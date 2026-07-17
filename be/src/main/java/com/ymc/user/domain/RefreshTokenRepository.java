package com.ymc.user.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** 재사용 탐지 시 사용자 전체 세션 폐기 (design §5). 쿼리 사다리 ② — @Query(JPQL). */
    @Modifying(clearAutomatically = true)
    @Query("update RefreshToken rt set rt.revokedAt = :now "
            + "where rt.userId = :userId and rt.revokedAt is null")
    int revokeAllActiveByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
