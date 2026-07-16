package com.opensource.docgrid.domain.permission.service.query;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
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
        if (document.getVisibility() == VisibilityType.PUBLIC) {
            log.info("[PERM] canRead public=true doc={} user={} step1={}ms elapsed={}ms",
                    documentId, userId, ms(t1), ms(start));
            return true;
        }

        // 3단계: USER 캐시
        long t3 = System.nanoTime();
        if (cacheRepository.existsValidReadCache(userId, documentId)) {
            log.info("[PERM] canRead cache=true doc={} user={} step2={}ms elapsed={}ms",
                    documentId, userId, ms(t2), ms(start));
            return true;
        }

        // 4단계: ROLE live
        long t4 = System.nanoTime();
        if (documentPermissionRepository.existsRoleReadPermission(userId, documentId)
                || collectionPermissionRepository.existsRoleReadPermissionForDocument(userId, documentId)) {
            log.info("[PERM] canRead role=true doc={} user={} step3={}ms elapsed={}ms",
                    documentId, userId, ms(t3), ms(start));
            return true;
        }

        // 5단계: DEPARTMENT live
        long t5 = System.nanoTime();
        if (documentPermissionRepository.existsDeptReadPermission(userId, documentId)
                || collectionPermissionRepository.existsDeptReadPermissionForDocument(userId, documentId)) {
            log.info("[PERM] canRead dept=true doc={} user={} step4={}ms elapsed={}ms",
                    documentId, userId, ms(t4), ms(start));
            return true;
        }

        log.info("[PERM] canRead denied doc={} user={} step5={}ms elapsed={}ms",
                documentId, userId, ms(t5), ms(start));
        return false;
    }

    // 문서 쓰기 권한 판단 (5단계)
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
        if (cacheRepository.existsValidWriteCache(userId, documentId)) {
            log.info("[PERM] canWrite cache=true doc={} user={} step1={}ms elapsed={}ms",
                    documentId, userId, ms(t1), ms(start));
            return true;
        }

        long t3 = System.nanoTime();
        if (documentPermissionRepository.existsRoleWritePermission(userId, documentId)
                || collectionPermissionRepository.existsRoleWritePermissionForDocument(userId, documentId)) {
            log.info("[PERM] canWrite role=true doc={} user={} step2={}ms elapsed={}ms",
                    documentId, userId, ms(t2), ms(start));
            return true;
        }

        long t4 = System.nanoTime();
        if (documentPermissionRepository.existsDeptWritePermission(userId, documentId)
                || collectionPermissionRepository.existsDeptWritePermissionForDocument(userId, documentId)) {
            log.info("[PERM] canWrite dept=true doc={} user={} step3={}ms elapsed={}ms",
                    documentId, userId, ms(t3), ms(start));
            return true;
        }

        log.info("[PERM] canWrite denied doc={} user={} step4={}ms elapsed={}ms",
                documentId, userId, ms(t4), ms(start));
        return false;
    }

    // 문서 관리 권한 판단 (5단계)
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
        if (cacheRepository.existsValidAdminCache(userId, documentId)) {
            log.info("[PERM] canAdmin cache=true doc={} user={} step1={}ms elapsed={}ms",
                    documentId, userId, ms(t1), ms(start));
            return true;
        }

        long t3 = System.nanoTime();
        if (documentPermissionRepository.existsRoleAdminPermission(userId, documentId)
                || collectionPermissionRepository.existsRoleAdminPermissionForDocument(userId, documentId)) {
            log.info("[PERM] canAdmin role=true doc={} user={} step2={}ms elapsed={}ms",
                    documentId, userId, ms(t2), ms(start));
            return true;
        }

        long t4 = System.nanoTime();
        if (documentPermissionRepository.existsDeptAdminPermission(userId, documentId)
                || collectionPermissionRepository.existsDeptAdminPermissionForDocument(userId, documentId)) {
            log.info("[PERM] canAdmin dept=true doc={} user={} step3={}ms elapsed={}ms",
                    documentId, userId, ms(t3), ms(start));
            return true;
        }

        log.info("[PERM] canAdmin denied doc={} user={} step4={}ms elapsed={}ms",
                documentId, userId, ms(t4), ms(start));
        return false;
    }

    // 컬렉션 쓰기 권한 판단 (소유자 또는 직접 USER 권한)
    public boolean canWriteCollection(Long userId, Long collectionId) {
        DocumentCollection collection = collectionRepository.findById(collectionId)
                .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));
        if (collection.getOwner().getId().equals(userId)) return true;
        return collectionPermissionRepository.existsUserWritePermission(userId, collectionId);
    }

    // 컬렉션 관리 권한 판단 (소유자 또는 직접 USER 권한)
    public boolean canAdminCollection(Long userId, Long collectionId) {
        DocumentCollection collection = collectionRepository.findById(collectionId)
                .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));
        if (collection.getOwner().getId().equals(userId)) return true;
        return collectionPermissionRepository.existsUserAdminPermission(userId, collectionId);
    }

    private double ms(long fromNano) {
        return (System.nanoTime() - fromNano) / 1_000_000.0;
    }
}
