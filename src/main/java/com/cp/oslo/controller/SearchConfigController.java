package com.cp.oslo.controller;

import com.cp.oslo.service.SearchConfigService;
import com.cp.oslo.service.SearchConfigService.InvalidValueException;
import com.cp.oslo.service.SearchConfigService.UnavailableException;
import com.cp.oslo.service.SearchConfigService.UnknownKeyException;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 검색 설정 관리 API — 상담AP 관리 화면이 호출한다. 응답은 키와 값만.
 * 반영 시점: Size·Weight·Fields는 다음 검색부터, 나머지는 다음 인덱스 재생성(정기 동기화)부터.
 */
@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
public class SearchConfigController {

    private final SearchConfigService configService;

    /** 필드 이름은 CI 설정 API와 같게 둔다. */
    public record ConfigUpdate(String value, @JsonProperty("mdfr_id") String mdfrId) {}

    @GetMapping
    public List<Map<String, String>> list() {
        return configService.list();
    }

    @GetMapping("/{key}")
    public Map<String, String> get(@PathVariable String key) {
        return configService.get(key);
    }

    @PutMapping("/{key}")
    public Map<String, String> put(@PathVariable String key, @RequestBody ConfigUpdate body) {
        return configService.put(key, body.value(), body.mdfrId());
    }

    /** 저장한 값을 지우고 기본값으로 되돌린다. */
    @DeleteMapping("/{key}")
    public Map<String, String> delete(@PathVariable String key) {
        return configService.delete(key);
    }

    @ExceptionHandler(UnknownKeyException.class)
    public ResponseEntity<Map<String, String>> unknown(UnknownKeyException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "등록되지 않은 설정 키입니다: " + e.getMessage()));
    }

    @ExceptionHandler(InvalidValueException.class)
    public ResponseEntity<Map<String, String>> invalid(InvalidValueException e) {
        return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler(UnavailableException.class)
    public ResponseEntity<Map<String, String>> unavailable(UnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("detail", e.getMessage()));
    }
}
