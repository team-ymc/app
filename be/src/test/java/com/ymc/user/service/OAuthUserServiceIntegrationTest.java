package com.ymc.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.support.IntegrationTest;
import com.ymc.user.domain.AuthProvider;

class OAuthUserServiceIntegrationTest extends IntegrationTest {

    @Autowired
    private OAuthUserService oAuthUserService;

    @Test
    @DisplayName("신규 provider+providerId → 사용자 생성 (FT-001 Story 2)")
    void 신규면_생성한다() {
        OAuthUserService.UpsertResult result =
                oAuthUserService.upsert(AuthProvider.GOOGLE, "sub-1", "a@b.c", "홍길동");
        assertThat(userRepository.findById(result.user().getId())).isPresent();
        assertThat(result.user().getEmail()).isEqualTo("a@b.c");
        assertThat(result.created()).isTrue();
    }

    @Test
    @DisplayName("같은 provider+providerId 재로그인 → 같은 사용자 (레코드 1개)")
    void 기존이면_같은_사용자를_돌려준다() {
        long before = userRepository.count();
        OAuthUserService.UpsertResult first =
                oAuthUserService.upsert(AuthProvider.GOOGLE, "sub-1", "a@b.c", "홍길동");
        OAuthUserService.UpsertResult second =
                oAuthUserService.upsert(AuthProvider.GOOGLE, "sub-1", "a@b.c", "홍길동");
        assertThat(second.user().getId()).isEqualTo(first.user().getId());
        assertThat(second.created()).isFalse();
        assertThat(userRepository.count()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("동시 첫 로그인 경쟁 — 패자도 승자의 사용자를 돌려받는다")
    void 동시_가입_경쟁에서도_한_명만_생성된다() throws Exception {
        long before = userRepository.count();
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<OAuthUserService.UpsertResult>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return oAuthUserService.upsert(AuthProvider.GOOGLE, "sub-race", "r@b.c", "레이스");
                }));
            }
            ready.await();
            start.countDown();
            OAuthUserService.UpsertResult first = results.get(0).get();
            OAuthUserService.UpsertResult second = results.get(1).get();
            assertThat(first.user().getId()).isEqualTo(second.user().getId());
            // 가입 신호는 실제로 행을 만든 쪽 하나에만 나간다
            assertThat(first.created() ^ second.created()).isTrue();
            assertThat(userRepository.count()).isEqualTo(before + 1);
        } finally {
            pool.shutdown();
        }
    }
}
