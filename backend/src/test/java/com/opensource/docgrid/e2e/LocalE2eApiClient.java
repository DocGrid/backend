package com.opensource.docgrid.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.boot.test.web.client.TestRestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.opensource.docgrid.e2e.LocalE2eDocumentFactory.DocumentPayload;

/**
 * 로컬 전체 관통 E2E가 실제 인증·Multipart·관리자 HTTP 경계를 같은 방식으로 호출하게 한다.
 *
 * <p>Seed ADMIN은 Test Schema 안에서만 사용하며, 응답의 공통 Envelope를 제품 DTO와 분리해 HTTP
 * 직렬화 계약 자체를 검증한다.
 */
final class LocalE2eApiClient {

    private static final String ADMIN_EMAIL = "kcw130502@gmail.com";
    private static final String ADMIN_PASSWORD = "admin1234";

    private final TestRestTemplate restTemplate;

    LocalE2eApiClient(TestRestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    String loginAdmin() {
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
            "/auth/login",
            new LoginBody(ADMIN_EMAIL, ADMIN_PASSWORD),
            JsonNode.class
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        String accessToken = response.getBody().path("data").path("accessToken").asText();
        assertThat(accessToken).isNotBlank();
        return accessToken;
    }

    UploadedDocument upload(String accessToken, DocumentPayload payload) {
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(payload.mediaType());

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(LocalE2eDocumentFactory.resource(payload), fileHeaders));
        body.add("title", payload.title());
        body.add("description", "로컬 전체 관통 E2E");
        body.add("visibility", "PRIVATE");

        HttpHeaders headers = authorizedHeaders(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<JsonNode> response = restTemplate.exchange(
            "/api/documents",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            JsonNode.class
        );

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        JsonNode data = response.getBody().path("data");
        assertThat(data.path("jobStatus").asText()).isEqualTo("PENDING");
        return new UploadedDocument(
            data.path("documentId").asLong(),
            data.path("documentVersionId").asLong(),
            data.path("fileObjectId").asLong(),
            data.path("embeddingJobId").asLong()
        );
    }

    UploadedVersion uploadVersion(String accessToken, Long documentId, DocumentPayload payload) {
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(payload.mediaType());

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(LocalE2eDocumentFactory.resource(payload), fileHeaders));

        HttpHeaders headers = authorizedHeaders(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<JsonNode> response = restTemplate.exchange(
            "/api/documents/" + documentId + "/versions",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            JsonNode.class
        );

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        JsonNode data = response.getBody().path("data");
        assertThat(data.path("jobStatus").asText()).isEqualTo("PENDING");
        return new UploadedVersion(
            data.path("documentId").asLong(),
            data.path("documentVersionId").asLong(),
            data.path("versionNo").asInt(),
            data.path("embeddingJobId").asLong(),
            data.path("currentVersionId").isNull()
                ? null
                : data.path("currentVersionId").asLong()
        );
    }

    ResponseEntity<JsonNode> get(String accessToken, String path) {
        return restTemplate.exchange(
            path,
            HttpMethod.GET,
            new HttpEntity<>(authorizedHeaders(accessToken)),
            JsonNode.class
        );
    }

    ResponseEntity<JsonNode> get(String path) {
        return restTemplate.getForEntity(path, JsonNode.class);
    }

    private HttpHeaders authorizedHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }

    /** 실제 로그인 요청의 JSON 계약을 표현한다. */
    private record LoginBody(String email, String password) {
    }

    /** 업로드 접수 뒤 전체 관통 상태를 추적하는 네 식별자만 보존한다. */
    record UploadedDocument(
        Long documentId,
        Long documentVersionId,
        Long fileObjectId,
        Long embeddingJobId
    ) {
    }

    /** 새 버전 접수 뒤 current 전환과 Job 완료를 추적하는 식별자를 보존한다. */
    record UploadedVersion(
        Long documentId,
        Long documentVersionId,
        int versionNo,
        Long embeddingJobId,
        Long currentVersionIdAtUpload
    ) {
    }
}
