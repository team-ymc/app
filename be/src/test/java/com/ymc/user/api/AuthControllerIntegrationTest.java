package com.ymc.user.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import com.ymc.support.IntegrationTest;
import com.ymc.user.domain.AuthProvider;
import com.ymc.user.domain.User;
import com.ymc.user.service.AuthService;

import jakarta.servlet.http.Cookie;

class AuthControllerIntegrationTest extends IntegrationTest {

    @Autowired
    private AuthService authService;

    private User user;

    @BeforeEach
    void seedUser() {
        user = userRepository.save(
                User.register(AuthProvider.GOOGLE, "sub-ctrl", "a@b.c", "홍길동", Instant.now()));
    }

    private Cookie refreshCookie() {
        return new Cookie("ymc_refresh", authService.issueTokens(user.getId()).rawRefreshToken());
    }

    @Test
    @DisplayName("refresh: 쿠키 없음 → 401 AUTH_REFRESH_INVALID")
    void 쿠키_없으면_401() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_INVALID"));
    }

    @Test
    @DisplayName("무효 토큰으로 refresh → 401과 함께 죽은 쿠키를 지운다")
    void 무효_쿠키는_401과_함께_삭제된다() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("ymc_refresh", "unknown-token")))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(result.getResponse().getHeader("Set-Cookie"))
                .contains("ymc_refresh=").contains("Max-Age=0");
    }

    @Test
    @DisplayName("refresh 성공: access+user 반환, 새 refresh 쿠키(HttpOnly) 세팅")
    void 리프레시_성공() throws Exception {
        Cookie cookie = refreshCookie();
        MvcResult result = mockMvc.perform(post("/api/auth/refresh").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(1800))
                .andExpect(jsonPath("$.user.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.user.email").value("a@b.c"))
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("ymc_refresh=").contains("HttpOnly").contains("SameSite=Lax");
        assertThat(setCookie).doesNotContain("ymc_refresh=" + cookie.getValue()); // 회전됨
    }

    @Test
    @DisplayName("회전 후 이전 쿠키 재사용 → 401")
    void 이전_쿠키는_무효() throws Exception {
        Cookie cookie = refreshCookie();
        mockMvc.perform(post("/api/auth/refresh").cookie(cookie)).andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/refresh").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logout: 204 + 쿠키 삭제, 이후 refresh 401. 쿠키 없이도 204")
    void 로그아웃() throws Exception {
        Cookie cookie = refreshCookie();
        MvcResult result = mockMvc.perform(post("/api/auth/logout").cookie(cookie))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("Max-Age=0");

        mockMvc.perform(post("/api/auth/refresh").cookie(cookie)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("me: 유효 access → 사용자 정보 / 무토큰 → 401")
    void 미_엔드포인트() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .with(jwt().jwt(j -> j.subject(user.getId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.displayName").value("홍길동"));

        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }
}
