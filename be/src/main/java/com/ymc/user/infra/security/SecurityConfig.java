package com.ymc.user.infra.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import com.ymc.common.config.AuthProperties;

import lombok.RequiredArgsConstructor;

/**
 * /api/** 는 bearer access JWT 필수. 예외는 refresh·logout(인증 수단이 쿠키 자체)뿐이다.
 * /api 밖(actuator 등)은 체인 미적용 — vite proxy가 /api만 전달하므로 노출면이 아니다.
 * CSRF는 끈다 — 쿠키 인증 POST(refresh/logout)는 SameSite=Lax가 크로스사이트 전송을 차단한다 (FT-001).
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ApiAuthenticationEntryPoint entryPoint;

    /**
     * OAuth 로그인 전용 체인 — /api prefix를 써서 vite proxy(/api → :8080)가 그대로 커버한다.
     * 핸드셰이크 동안만 세션을 쓴다(state 저장). API 인증은 apiChain이 무세션으로 처리.
     */
    @Bean
    @Order(1)
    SecurityFilterChain oauthLoginChain(HttpSecurity http,
            OAuthLoginSuccessHandler successHandler,
            OAuthLoginFailureHandler failureHandler) throws Exception {
        http
                .securityMatcher("/api/oauth2/**", "/api/login/oauth2/**")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(a -> a.baseUri("/api/oauth2/authorization"))
                        .redirectionEndpoint(r -> r.baseUri("/api/login/oauth2/code/*"))
                        .successHandler(successHandler)
                        .failureHandler(failureHandler));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        http
                .securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/refresh", "/api/auth/logout").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs
                        .authenticationEntryPoint(entryPoint)
                        .jwt(jwt -> jwt.decoder(jwtDecoder)));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(AuthProperties props) {
        SecretKey key = new SecretKeySpec(
                props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).build();
    }
}
