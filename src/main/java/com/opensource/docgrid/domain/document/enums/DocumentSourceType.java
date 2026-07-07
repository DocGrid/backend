package com.opensource.docgrid.domain.document.enums;

/**
 * 문서가 시스템에 유입된 경로.
 * UPLOAD: 직접 업로드, URL: URL 수집, API: 외부 API 연동, MCP: MCP 연동.
 */
public enum DocumentSourceType {
    UPLOAD,
    URL,
    API,
    MCP
}
