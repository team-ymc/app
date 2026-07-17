package com.ymc.user.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Id;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;

/**
 * refresh token — 원문은 저장하지 않고 SHA-256 해시만 둔다. 회전 시 이전 행을 revoke하고
 * 새 행을 만든다. revoked 행과의 대조가 재사용 탐지의 근거다 (design §5).
 */
@Getter
@Entity
@Table(
        name = "refresh_token",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_refresh_token_hash", columnNames = "token_hash"))
public class RefreshToken {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 컨텍스트 내부지만 연관관계 없이 ID만 둔다 — 조회 경로가 tokenHash 단건뿐이다. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {
        // JPA
    }

    private RefreshToken(UUID id, UUID userId, String tokenHash, Instant now, Duration ttl) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = now.plus(ttl);
        this.createdAt = now;
    }

    public static RefreshToken issue(UUID userId, String tokenHash, Instant now, Duration ttl) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(tokenHash, "tokenHash");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(ttl, "ttl");
        return new RefreshToken(UUID.randomUUID(), userId, tokenHash, now, ttl);
    }

    /** 멱등 — 최초 폐기 시각을 유지한다 (재사용 탐지 로그의 기준 시각). */
    public void revoke(Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now;
        }
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
