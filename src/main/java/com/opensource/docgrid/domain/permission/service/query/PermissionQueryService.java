package com.opensource.docgrid.domain.permission.service.query;

import java.util.ArrayList;
import java.util.List;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.permission.dto.response.DocumentPermissionSummaryResponse;
import com.opensource.docgrid.domain.permission.enums.PermissionSourceType;
import com.opensource.docgrid.domain.permission.repository.CollectionPermissionRepository;
import com.opensource.docgrid.domain.permission.repository.DocumentPermissionRepository;
import com.opensource.docgrid.domain.permission.repository.UserDocumentAccessCacheRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
public class PermissionQueryService {

    private final CollectionRepository collectionRepository;
    private final DocumentRepository documentRepository;
    private final UserDocumentAccessCacheRepository cacheRepository;
    private final DocumentPermissionRepository documentPermissionRepository;
    private final CollectionPermissionRepository collectionPermissionRepository;

    // 문서 읽기 권한 판단 (5단계)
    public boolean canReadDocument(Long userId, Long documentId) {
        long start = System.nanoTime();
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));

        // 1단계: 소유자
        long t1 = System.nanoTime();
        if (document.getOwner().getId().equals(userId)) {
            log.info("[PERM] canRead owner=true doc={} user={} elapsed={}ms", documentId, userId, ms(start));
            return true;
        }

        // 2단계: PUBLIC
        long t2 = System.nanoTime();
        double step1Ms = (t2 - t1) / 1_000_000.0;
        if (document.getVisibility() == VisibilityType.PUBLIC) {
            log.info("[PERM] canRead public=true doc={} user={} step1={}ms elapsed={}ms",
                    documentId, userId, step1Ms, ms(start));
            return true;
        }

        // 3단계: USER 캐시
        long t3 = System.nanoTime();
        double step2Ms = (t3 - t2) / 1_000_000.0;
        if (cacheRepository.existsValidReadCache(userId, documentId)) {
            log.info("[PERM] canRead cache=true doc={} user={} step2={}ms elapsed={}ms",
                    documentId, userId, step2Ms, ms(start));
            return true;
        }

        // 4단계: ROLE live
        long t4 = System.nanoTime();
        double step3Ms = (t4 - t3) / 1_000_000.0;
        if (documentPermissionRepository.existsRoleReadPermission(userId, documentId)
                || collectionPermissionRepository.existsRoleReadPermissionForDocument(userId, documentId)) {
            log.info("[PERM] canRead role=true doc={} user={} step3={}ms elapsed={}ms",
                    documentId, userId, step3Ms, ms(start));
            return true;
        }

        // 5단계: DEPARTMENT live
        long t5 = System.nanoTime();
        double step4Ms = (t5 - t4) / 1_000_000.0;
        if (documentPermissionRepository.existsDeptReadPermission(userId, documentId)
                || collectionPermissionRepository.existsDeptReadPermissionForDocument(userId, documentId)) {
            log.info("[PERM] canRead dept=true doc={} user={} step4={}ms elapsed={}ms",
                    documentId, userId, step4Ms, ms(start));
            return true;
        }

        double step5Ms = (System.nanoTime() - t5) / 1_000_000.0;
        log.info("[PERM] canRead denied doc={} user={} step5={}ms elapsed={}ms",
                documentId, userId, step5Ms, ms(start));
        return false;
    }

    // 문서 쓰기 권한 판단 (4단계)
    public boolean canWriteDocument(Long userId, Long documentId) {
        long start = System.nanoTime();
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));

        long t1 = System.nanoTime();
        if (document.getOwner().getId().equals(userId)) {
            log.info("[PERM] canWrite owner=true doc={} user={} elapsed={}ms", documentId, userId, ms(start));
            return true;
        }

        long t2 = System.nanoTime();
        double step1Ms = (t2 - t1) / 1_000_000.0;
        if (cacheRepository.existsValidWriteCache(userId, documentId)) {
            log.info("[PERM] canWrite cache=true doc={} user={} step1={}ms elapsed={}ms",
                    documentId, userId, step1Ms, ms(start));
            return true;
        }

        long t3 = System.nanoTime();
        double step2Ms = (t3 - t2) / 1_000_000.0;
        if (documentPermissionRepository.existsRoleWritePermission(userId, documentId)
                || collectionPermissionRepository.existsRoleWritePermissionForDocument(userId, documentId)) {
            log.info("[PERM] canWrite role=true doc={} user={} step2={}ms elapsed={}ms",
                    documentId, userId, step2Ms, ms(start));
            return true;
        }

        long t4 = System.nanoTime();
        double step3Ms = (t4 - t3) / 1_000_000.0;
        if (documentPermissionRepository.existsDeptWritePermission(userId, documentId)
                || collectionPermissionRepository.existsDeptWritePermissionForDocument(userId, documentId)) {
            log.info("[PERM] canWrite dept=true doc={} user={} step3={}ms elapsed={}ms",
                    documentId, userId, step3Ms, ms(start));
            return true;
        }

        double step4Ms = (System.nanoTime() - t4) / 1_000_000.0;
        log.info("[PERM] canWrite denied doc={} user={} step4={}ms elapsed={}ms",
                documentId, userId, step4Ms, ms(start));
        return false;
    }

    // 문서 관리 권한 판단 (4단계)
    public boolean canAdminDocument(Long userId, Long documentId) {
        long start = System.nanoTime();
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));

        long t1 = System.nanoTime();
        if (document.getOwner().getId().equals(userId)) {
            log.info("[PERM] canAdmin owner=true doc={} user={} elapsed={}ms", documentId, userId, ms(start));
            return true;
        }

        long t2 = System.nanoTime();
        double step1Ms = (t2 - t1) / 1_000_000.0;
        if (cacheRepository.existsValidAdminCache(userId, documentId)) {
            log.info("[PERM] canAdmin cache=true doc={} user={} step1={}ms elapsed={}ms",
                    documentId, userId, step1Ms, ms(start));
            return true;
        }

        long t3 = System.nanoTime();
        double step2Ms = (t3 - t2) / 1_000_000.0;
        if (documentPermissionRepository.existsRoleAdminPermission(userId, documentId)
                || collectionPermissionRepository.existsRoleAdminPermissionForDocument(userId, documentId)) {
            log.info("[PERM] canAdmin role=true doc={} user={} step2={}ms elapsed={}ms",
                    documentId, userId, step2Ms, ms(start));
            return true;
        }

        long t4 = System.nanoTime();
        double step3Ms = (t4 - t3) / 1_000_000.0;
        if (documentPermissionRepository.existsDeptAdminPermission(userId, documentId)
                || collectionPermissionRepository.existsDeptAdminPermissionForDocument(userId, documentId)) {
            log.info("[PERM] canAdmin dept=true doc={} user={} step3={}ms elapsed={}ms",
                    documentId, userId, step3Ms, ms(start));
            return true;
        }

        double step4Ms = (System.nanoTime() - t4) / 1_000_000.0;
        log.info("[PERM] canAdmin denied doc={} user={} step4={}ms elapsed={}ms",
                documentId, userId, step4Ms, ms(start));
        return false;
    }

    // 문서 권한 확인 API용 — read/write/admin 동시 판단 + 접근 경로(sources) 수집
    public DocumentPermissionSummaryResponse checkDocumentPermission(Long userId, Long documentId) {
        long start = System.nanoTime();
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));

        List<PermissionSourceType> sources = new ArrayList<>();
        boolean canRead = false, canWrite = false, canAdmin = false;

        // 1단계: 소유자 — 전체 권한 즉시 반환
        if (document.getOwner().getId().equals(userId)) {
            sources.add(PermissionSourceType.OWNER);
            log.info("[PERM] checkDoc owner doc={} user={} elapsed={}ms", documentId, userId, ms(start));
            return new DocumentPermissionSummaryResponse(documentId, true, true, true, sources);
        }

        // 2단계: PUBLIC — 읽기만 허용
        if (document.getVisibility() == VisibilityType.PUBLIC) {
            canRead = true;
            sources.add(PermissionSourceType.PUBLIC);
        }

        // 3단계: USER 캐시
        boolean cacheRead  = cacheRepository.existsValidReadCache(userId, documentId);
        boolean cacheWrite = cacheRepository.existsValidWriteCache(userId, documentId);
        boolean cacheAdmin = cacheRepository.existsValidAdminCache(userId, documentId);
        if (cacheRead || cacheWrite || cacheAdmin) {
            sources.add(PermissionSourceType.USER_CACHE);
            if (cacheRead)  canRead  = true;
            if (cacheWrite) canWrite = true;
            if (cacheAdmin) canAdmin = true;
        }

        // 4단계: ROLE live
        boolean roleRead  = documentPermissionRepository.existsRoleReadPermission(userId, documentId)
                || collectionPermissionRepository.existsRoleReadPermissionForDocument(userId, documentId);
        boolean roleWrite = documentPermissionRepository.existsRoleWritePermission(userId, documentId)
                || collectionPermissionRepository.existsRoleWritePermissionForDocument(userId, documentId);
        boolean roleAdmin = documentPermissionRepository.existsRoleAdminPermission(userId, documentId)
                || collectionPermissionRepository.existsRoleAdminPermissionForDocument(userId, documentId);
        if (roleRead || roleWrite || roleAdmin) {
            sources.add(PermissionSourceType.ROLE);
            if (roleRead)  canRead  = true;
            if (roleWrite) canWrite = true;
            if (roleAdmin) canAdmin = true;
        }

        // 5단계: DEPARTMENT live
        boolean deptRead  = documentPermissionRepository.existsDeptReadPermission(userId, documentId)
                || collectionPermissionRepository.existsDeptReadPermissionForDocument(userId, documentId);
        boolean deptWrite = documentPermissionRepository.existsDeptWritePermission(userId, documentId)
                || collectionPermissionRepository.existsDeptWritePermissionForDocument(userId, documentId);
        boolean deptAdmin = documentPermissionRepository.existsDeptAdminPermission(userId, documentId)
                || collectionPermissionRepository.existsDeptAdminPermissionForDocument(userId, documentId);
        if (deptRead || deptWrite || deptAdmin) {
            sources.add(PermissionSourceType.DEPARTMENT);
            if (deptRead)  canRead  = true;
            if (deptWrite) canWrite = true;
            if (deptAdmin) canAdmin = true;
        }

        log.info("[PERM] checkDoc doc={} user={} canRead={} canWrite={} canAdmin={} sources={} elapsed={}ms",
                documentId, userId, canRead, canWrite, canAdmin, sources, ms(start));
        return new DocumentPermissionSummaryResponse(documentId, canRead, canWrite, canAdmin, sources);
    }

    // 컬렉션 쓰기 권한 판단 (소유자, USER/ROLE/DEPT 직접 권한)
    public boolean canWriteCollection(Long userId, Long collectionId) {
        DocumentCollection collection = collectionRepository.findById(collectionId)
                .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));
        if (collection.getOwner().getId().equals(userId)) return true;
        if (collectionPermissionRepository.existsUserWritePermission(userId, collectionId)) return true;
        if (collectionPermissionRepository.existsRoleWritePermissionForCollection(userId, collectionId)) return true;
        return collectionPermissionRepository.existsDeptWritePermissionForCollection(userId, collectionId);
    }

    // 컬렉션 관리 권한 판단 (소유자, USER/ROLE/DEPT 직접 권한)
    public boolean canAdminCollection(Long userId, Long collectionId) {
        DocumentCollection collection = collectionRepository.findById(collectionId)
                .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));
        if (collection.getOwner().getId().equals(userId)) return true;
        if (collectionPermissionRepository.existsUserAdminPermission(userId, collectionId)) return true;
        if (collectionPermissionRepository.existsRoleAdminPermissionForCollection(userId, collectionId)) return true;
        return collectionPermissionRepository.existsDeptAdminPermissionForCollection(userId, collectionId);
    }

    private double ms(long fromNano) {
        return (System.nanoTime() - fromNano) / 1_000_000.0;
    }
}
