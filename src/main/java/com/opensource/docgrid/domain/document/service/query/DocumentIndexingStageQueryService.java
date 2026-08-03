package com.opensource.docgrid.domain.document.service.query;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * Worker 파이프라인의 시작 단계를 결정할 문서 버전 상태 Snapshot을 조회한다.
 *
 * <p>조회 결과는 경로 선택에만 사용하며 Entity를 Worker 계층에 전달하지 않는다. 실제 상태 변경 가능
 * 여부와 소유권은 각 Command Service가 Job과 Version을 잠근 뒤 다시 검증한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentIndexingStageQueryService {

    private final DocumentVersionRepository documentVersionRepository;

    /**
     * Claim 응답이 가리키는 문서 버전의 현재 파이프라인 상태를 반환한다.
     */
    public DocumentVersionStatus getStatus(Long documentVersionId) {
        return documentVersionRepository.findById(documentVersionId)
            .map(documentVersion -> documentVersion.getStatus())
            .orElseThrow(() -> new DocGridException(ErrorCode.INDEXING_STATUS_INCONSISTENT));
    }
}
