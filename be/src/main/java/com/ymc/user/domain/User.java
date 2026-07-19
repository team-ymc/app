package com.ymc.user.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;

/**
 * 소셜 인증 사용자. provider + providerId가 식별자다 — 같은 사람이 다른 provider로
 * 로그인하면 별개 계정이다 (FT-001 Story 2, 계정 병합 없음).
 */
@Getter
@Entity
@Table(
        name = "users",   // user는 PostgreSQL 예약어
        uniqueConstraints = @UniqueConstraint(
                name = "uk_users_provider_provider_id",
                columnNames = {"provider", "provider_id"}))
public class User {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false, length = 32)
    private AuthProvider provider;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private String providerId;

    /** provider가 이메일을 주지 않을 수 있다 (계약 AuthUser.email nullable). */
    @Column(name = "email")
    private String email;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected User() {
        // JPA
    }

    private User(UUID id, AuthProvider provider, String providerId,
            String email, String displayName, Instant now) {
        this.id = id;
        this.provider = provider;
        this.providerId = providerId;
        this.email = email;
        this.displayName = displayName;
        this.createdAt = now;
    }

    public static User register(AuthProvider provider, String providerId,
            String email, String displayName, Instant now) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(now, "now");
        return new User(UUID.randomUUID(), provider, providerId, email, displayName, now);
    }
}
