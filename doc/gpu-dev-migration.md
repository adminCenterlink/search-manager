# search-manager GPU 개발기 이관 가이드

## 개요

search-manager를 GPU 개발기로 이관하면서, 임베딩·리랭킹·OCR을 담당하던 `search-intelligence`를 별도 컨테이너로 두지 않고 `contact-intelligence`의 Feature로 통합했다. 목적은 파이썬 ML 서비스 컨테이너 중복 제거와 GPU 활용이다.

### 구성 서버

| 역할 | 주소 | 비고 |
| :--- | :--- | :--- |
| GPU 개발기 | 192.168.0.5 (Ubuntu) | search-manager(docker), contact-intelligence(host native), opensearch(docker) |
| 파일 서버(WAS) | 192.168.0.9 (Rocky Linux 9) | 인덱싱 대상 파일 보관, NFS export |
| MariaDB | centerlink.kr:6033 / callcenter_verona | 원격 |

## 아키텍처 변경

- 이관 전: `search-manager` + `opensearch` + `search-intelligence` 세 컨테이너를 docker-compose로 관리.
- 이관 후: `search-manager`만 docker-compose로 관리한다. `opensearch`는 별도 컨테이너, `contact-intelligence`는 host native(포트 8180)로 둔다. `search-intelligence`는 제거한다.

search-manager는 두 외부 의존성(opensearch 9200, contact-intelligence 8180)을 모두 `host.docker.internal`로 접근한다.

## 1. search-intelligence의 contact-intelligence 통합

`contact-intelligence`에 두 Feature를 추가했다.

- `features/embedding`: `/search/embed`, `/search/embed/batch`, `/search/rerank`
- `features/ocr`: `/search/ocr`

리랭커는 GPU에서 `AutoModelForSequenceClassification` + `trust_remote_code` + CUDA + BF16으로 추론한다. CPU 전용 ONNX INT8 경로와 FlagEmbedding 의존성은 제거했다. OCR은 easyocr을 쓰며, torch 버전을 고정한 채 설치해야 한다(torchvision이 torch를 상향시키는 것을 막기 위함).

search-manager의 `SearchIntelligenceClient`는 이 엔드포인트를 호출한다. 호출 경로에 `/search` prefix를 붙였고, 헬스체크는 `/health`를 쓴다.

## 2. search-manager 변경

- `SearchIntelligenceClient.java`: 호출 uri를 `/search/*`로, 헬스체크를 `/docs`에서 `/health`로 변경.
- `docker-compose.yml`: `search-intelligence` 서비스 제거, `opensearch-node` 서비스 제거(별도 관리), `OPEN_SEARCH_HOST`·`SEARCH_INTELLIGENCE_HOST`를 `host.docker.internal`로, `extra_hosts`에 `host.docker.internal:host-gateway` 추가, `shared_net` external 네트워크 제거.

## 3. 경로 3분리 — 파일 / 로그 / 색인

파일이 원격(WAS)으로 분리되면서, 로컬에 쓰던 세 종류의 경로를 분리했다.

| 용도 | host 경로 | 성격 | 관련 설정 |
| :--- | :--- | :--- | :--- |
| 인덱싱 대상 파일 | `/home/centerlink/upload/file` | WAS NFS 마운트(읽기) | `UPLOAD_PATH` |
| 로그 | `/home/centerlink/upload/logs` | 로컬 | `LOG_PATH` |
| OpenSearch 색인 | `/home/centerlink/upload/search` | 로컬 | opensearch `-v` |

### 코드 변경 배경

기존에는 `FileIndexingService`가 파일 읽기 뿌리를 `logging.file.path`에서 가져와, 파일 경로와 로그 경로가 같은 값에 묶여 있었다. 파일이 로컬일 때는 문제가 없었으나, 파일을 원격 NFS로 옮기면 로그도 NFS에 쓰려는 문제가 생긴다. 그래서 다음을 분리했다.

- `FileIndexingService`: 파일 읽기 뿌리를 `${UPLOAD_PATH}` 직접 참조로 변경.
- `application.yml`: `logging.file.path`를 `${UPLOAD_PATH}`에서 `${LOG_PATH:/app/logs}`로 변경.
- `logback-spring.xml`: 로그 경로의 `/logs` 중복을 제거.

### 파일 경로 매핑 규칙

`FileIndexingService`는 DB `TB_FILE.SAVED_FILE_PATH`에서 접두어를 떼고 `UPLOAD_PATH`를 붙여 실제 파일 경로를 계산한다.

```
realPath = /app/upload + (SAVED_FILE_PATH - SEARCH_INDEXES_FILE_LOCAL_PATH_PREFIX)
```

- `SEARCH_INDEXES_FILE_LOCAL_PATH_PREFIX` = `/home/centerlink/app/upload` (DB `SAVED_FILE_PATH`의 접두어, `app` 포함)
- 마운트 지점(`UPLOAD_PATH`)은 `/home/centerlink/upload/file`이며, 접두어와 다른 값이다.

예: `SAVED_FILE_PATH=/home/centerlink/app/upload/2026/09` → 접두어 제거 → `/2026/09` → 컨테이너 `/app/upload/2026/09` → 개발기 `/home/centerlink/upload/file/2026/09` → NFS → WAS `/home/centerlink/app/upload/2026/09`.

## 4. OpenSearch 실행 (별도 컨테이너)

색인을 host에 영속화하고 analysis-nori를 설치한다. 색인 경로는 `UPLOAD_PATH`(NFS)와 겹치지 않는 별도 로컬 경로에 둔다.

```bash
# 색인 디렉토리 생성 및 소유권 부여 (opensearch 컨테이너는 내부 uid 1000으로 실행)
sudo mkdir -p /home/centerlink/upload/search
sudo chown -R 1000:1000 /home/centerlink/upload/search

# 컨테이너 실행 (색인 볼륨 마운트)
docker run -d \
  --name opensearch-node \
  -p 9200:9200 \
  -p 9600:9600 \
  -e "discovery.type=single-node" \
  -e "cluster.name=opensearch-cluster" \
  -e "node.name=opensearch-node" \
  -e "DISABLE_SECURITY_PLUGIN=true" \
  -e "OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m" \
  -v /home/centerlink/upload/search:/usr/share/opensearch/data \
  opensearchproject/opensearch:3.3.2

# nori 플러그인 설치 후 재시작
docker exec opensearch-node ./bin/opensearch-plugin install --batch analysis-nori
docker restart opensearch-node
```

## 5. NFS 파일 마운트

### 파일 서버(192.168.0.9, Rocky Linux 9) — export

```bash
sudo dnf install -y nfs-utils
sudo systemctl enable --now nfs-server
echo '/home/centerlink/app/upload 192.168.0.5(ro,sync,no_subtree_check,root_squash)' \
  | sudo tee /etc/exports.d/search-files.exports
sudo exportfs -rav
sudo firewall-cmd --permanent --add-service=nfs
sudo firewall-cmd --permanent --add-service=rpc-bind
sudo firewall-cmd --permanent --add-service=mountd
sudo firewall-cmd --reload
sudo setsebool -P nfs_export_all_ro on
sudo exportfs -v && showmount -e localhost
```

- export 경로는 DB `SAVED_FILE_PATH`의 접두어인 `/home/centerlink/app/upload`이다.
- 개발기 단일 IP(192.168.0.5)에만 읽기 전용(`ro`)으로 연다.

### 개발기(192.168.0.5, Ubuntu) — 마운트

```bash
sudo apt update && sudo apt install -y nfs-common
sudo mkdir -p /home/centerlink/upload/file
sudo mount -t nfs 192.168.0.9:/home/centerlink/app/upload /home/centerlink/upload/file
# 영구 마운트
echo '192.168.0.9:/home/centerlink/app/upload  /home/centerlink/upload/file  nfs  ro,_netdev  0  0' \
  | sudo tee -a /etc/fstab
```

`root_squash`를 쓰므로, 파일에 others 읽기 권한(`o+r`)이 있어야 컨테이너가 읽을 수 있다.

## 6. 배포 절차

개발기는 amd64이므로 arm64 맥에서 만든 이미지는 실행되지 않는다. 개발기에서 네이티브 빌드하거나 `--platform linux/amd64`로 크로스 빌드한다.

```bash
docker compose build search-manager
docker compose up -d --force-recreate search-manager
```

- `docker restart`는 environment·볼륨 변경을 반영하지 않는다. 변경 반영에는 `up --force-recreate`가 필요하다.
- opensearch를 compose 밖에서 관리하므로 `depends_on`이 없다. `--no-deps`도 필요 없다.

## 환경변수 정리 (.env)

`.env`는 저장소에 커밋하지 않는다(민감 정보 포함). 이관에 관련된 값은 다음과 같다.

| 변수 | 값(개발기) | 의미 |
| :--- | :--- | :--- |
| `UPLOAD_PATH` | `/home/centerlink/upload/file` | 파일(NFS 마운트 지점) |
| `LOG_PATH` | `/home/centerlink/upload/logs` | 로그(로컬) |
| `SEARCH_INTELLIGENCE_HOST` | `http://host.docker.internal` | contact-intelligence 접근 |
| `SEARCH_INTELLIGENCE_PORT` | `8180` | contact-intelligence 포트 |

docker-compose의 `environment`는 컨테이너 내부 값을 오버라이드한다.

| 변수 | 값(컨테이너) | 의미 |
| :--- | :--- | :--- |
| `UPLOAD_PATH` | `/app/upload` | 컨테이너 내부 파일 경로 |
| `LOG_PATH` | `/app/logs` | 컨테이너 내부 로그 경로 |
| `OPEN_SEARCH_HOST` | `host.docker.internal` | opensearch 접근 |
| `SEARCH_INTELLIGENCE_HOST` | `http://host.docker.internal` | contact-intelligence 접근 |
| `SEARCH_INDEXES_FILE_LOCAL_PATH_PREFIX` | `/home/centerlink/app/upload` | DB 경로 접두어(`app` 포함) |

## 로그 위치

로그는 host `LOG_PATH` 아래에 쌓인다.

- 일반 로그: `/home/centerlink/upload/logs/search-manager/search-manager-80.log`
- 에러 로그: `/home/centerlink/upload/logs/search-manager/errors/search-manager-80.error.log`

파일명의 `80`은 컨테이너 내부 포트다(Dockerfile이 `-Dserver.port=80`으로 실행). host 노출 포트는 9400이다.
