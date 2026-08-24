package com.opensource.docgrid.domain.user.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.opensource.docgrid.domain.user.entity.McpAccessToken;

public interface McpAccessTokenRepository extends JpaRepository<McpAccessToken, Long> {

    /** API 키 인증에 쓰인다 — 해시값으로 폐기되지 않은 유효한 토큰을 찾는다. */
    Optional<McpAccessToken> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    /** 웹 토큰 관리 화면에 쓰인다 — 사용자의 토큰 목록을 최신순으로 조회한다. */
    List<McpAccessToken> findAllByUser_IdOrderByCreatedAtDesc(Long userId);
}