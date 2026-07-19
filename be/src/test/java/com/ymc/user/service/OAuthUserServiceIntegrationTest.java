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
import com.ymc.user.domain.User;

class OAuthUserServiceIntegrationTest extends IntegrationTest {

    @Autowired
    private OAuthUserService oAuthUserService;

    @Test
    @DisplayName("신규 provider+providerId → 사용자 생성 (FT-001 Story 2)")
    void 신규면_생성한다() {
        User user = oAuthUserService.upsert(AuthProvider.GOOGLE, "sub-1", "a@b.c", "홍길동");
        assertThat(userRepository.findById(user.getId())).isPresent();
        assertThat(user.getEmail()).isEqualTo("a@b.c");
    }

    @Test
    @DisplayName("같은 provider+providerId 재로그인 → 같은 사용자 (레코드 1개)")
    void 기존이면_같은_사용자를_돌려준다() {
        User first = oAuthUserService.upsert(AuthProvider.GOOGLE, "sub-1", "a@b.c", "홍길동");
        User second = oAuthUserService.upsert(AuthProvider.GOOGLE, "sub-1", "a@b.c", "홍길동");
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 첫 로그인 경쟁 — 패자도 승자의 사용자를 돌려받는다")
    void 동시_가입_경쟁에서도_한_명만_생성된다() throws Exception {
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<User>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return oAuthUserService.upsert(AuthProvider.GOOGLE, "sub-race", "r@b.c", "레이스");
                }));
            }
            ready.await();
            start.countDown();
            User first = results.get(0).get();
            User second = results.get(1).get();
            assertThat(first.getId()).isEqualTo(second.getId());
            assertThat(userRepository.count()).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
    }
}
