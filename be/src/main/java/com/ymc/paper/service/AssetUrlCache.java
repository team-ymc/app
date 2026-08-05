package com.ymc.paper.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.ymc.paper.service.port.FileStorage;
import com.ymc.paper.service.port.PresignedDownload;

import lombok.RequiredArgsConstructor;

/**
 * asset presigned GET URL 발급 + 만료 창 내 재사용 —
 * 같은 asset에 같은 URL을 돌려주면 브라우저·중간 캐시가 산다.
 *
 * <p>재사용은 <b>인스턴스 로컬 best-effort</b>다. 다중 태스크 배포에서는 요청이 다른 인스턴스로
 * 가면 같은 asset이라도 다른 URL이 나갈 수 있다 — 둘 다 유효하므로 정확성 문제는 아니고,
 * 재조회 시 이미지 캐시 미스가 날 뿐이다. 계약도 재사용을 보장이 아니라 best-effort로 둔다.
 * 인스턴스 간 동일 URL이 필요해지면 공유 캐시를 별도로 검토한다.
 *
 * <p>키 수는 평상시 (논문 수 × 이미지 수)로 유계이지만, 개별 축출 없이 상한 초과 시
 * 전체를 비우는 가드만 둔다. 서버 재시작이나 이 비움이 일어나도 무해하다 — 새 URL이 발급될 뿐이다.
 */
@Component
@RequiredArgsConstructor
public class AssetUrlCache {

    /** 만료 임박 URL을 돌려주지 않기 위한 여유. 이 이하로 남으면 재발급한다. */
    private static final Duration REUSE_MARGIN = Duration.ofMinutes(1);

    /** 이 개수를 넘으면 캐시를 통째로 비운다. */
    private static final int MAX_ENTRIES = 10_000;

    private final FileStorage fileStorage;
    private final ConcurrentHashMap<String, PresignedDownload> cache = new ConcurrentHashMap<>();

    public PresignedDownload issue(String s3Key) {
        // 상한 초과 시 통째로 비운다
        if (cache.size() > MAX_ENTRIES) {
            cache.clear();
        }
        // compute로 만료 판정과 재발급을 키 단위 원자 구간에 묶는다 — 동시 요청이 같은 키에
        // 몰려도 한 스레드만 presign하고 나머지는 그 결과를 받는다.
        return cache.compute(s3Key, (key, cached) -> {
            if (cached != null && cached.expiresAt().isAfter(Instant.now().plus(REUSE_MARGIN))) {
                return cached;
            }
            return fileStorage.presignAssetGet(key);
        });
    }
}
