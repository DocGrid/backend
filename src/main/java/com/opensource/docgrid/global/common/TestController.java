package com.opensource.docgrid.global.common;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.Builder;

@Profile("local")
@RestController
@RequestMapping("/test")
public class TestController {

    @Builder
    record SampleData(String id, String message) {
    }

    @GetMapping("/success")
    public ResponseEntity<ApiResponse<SampleData>> success() {
        SampleData data = SampleData.builder()
            .id("docgrid-1")
            .message("공통 응답 정상 동작")
            .build();
        return ResponseUtils.ok(data);
    }

    @GetMapping("/success/empty")
    public ResponseEntity<ApiResponse<Void>> successEmpty() {
        return ResponseUtils.ok();
    }

    @GetMapping("/created")
    public ResponseEntity<ApiResponse<SampleData>> created() {
        SampleData data = SampleData.builder()
            .id("docgrid-new")
            .message("리소스 생성")
            .build();
        return ResponseUtils.created(data);
    }

    @GetMapping("/no-content")
    public ResponseEntity<ApiResponse<Void>> noContent() {
        return ResponseUtils.noContent();
    }

    @GetMapping("/exception/business")
    public ResponseEntity<ApiResponse<Void>> businessException() {
        throw new DocGridException(ErrorCode.USER_NOT_FOUND);
    }

    @GetMapping("/exception/server")
    public ResponseEntity<ApiResponse<Void>> serverException() {
        throw new RuntimeException("의도적 서버 오류");
    }

    @GetMapping("/exception/missing-param")
    public ResponseEntity<ApiResponse<String>> missingParam(@RequestParam String name) {
        return ResponseUtils.ok(name);
    }

    @GetMapping("/exception/type-mismatch")
    public ResponseEntity<ApiResponse<Integer>> typeMismatch(@RequestParam Integer count) {
        return ResponseUtils.ok(count);
    }
}
