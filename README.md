# Search Manager

MariaDB 데이터를 OpenSearch로 인덱싱하고 검색하는 Spring Boot 애플리케이션입니다.

## 주요 기능

- ✅ MariaDB 테이블을 OpenSearch 인덱스로 자동 동기화
- ✅ 여러 테이블/인덱스 관리 지원
- ✅ 동적 필드 매핑 설정
- ✅ Bulk 인덱싱으로 대용량 데이터 처리
- ✅ 동기화 이력 관리
- ✅ REST API 제공

## 기술 스택

- Java 17
- Spring Boot 3.2.0
- Spring Data JPA
- MariaDB
- OpenSearch 2.11.0
- Gradle

## 시작하기

### 1. 사전 요구사항

- JDK 17 이상
- MariaDB 10.x 이상
- Docker 및 Docker Compose
- Python 3.10+ (Search Intelligence Service용)

### 2. .env 파일 설정

프로젝트의 각 서비스(`search-manager`, `opensearch`, `search-intelligence`)는 `.env` 파일을 통해 환경 변수를 설정합니다. `env.example` 파일을 복사하여 `.env` 파일을 생성하고 필요한 값을 설정합니다.

```bash
cp .env.example .env
# search-intelligence 디렉토리에도 .env 파일 생성
cp search-intelligence/.env.example search-intelligence/.env
```

`.env` 파일에서 다음 변수들을 확인하고 필요에 따라 수정합니다:
*   `DB_URL`, `DB_USERNAME`, `DB_PASS`: MariaDB 연결 정보
*   `OPEN_SEARCH_HOST`, `OPEN_SEARCH_PORT_1`, `OPEN_SEARCH_USERNAME`, `OPEN_SEARCH_PASSWORD`: OpenSearch 연결 정보
*   `SEARCH_INTELLIGENCE_URL`, `EMBEDDING_PORT`: Search Intelligence Service 연결 정보
*   `OPENSEARCH_INITIAL_ADMIN_PASSWORD`: OpenSearch 초기 관리자 비밀번호 (필요시 설정)

### 3. OpenSearch 및 Nori 플러그인 실행

`analysis-nori` 플러그인이 포함된 OpenSearch 이미지를 빌드하고 실행합니다. 프로젝트 내 `opensearch/Dockerfile`을 사용합니다.

```bash
# 1. OpenSearch 이미지 빌드 (analysis-nori 플러그인 포함)
# 이 명령은 opensearch/Dockerfile을 사용하여 'custom-opensearch:3.3.2' 이미지를 생성합니다.
docker build -t custom-opensearch:3.3.2 ./opensearch

# 2. OpenSearch 컨테이너 실행
# .env 파일에 설정된 포트(9200, 9600)로 OpenSearch를 실행합니다.
docker run -d \
  --name opensearch-node \
  -p ${OPEN_SEARCH_PORT_1:-9200}:9200 \
  -p ${OPEN_SEARCH_PORT_2:-9600}:9600 \
  -e "discovery.type=single-node" \
  -e "cluster.name=opensearch-cluster" \
  -e "node.name=opensearch-node" \
  -e "DISABLE_SECURITY_PLUGIN=true" \
  -e "OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m" \
  custom-opensearch:3.3.2
  
# 3. 또는 OpenSearch를 그냥 pull 해서 nori 플러그인 설치 후 실행합니다.(간단)
# 3.3.2 버전 풀
docker pull opensearchproject/opensearch:3.3.2

# 컨테이너를 실행
docker run -d \
  --name opensearch-node \
  -p ${OPEN_SEARCH_PORT_1:-9200}:9200 \
  -p ${OPEN_SEARCH_PORT_2:-9600}:9600 \
  -e "discovery.type=single-node" \
  -e "cluster.name=opensearch-cluster" \
  -e "node.name=opensearch-node" \
  -e "DISABLE_SECURITY_PLUGIN=true" \
  -e "OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m" \
  opensearchproject/opensearch:3.3.2

# nori 플러그인 설치
docker exec -it opensearch-node ./bin/opensearch-plugin install analysis-nori

# 이 후 컨테이너 재시작
docker restart opensearch-node

# 평소에 opensearch 시작
docker start opensearch-node

```

### 4. Search Intelligence Service 실행

OpenSearch가 실행된 후, 벡터 임베딩을 제공하는 Search Intelligence Service를 실행합니다.

```bash
# 1. search-intelligence 디렉토리로 이동
cd search-intelligence

# 2. Python 가상 환경 생성 및 활성화
python3 -m venv venv
source venv/bin/activate

# 3. 필요한 라이브러리 설치 (requirements.txt에 명시된 버전으로 설치, uvicorn 포함)
pip install -r requirements.txt

# 4. Search Intelligence Service 실행 (백그라운드 실행을 권장합니다)
# .env 파일에 설정된 EMBEDDING_PORT (기본 8000)로 실행됩니다.
uvicorn search_intelligence:app --host 0.0.0.0 --port ${EMBEDDING_PORT:-8000}
```

### 5. Spring Boot 애플리케이션 실행

Spring Boot 애플리케이션을 실행하기 전에, `.env` 파일에 설정된 환경 변수를 애플리케이션이 인식하도록 해야 합니다.

#### 5.1. IntelliJ IDEA에서 실행 (권장)

IntelliJ IDEA에서 `.env` 파일을 통해 환경 변수를 로드하려면 'EnvFile' 플러그인을 설치하는 것을 권장합니다.

1.  **EnvFile 플러그인 설치**:
    *   IntelliJ IDEA 설정(Preferences/Settings)에서 'Plugins'를 검색합니다.
    *   'Marketplace' 탭에서 'EnvFile'을 검색하여 설치합니다.
    *   IntelliJ IDEA를 재시작합니다.
2.  **실행 구성(Run/Debug Configurations) 설정**:
    *   `SearchManagerApplication`을 선택하거나 새로운 Spring Boot 실행 구성을 생성합니다.
    *   'Environment variables' 섹션에서 'Enable EnvFile' 체크박스를 선택합니다.
    *   '+' 버튼을 클릭하여 프로젝트 루트에 있는 `.env` 파일을 추가합니다.
    *   'Apply' 또는 'OK'를 클릭하여 설정을 저장합니다.

이제 `SearchManagerApplication`을 실행하면 `.env` 파일의 환경 변수들이 자동으로 로드됩니다.

#### 5.2. Gradle 또는 JAR 파일로 실행

`.env` 파일 없이 Gradle 또는 빌드된 JAR 파일로 실행할 경우, `application.yml` 또는 시스템 환경 변수를 통해 직접 설정해야 합니다.

```bash
# Gradle로 실행 (환경 변수를 직접 넘겨주어야 할 수 있습니다)
./gradlew bootRun

# 또는 JAR 빌드 후 실행 (환경 변수를 직접 넘겨주어야 할 수 있습니다)
./gradlew build
java -jar build/libs/search-manager-1.0.0.jar
```

애플리케이션은 기본적으로 `http://localhost:9400`에서 실행됩니다 (SERVER_PORT 변수에 따름).

## 빠른 시작: uvw_manual 인덱싱

**OpenSearch 및 Search Intelligence Service가 먼저 실행 중인지 확인하세요.**

```bash
# 1. 인덱스 생성 (인덱스 생성 + 데이터 동기화 자동 처리)
curl -X POST http://localhost:9400/api/v1/manual/create-index

# 2. 검색 테스트
curl "http://localhost:9400/api/v1/search/manual?query=시의원"
```

자세한 가이드는 [examples/MANUAL_INDEX_README.md](examples/MANUAL_INDEX_README.md)를 참고하세요.

## API 사용법

### 1. 인덱스 설정 생성

```bash
curl -X POST http://localhost:8080/api/v1/indexes \
  -H "Content-Type: application/json" \
  -d '{
    "indexName": "products",
    "description": "상품 검색 인덱스",
    "sourceTableName": "products",
    "numberOfShards": 1,
    "numberOfReplicas": 1,
    "enabled": true,
    "autoSync": false,
    "fieldMappings": [
      {
        "sourceColumnName": "id",
        "fieldType": "LONG",
        "indexed": true,
        "sortOrder": 0
      },
      {
        "sourceColumnName": "name",
        "targetFieldName": "product_name",
        "fieldType": "TEXT",
        "analyzer": "standard",
        "indexed": true,
        "sortOrder": 1
      },
      {
        "sourceColumnName": "description",
        "fieldType": "TEXT",
        "analyzer": "standard",
        "indexed": true,
        "sortOrder": 2
      },
      {
        "sourceColumnName": "price",
        "fieldType": "DOUBLE",
        "indexed": true,
        "sortOrder": 3
      },
      {
        "sourceColumnName": "created_at",
        "fieldType": "DATETIME",
        "indexed": true,
        "sortOrder": 4
      }
    ]
  }'
```

### 2. 모든 인덱스 설정 조회

```bash
curl http://localhost:8080/api/v1/indexes
```

### 3. 특정 인덱스 설정 조회

```bash
curl http://localhost:8080/api/v1/indexes/1
```

### 4. 인덱스 동기화 실행

```bash
# 특정 인덱스 동기화
curl -X POST http://localhost:9400/api/v1/indexes/1/sync

# 모든 활성화된 인덱스 동기화
curl -X POST http://localhost:9400/api/v1/indexes/sync-all
```

### 5. 동기화 이력 조회

```bash
curl http://localhost:9400/api/v1/indexes/1/history
```

### 6. 검색 실행

```bash
# 전체 검색
curl "http://localhost:9400/api/v1/search/products?query=*"

# 특정 키워드 검색
curl "http://localhost:9400/api/v1/search/products?query=노트북"
```

### 7. 인덱스 삭제

```bash
curl -X DELETE http://localhost:9400/api/v1/indexes/1
```

## 한국어 검색 설정

한국어 형태소 분석을 위해서는 OpenSearch의 `analysis-nori` 플러그인을 사용합니다.



### 한국어 인덱스 설정 예제

`examples/manual-index-config.json` 파일의 일부입니다.

```json
{
  "indexName": "manual",
  "sourceTableName": "uvw_manual",
  "fieldMappings": [
    {
      "sourceColumnName": "TITLE",
      "targetFieldName": "title",
      "fieldType": "TEXT",
      "analyzer": "nori"
    },
    {
      "sourceColumnName": "CONTENTS",
      "targetFieldName": "contents",
      "fieldType": "TEXT",
      "analyzer": "nori"
    },
    {
      "sourceColumnName": "HTML",
      "targetFieldName": "html",
      "fieldType": "TEXT",
      "analyzer": "nori"
    }
  ]
}
```

## 필드 타입

지원하는 OpenSearch 필드 타입:

- `TEXT` - 전문 검색용 텍스트
- `KEYWORD` - 정확한 매칭용 키워드
- `INTEGER` - 정수
- `LONG` - 긴 정수
- `DOUBLE` - 부동소수점
- `FLOAT` - 부동소수점
- `BOOLEAN` - 불린
- `DATE` - 날짜
- `DATETIME` - 날짜+시간
- `OBJECT` - 중첩 객체
- `GEO_POINT` - 지리적 좌표

## 프로젝트 구조

```
search-manager/
├── src/main/java/com/search/manager/
│   ├── config/          # 설정 클래스
│   ├── controller/      # REST API 컨트롤러
│   ├── domain/          # 엔티티 클래스
│   ├── dto/             # DTO 클래스
│   ├── repository/      # JPA 리포지토리
│   ├── service/         # 비즈니스 로직
│   └── SearchManagerApplication.java
├── src/main/resources/
│   └── application.yml
└── build.gradle
```

