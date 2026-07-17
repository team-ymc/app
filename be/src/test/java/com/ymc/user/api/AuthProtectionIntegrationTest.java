package com.ymc.user.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.support.IntegrationTest;
import com.ymc.user.infra.security.JwtTokenProvider;

class AuthProtectionIntegrationTest extends IntegrationTest {

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("토큰 없이 보호 API 호출 → 401 + 계약 Error 포맷(code=UNAUTHORIZED)")
    void 미인증은_401() throws Exception {
        mockMvc.perform(get("/api/papers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("실제 발급 토큰(Authorization 헤더)이 디코더를 통과한다 — 발급·검증 키 왕복")
    void 실토큰_인증_성공() throws Exception {
        String token = jwtTokenProvider.issueAccessToken(TEST_USER_ID, Instant.now());
        mockMvc.perform(get("/api/papers").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
