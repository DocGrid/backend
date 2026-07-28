package com.opensource.docgrid.domain.rag.dto.request;

public record OllamaGenerateRequest(String model, String prompt, boolean stream) {
}
