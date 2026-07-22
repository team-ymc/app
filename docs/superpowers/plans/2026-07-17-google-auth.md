# Google 소셜 인증 (FE+BE) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Google 계정으로 로그인/회원가입 → JWT access(30분) + HttpOnly refresh 쿠키(14일, 회전) 세션 → papers API 인증 필수화까지, FE(Vite+React)·BE(Spring Boot) 양쪽을 완성한다.

**Architecture:** BE 주도 OAuth(Spring Security `oauth2Login`, 팝업 창에서 진행) → 성공 핸들러가 사용자 upsert 후 refresh 쿠키를 심고 FE 브릿지 페이지로 302 → 브릿지가 본창에 postMessage → FE가 `POST /api/auth/refresh`로 access token을 메모리에 수령. API 보호는 `oauth2ResourceServer(jwt)`(HMAC 대칭키). 상세 설계: `docs/superpowers/specs/2026-07-17-google-auth-design.md`.

**Tech Stack:** Spring Boot 3.5.15 / Java 21 / spring-boot-starter-oauth2-client·oauth2-resource-server / PostgreSQL(ddl-auto: update) / Vite 6 + React 18(JS) / Vitest

**Source:** project-docs/features/FT-001-소셜-인증.md / Jira: YMC-211(에픽) — YMC-213(BE 인증), YMC-215(BE 업로드 연동), YMC-214(FE UI)

## Global Constraints

- 계약 SSOT는 `project-docs/contracts/openapi.yaml` — **계약 PR(Task 1)이 코드보다 먼저.** ErrorCode enum 추가도 계약부터.
- 만료: access **30m** / refresh **14d**. 쿠키 이름 **`ymc_refresh`** (`HttpOnly; SameSite=Lax; Path=/api/auth`, Secure는 설정값).
- Google scope: **`openid email profile`**. redirect URI: **`http://localhost:5173/api/login/oauth2/code/google`** (YMC-227에서 콘솔 등록 완료).
- be/CLAUDE.md 준수: DI는 `@RequiredArgsConstructor`+`final`, 엔티티는 `@Getter`만, 컨텍스트 간 참조는 ID로, OSIV off.
- 커밋 prefix: BE 인증 `[YMC-213]`, papers 연동 `[YMC-215]`, FE `[YMC-214]`, 계약 `[YMC-213]`. **Co-Authored-By/🤖 트레일러 금지** (팀 규칙).
- FE는 TypeScript 전환·라우터 도입 없음. S3 presigned PUT(`uploadToS3`)에는 Authorization을 붙이지 않는다 (서명 URL 자체가 인가).
- BE 테스트는 Testcontainers 통합 테스트(Docker 필요). 전체 스위트: `cd be && ./gradlew test`.
- Spring Security 6.5 API(oauth2Login baseUri 커스터마이즈, `NimbusJwtDecoder.withSecretKey`, `NimbusJwtEncoder`+`ImmutableSecret`)는 공식 레퍼런스로 검증된 사용법이다 — 임의 변형 금지.

## 브랜치 전략 (스택)

| Phase | repo | 브랜치 | 분기점 | Tasks |
|---|---|---|---|---|
| A 계약 | project-docs | `YMC-213-auth-contract` | `origin/YMC-223-paper-download-list` (0.1.1 미머지 시) 또는 main | 1 |
| B BE 인증 | app | `YMC-213-social-auth` | main | 2–7 |
| C papers 연동 | app | `YMC-215-upload-auth` | `YMC-213-social-auth` | 8 |
| D FE | app | `YMC-214-login-ui` | `YMC-215-upload-auth` | 9–12 |
| E 검증·PR | — | — | — | 13 |

---

### Task 1: 계약 — openapi.yaml 0.1.2 (project-docs)

**Files:**
- Modify: `project-docs/contracts/openapi.yaml`

**Interfaces:**
- Produces: `/api/auth/refresh`·`/api/auth/logout`·`/api/auth/me` 스키마, `bearerAuth` scheme, Error enum `UNAUTHORIZED`·`AUTH_REFRESH_INVALID` — Task 2·6의 구현 기준.

- [ ] **Step 1: 분기점 확인 후 브랜치 생성**

```bash
cd "/Users/geunhh/Library/Mobile Documents/com~apple~CloudDocs/Desktop/team-ymc/project-docs"
git fetch --prune
# YMC-223 PR이 main에 머지됐는지 확인
git log --oneline origin/main -1
# 머지됐으면: git checkout -b YMC-213-auth-contract origin/main
# 아직이면(0.1.1이 YMC-223 브랜치에만 있음):
git checkout -b YMC-213-auth-contract origin/YMC-223-paper-download-list
```

- [ ] **Step 2: openapi.yaml 수정**

(1) `version: 0.1.1` → `version: 0.1.2`

(2) 최상위 `security: []` 블록(및 그 위 "Sprint 01은 인증 없음" 주석)을 다음으로 교체:

```yaml
# FT-001: bearer access token(JWT) + HttpOnly refresh 쿠키(ymc_refresh, BE 내부 구현).
# 로그인 시작·콜백은 Spring Security 소유의 브라우저 네비게이션 경로라 이 문서에 스키마를 두지 않는다:
#   GET /api/oauth2/authorization/google  → Google 동의 화면으로 리다이렉트
#   GET /api/login/oauth2/code/google     → 콜백. 성공: {FE_ORIGIN}/auth/popup-done.html로 302
#                                            실패: {FE_ORIGIN}/auth/popup-done.html?error=oauth_failed
security:
  - bearerAuth: []
```

(3) `paths:` 에 auth 3종 추가 (papers 위쪽에 삽입):

```yaml
  /api/auth/refresh:
    post:
      operationId: refreshSession
      summary: refresh 쿠키로 access token 발급·회전
      description: |
        ymc_refresh 쿠키의 토큰을 검증해 access token을 발급하고, refresh 토큰을 회전한다
        (이전 토큰 폐기 + 새 쿠키 Set-Cookie). FE는 앱 부트스트랩과 401 재시도 시 호출한다.
        폐기된 토큰의 재사용이 감지되면 해당 사용자의 모든 세션을 폐기한다 (응답은 동일한 401).
      tags: [auth]
      security: []   # 인증 수단이 쿠키 자체다
      responses:
        "200":
          description: 새 access token + 회전된 refresh 쿠키(Set-Cookie)
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/TokenResponse"
        "401":
          description: "쿠키 없음·만료·폐기·재사용. code: AUTH_REFRESH_INVALID"
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/Error"

  /api/auth/logout:
    post:
      operationId: logout
      summary: 세션 종료 (refresh 무효화 + 쿠키 삭제)
      description: |
        refresh token을 즉시 폐기하고 쿠키를 지운다. 멱등 — 쿠키가 없어도 204.
        주의: 이미 발급된 access token은 회수 불가라 만료(최대 30분)까지 유효하다.
      tags: [auth]
      security: []
      responses:
        "204":
          description: 세션 종료됨 (쿠키 삭제 Set-Cookie 포함)

  /api/auth/me:
    get:
      operationId: getMe
      summary: 현재 사용자 조회
      tags: [auth]
      responses:
        "200":
          description: 인증된 사용자
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/AuthUser"
        "401":
          description: "access token 없음·만료. code: UNAUTHORIZED"
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/Error"
```

(4) `components:` 에 securitySchemes 추가 + 스키마 2개 추가:

```yaml
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT
      description: POST /api/auth/refresh가 발급한 access token (만료 30분)
```

```yaml
    TokenResponse:
      type: object
      required: [accessToken, expiresIn, user]
      properties:
        accessToken:
          type: string
          description: Bearer access token (JWT). FE는 메모리에만 보관한다 — 스토리지 저장 금지.
        expiresIn:
          type: integer
          description: access token 만료까지 남은 초 (발급 시점 기준, 1800)
        user:
          $ref: "#/components/schemas/AuthUser"

    AuthUser:
      type: object
      required: [userId, displayName]
      properties:
        userId:
          type: string
          format: uuid
        email:
          type: string
          nullable: true
          description: provider가 이메일을 주지 않으면 null
        displayName:
          type: string
```

(5) 기존 papers 4개 오퍼레이션(list·create·complete·status·download — 0.1.1 기준 5개)에 각각 401 응답 추가:

```yaml
        "401":
          description: "인증 필요. code: UNAUTHORIZED"
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/Error"
```

(6) `Error.code` enum과 목록 주석에 2개 추가:

```yaml
            - UNAUTHORIZED          : access token 없음·만료 (401)
            - AUTH_REFRESH_INVALID  : refresh 쿠키 없음·만료·폐기·재사용 (401)
```
enum 배열에 `UNAUTHORIZED`, `AUTH_REFRESH_INVALID` 추가.

(7) `PaperCreated.fileKey`의 description·example을 새 키 형식으로 갱신 (YMC-215, ADR-002):

```yaml
          example: uploads/00000000-0000-0000-0000-000000000001/550e8400-e29b-41d4-a716-446655440000.pdf
```

- [ ] **Step 3: YAML 문법 검증**

```bash
python3 -c "import yaml, io; yaml.safe_load(io.open('contracts/openapi.yaml', encoding='utf-8')); print('OK')"
```
Expected: `OK`

- [ ] **Step 4: 커밋 + PR**

```bash
git add contracts/openapi.yaml
git commit -m "[YMC-213] docs(contracts): 인증 스펙 추가 — auth 3종·bearerAuth·401 (0.1.2)"
git push -u origin YMC-213-auth-contract
gh pr create --title "[YMC-213] contracts: 인증 스펙 (openapi 0.1.2)" --body "FT-001 인증 계약. auth 3종 + bearerAuth + papers 401 + Error 코드 2종 + fileKey 형식(ADR-002). Source: features/FT-001-소셜-인증.md"
```
(PR 본문에 Claude 트레일러를 넣지 않는다.)

---

### Task 2: BE 보안 백본 — 의존성·JWT·SecurityConfig·기존 테스트 그린 유지

**Files:**
- Modify: `be/build.gradle`
- Modify: `be/src/main/resources/application.yml`
- Create: `be/src/main/java/com/ymc/common/config/AuthProperties.java`
- Modify: `be/src/main/java/com/ymc/common/config/AppConfig.java`
- Modify: `be/src/main/java/com/ymc/common/error/ErrorCode.java`
- Create: `be/src/main/java/com/ymc/user/infra/security/JwtTokenProvider.java`
- Create: `be/src/main/java/com/ymc/user/infra/security/ApiAuthenticationEntryPoint.java`
- Create: `be/src/main/java/com/ymc/user/infra/security/SecurityConfig.java`
- Test: `be/src/test/java/com/ymc/user/infra/security/JwtTokenProviderTest.java`, `be/src/test/java/com/ymc/user/api/AuthProtectionIntegrationTest.java`
- Modify(테스트 인증): `be/src/test/java/com/ymc/support/IntegrationTest.java` + `be/src/test/java/com/ymc/paper/` 아래 MockMvc 사용 테스트 6개 파일

**Interfaces:**
- Consumes: Task 1의 계약 (UNAUTHORIZED·AUTH_REFRESH_INVALID)
- Produces: `JwtTokenProvider.issueAccessToken(UUID userId, Instant now): String`, `JwtTokenProvider.accessTtlSeconds(): long`, `AuthProperties(String jwtSecret, Duration accessTtl, Duration refreshTtl, String feOrigin, boolean cookieSecure)`, `IntegrationTest.TEST_USER_ID`/`userJwt()`, SecurityConfig의 `/api/**` 보호(+`/api/auth/refresh`·`/api/auth/logout` permitAll)

- [ ] **Step 1: 브랜치 생성**

```bash
cd "/Users/geunhh/Library/Mobile Documents/com~apple~CloudDocs/Desktop/team-ymc/app"
git checkout main && git pull --ff-only
git checkout -b YMC-213-social-auth
```

- [ ] **Step 2: 의존성 추가** — `be/build.gradle`의 dependencies에 추가:

```groovy
	// FT-001 인증 — BE 주도 Google 로그인(oauth2Login) + 자체 발급 JWT 검증(resource server)
	implementation 'org.springframework.boot:spring-boot-starter-oauth2-client'
	implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'
```
testImplementation 블록에 추가:
```groovy
	// MockMvc에 JWT principal 주입 (SecurityMockMvcRequestPostProcessors.jwt)
	testImplementation 'org.springframework.security:spring-security-test'
```

- [ ] **Step 3: 설정 추가** — `application.yml` 공통 블록의 `app:` 아래에 추가:

```yaml
# FT-001 인증. 만료값은 UX 파라미터 — 설계 §2 (30m/14d).
auth:
  jwt-secret: ${AUTH_JWT_SECRET:local-dev-only-secret-key-32bytes-min!!}   # HS256, 32바이트 이상
  access-ttl: 30m
  refresh-ttl: 14d
  fe-origin: http://localhost:5173
  cookie-secure: false
```
prod 블록에 override 추가:
```yaml
auth:
  jwt-secret: ${AUTH_JWT_SECRET}    # prod는 기본값 없음 — 미설정 시 기동 실패가 맞다
  fe-origin: ${FE_ORIGIN}
  cookie-secure: true
```

- [ ] **Step 4: AuthProperties + AppConfig 등록**

`be/src/main/java/com/ymc/common/config/AuthProperties.java`:
```java
package com.ymc.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml의 auth.* 바인딩 (FT-001). 만료값 근거는 design 2026-07-17 §2. */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        String jwtSecret,
        Duration accessTtl,
        Duration refreshTtl,
        String feOrigin,
        boolean cookieSecure) {
}
```
`AppConfig.java`의 어노테이션 수정:
```java
@EnableConfigurationProperties({AppProperties.class, AuthProperties.class})
```

- [ ] **Step 5: ErrorCode 추가** — `ErrorCode.java` enum에 추가 (계약 0.1.2 반영):

```java
    /** access token 없음·만료 (FT-001) */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),

    /** refresh 쿠키 없음·만료·폐기·재사용 (FT-001) */
    AUTH_REFRESH_INVALID(HttpStatus.UNAUTHORIZED);
```
(기존 마지막 항목 `UPLOAD_NOT_FOUND(...)` 뒤 `;`를 `,`로 바꾸고 이어붙인다.)

- [ ] **Step 6: JwtTokenProvider 실패 테스트 작성**

`be/src/test/java/com/ymc/user/infra/security/JwtTokenProviderTest.java`:
```java
package com.ymc.user.infra.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.ymc.common.config.AuthProperties;

class JwtTokenProviderTest {

    private final AuthProperties props = new AuthProperties(
            "test-secret-key-that-is-32-bytes-long!!",
            Duration.ofMinutes(30), Duration.ofDays(14), "http://localhost:5173", false);

    private final JwtTokenProvider provider = new JwtTokenProvider(props);

    @Test
    void 발급한_토큰은_같은_시크릿의_디코더로_검증되고_sub와_만료가_맞다() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        String token = provider.issueAccessToken(userId, now);

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(
                new SecretKeySpec(props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256")).build();
        Jwt jwt = decoder.decode(token);
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getExpiresAt()).isEqualTo(now.plus(Duration.ofMinutes(30)));
    }

    @Test
    void accessTtlSeconds는_설정값을_초로_돌려준다() {
        assertThat(provider.accessTtlSeconds()).isEqualTo(1800L);
    }
}
```

- [ ] **Step 7: 실패 확인**

```bash
cd be && ./gradlew test --tests 'com.ymc.user.infra.security.JwtTokenProviderTest'
```
Expected: 컴파일 실패 — `JwtTokenProvider` 미존재.

- [ ] **Step 8: JwtTokenProvider 구현**

`be/src/main/java/com/ymc/user/infra/security/JwtTokenProvider.java`:
```java
package com.ymc.user.infra.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.ymc.common.config.AuthProperties;

/**
 * access JWT 발급 (HS256 대칭키). 검증은 resource server의 {@code JwtDecoder}가 같은 키로 한다.
 * access는 발급 후 회수 불가 — 만료가 곧 폐기다 (design §3).
 */
@Component
public class JwtTokenProvider {

    private final JwtEncoder encoder;
    private final Duration accessTtl;

    public JwtTokenProvider(AuthProperties props) {
        SecretKey key = new SecretKeySpec(
                props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.accessTtl = props.accessTtl();
    }

    public String issueAccessToken(UUID userId, Instant now) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(accessTtl))
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long accessTtlSeconds() {
        return accessTtl.toSeconds();
    }
}
```

- [ ] **Step 9: 통과 확인** — Step 7 명령 재실행. Expected: PASS

- [ ] **Step 10: EntryPoint + SecurityConfig 구현**

`be/src/main/java/com/ymc/user/infra/security/ApiAuthenticationEntryPoint.java`:
```java
package com.ymc.user.infra.security;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.common.error.ErrorCode;
import com.ymc.common.error.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** 미인증 401을 계약의 Error 스키마(JSON)로 — resource server 기본(헤더만)을 대체한다. */
@Component
@RequiredArgsConstructor
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ErrorResponse.of(ErrorCode.UNAUTHORIZED, "인증이 필요합니다."));
    }
}
```

`be/src/main/java/com/ymc/user/infra/security/SecurityConfig.java`:
```java
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
 * CSRF는 끈다 — 쿠키 인증 POST(refresh/logout)는 SameSite=Lax가 크로스사이트 전송을 차단한다 (design §5).
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ApiAuthenticationEntryPoint entryPoint;

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
```

- [ ] **Step 11: 테스트 베이스에 인증 헬퍼 추가** — `IntegrationTest.java`에 필드·메서드 추가:

```java
    /** 기존 fixed-owner-id와 같은 값 — Task 8 전까지 데이터 소유자와 JWT 주체가 일치하게 한다. */
    protected static final UUID TEST_USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** MockMvc 요청에 인증 principal 주입. 디코더를 거치지 않는 테스트 전용 JWT다. */
    protected org.springframework.test.web.servlet.request.RequestPostProcessor userJwt() {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .jwt().jwt(j -> j.subject(TEST_USER_ID.toString()));
    }
```
(import 정리는 IDE 규약대로 상단 import 문으로 올린다.)

- [ ] **Step 12: 기존 MockMvc 테스트 전부에 인증 부착** — 다음 6개 파일의 모든 `mockMvc.perform(...)` 요청 빌더에 `.with(userJwt())` 추가:

`PaperRegistrationIntegrationTest`, `PaperUploadCompletionIntegrationTest`, `PaperStatusPollingIntegrationTest`, `PaperDownloadIntegrationTest`, `PaperListIntegrationTest`, `PaperFlowE2ETest`

변경 예 (형태는 전부 동일):
```java
// before
mockMvc.perform(post("/api/papers").contentType(MediaType.APPLICATION_JSON).content(body))
// after
mockMvc.perform(post("/api/papers").with(userJwt())
        .contentType(MediaType.APPLICATION_JSON).content(body))
```
누락 검증:
```bash
cd be && grep -rn "mockMvc.perform" src/test/java/com/ymc/paper | grep -v "userJwt" ; echo "exit=$?"
```
Expected: 출력 없음, `exit=1`

- [ ] **Step 13: 보호 동작 통합 테스트 작성**

`be/src/test/java/com/ymc/user/api/AuthProtectionIntegrationTest.java`:
```java
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
```

- [ ] **Step 14: 전체 스위트 실행**

```bash
cd be && ./gradlew test
```
Expected: 전부 PASS (Docker 필요)

- [ ] **Step 15: 커밋**

```bash
git add be/
git commit -m "[YMC-213] feat(auth): 보안 백본 — JWT 발급·검증과 /api 보호, 미인증 401 계약 포맷"
```

---

### Task 3: User·RefreshToken 도메인 + 리포지토리

**Files:**
- Create: `be/src/main/java/com/ymc/user/domain/AuthProvider.java`, `User.java`, `RefreshToken.java`, `UserRepository.java`, `RefreshTokenRepository.java`
- Test: `be/src/test/java/com/ymc/user/domain/RefreshTokenTest.java`, `UserTest.java`

**Interfaces:**
- Produces: `User.register(AuthProvider, String providerId, String email, String displayName, Instant now): User`, `RefreshToken.issue(UUID userId, String tokenHash, Instant now, Duration ttl)`, `revoke(Instant)`, `isRevoked()`, `isExpired(Instant)`, `UserRepository.findByProviderAndProviderId(AuthProvider, String)`, `RefreshTokenRepository.findByTokenHash(String)`, `revokeAllActiveByUserId(UUID, Instant): int`

- [ ] **Step 1: 도메인 실패 테스트 작성**

`be/src/test/java/com/ymc/user/domain/RefreshTokenTest.java`:
```java
package com.ymc.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RefreshTokenTest {

    private final Instant now = Instant.parse("2026-07-17T00:00:00Z");

    @Test
    void issue는_만료를_now_plus_ttl로_계산하고_미폐기_상태다() {
        RefreshToken token = RefreshToken.issue(UUID.randomUUID(), "hash", now, Duration.ofDays(14));
        assertThat(token.getExpiresAt()).isEqualTo(now.plus(Duration.ofDays(14)));
        assertThat(token.isRevoked()).isFalse();
        assertThat(token.isExpired(now)).isFalse();
    }

    @Test
    void revoke_후_isRevoked_true_재호출은_최초_시각_유지() {
        RefreshToken token = RefreshToken.issue(UUID.randomUUID(), "hash", now, Duration.ofDays(14));
        token.revoke(now.plusSeconds(60));
        token.revoke(now.plusSeconds(120));
        assertThat(token.isRevoked()).isTrue();
        assertThat(token.getRevokedAt()).isEqualTo(now.plusSeconds(60));
    }

    @Test
    void 만료_경계_지나면_isExpired_true() {
        RefreshToken token = RefreshToken.issue(UUID.randomUUID(), "hash", now, Duration.ofDays(14));
        assertThat(token.isExpired(now.plus(Duration.ofDays(14)).plusSeconds(1))).isTrue();
    }
}
```

`be/src/test/java/com/ymc/user/domain/UserTest.java`:
```java
package com.ymc.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class UserTest {

    @Test
    void register는_id를_생성하고_필드를_채운다() {
        User user = User.register(AuthProvider.GOOGLE, "sub-1", "a@b.c", "홍길동", Instant.now());
        assertThat(user.getId()).isNotNull();
        assertThat(user.getProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(user.getProviderId()).isEqualTo("sub-1");
    }

    @Test
    void email은_null_허용_providerId는_필수() {
        assertThat(User.register(AuthProvider.GOOGLE, "sub-1", null, "이름", Instant.now())
                .getEmail()).isNull();
        assertThatNullPointerException()
                .isThrownBy(() -> User.register(AuthProvider.GOOGLE, null, null, "이름", Instant.now()));
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
cd be && ./gradlew test --tests 'com.ymc.user.domain.*'
```
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`AuthProvider.java`:
```java
package com.ymc.user.domain;

/** 소셜 provider. MVP는 Google만 — Kakao·Naver는 post-MVP (FT-001). */
public enum AuthProvider {
    GOOGLE
}
```

`User.java`:
```java
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
```

`RefreshToken.java`:
```java
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
```

`UserRepository.java`:
```java
package com.ymc.user.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);
}
```

`RefreshTokenRepository.java`:
```java
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
```

- [ ] **Step 4: 통과 확인** — Step 2 명령 재실행. Expected: PASS

- [ ] **Step 5: 전체 스위트 + 커밋**

```bash
cd be && ./gradlew test
git add be/ && git commit -m "[YMC-213] feat(user): User·RefreshToken 도메인과 리포지토리"
```

---

### Task 4: OAuthUserService — provider+providerId upsert

**Files:**
- Create: `be/src/main/java/com/ymc/user/service/OAuthUserService.java`
- Modify: `be/src/test/java/com/ymc/support/IntegrationTest.java` (user 리포지토리 정리 추가)
- Test: `be/src/test/java/com/ymc/user/service/OAuthUserServiceIntegrationTest.java`

**Interfaces:**
- Consumes: Task 3의 `User`, `UserRepository`
- Produces: `OAuthUserService.upsert(AuthProvider, String providerId, String email, String displayName): User`

- [ ] **Step 1: IntegrationTest 베이스 정리 확장** — 필드 추가 및 `resetState()` 첫 줄에 정리 추가:

```java
    @Autowired
    protected com.ymc.user.domain.UserRepository userRepository;

    @Autowired
    protected com.ymc.user.domain.RefreshTokenRepository refreshTokenRepository;
```
`resetState()` 맨 앞에:
```java
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
```

- [ ] **Step 2: 실패 테스트 작성**

`be/src/test/java/com/ymc/user/service/OAuthUserServiceIntegrationTest.java`:
```java
package com.ymc.user.service;

import static org.assertj.core.api.Assertions.assertThat;

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
}
```

- [ ] **Step 3: 실패 확인**

```bash
cd be && ./gradlew test --tests 'com.ymc.user.service.OAuthUserServiceIntegrationTest'
```
Expected: 컴파일 실패

- [ ] **Step 4: 구현**

`be/src/main/java/com/ymc/user/service/OAuthUserService.java`:
```java
package com.ymc.user.service;

import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.user.domain.AuthProvider;
import com.ymc.user.domain.User;
import com.ymc.user.domain.UserRepository;

import lombok.RequiredArgsConstructor;

/** OAuth 성공 시 사용자 식별·생성. provider+providerId가 키다 (FT-001 Story 2). */
@Service
@RequiredArgsConstructor
public class OAuthUserService {

    private final UserRepository userRepository;

    @Transactional
    public User upsert(AuthProvider provider, String providerId, String email, String displayName) {
        return userRepository.findByProviderAndProviderId(provider, providerId)
                .orElseGet(() -> saveNew(provider, providerId, email, displayName));
    }

    private User saveNew(AuthProvider provider, String providerId, String email, String displayName) {
        try {
            return userRepository.saveAndFlush(
                    User.register(provider, providerId, email, displayName, Instant.now()));
        } catch (DataIntegrityViolationException e) {
            // 동시 첫 로그인 경쟁 — 유니크 제약을 이긴 쪽을 다시 읽는다 (PaperRegistrationService 패턴)
            return userRepository.findByProviderAndProviderId(provider, providerId)
                    .orElseThrow(() -> e);
        }
    }
}
```

- [ ] **Step 5: 통과 확인 → 전체 스위트 → 커밋**

```bash
cd be && ./gradlew test --tests 'com.ymc.user.service.OAuthUserServiceIntegrationTest' && ./gradlew test
git add be/ && git commit -m "[YMC-213] feat(user): OAuth 사용자 upsert — provider+providerId 식별"
```

---

### Task 5: AuthService — 발급·회전·재사용 탐지·로그아웃 + RefreshTokenCookie

**Files:**
- Create: `be/src/main/java/com/ymc/user/infra/security/TokenHasher.java`, `RefreshTokenCookie.java`
- Create: `be/src/main/java/com/ymc/user/service/AuthService.java`
- Test: `be/src/test/java/com/ymc/user/service/AuthServiceIntegrationTest.java`

**Interfaces:**
- Consumes: Task 2 `JwtTokenProvider`·`AuthProperties`, Task 3 도메인
- Produces:
  - `AuthService.IssuedTokens(String accessToken, long expiresInSeconds, String rawRefreshToken)`
  - `AuthService.RefreshResult(IssuedTokens tokens, User user)`
  - `issueTokens(UUID userId): IssuedTokens` / `refresh(String raw): RefreshResult` / `logout(String rawOrNull): void` / `getUser(UUID): User`
  - `TokenHasher.sha256(String): String`
  - `RefreshTokenCookie.NAME = "ymc_refresh"`, `issue(String raw): ResponseCookie`, `expire(): ResponseCookie`

- [ ] **Step 1: 실패 테스트 작성**

`be/src/test/java/com/ymc/user/service/AuthServiceIntegrationTest.java`:
```java
package com.ymc.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.support.IntegrationTest;
import com.ymc.user.domain.AuthProvider;
import com.ymc.user.domain.RefreshToken;
import com.ymc.user.domain.User;
import com.ymc.user.infra.security.TokenHasher;
import com.ymc.user.service.AuthService.IssuedTokens;
import com.ymc.user.service.AuthService.RefreshResult;

class AuthServiceIntegrationTest extends IntegrationTest {

    @Autowired
    private AuthService authService;

    private UUID userId;

    @BeforeEach
    void seedUser() {
        User user = userRepository.save(
                User.register(AuthProvider.GOOGLE, "sub-auth", "a@b.c", "홍길동", Instant.now()));
        userId = user.getId();
    }

    @Test
    @DisplayName("refresh 성공: 새 토큰 발급 + 이전 토큰은 폐기(회전)")
    void 회전한다() {
        IssuedTokens issued = authService.issueTokens(userId);

        RefreshResult result = authService.refresh(issued.rawRefreshToken());

        assertThat(result.tokens().rawRefreshToken()).isNotEqualTo(issued.rawRefreshToken());
        assertThat(result.user().getId()).isEqualTo(userId);
        RefreshToken old = refreshTokenRepository
                .findByTokenHash(TokenHasher.sha256(issued.rawRefreshToken())).orElseThrow();
        assertThat(old.isRevoked()).isTrue();
    }

    @Test
    @DisplayName("폐기된 토큰 재사용(탈취 신호) → 401 + 사용자 전체 세션 폐기")
    void 재사용은_전체_세션을_폐기한다() {
        IssuedTokens first = authService.issueTokens(userId);
        RefreshResult rotated = authService.refresh(first.rawRefreshToken());

        assertThatThrownBy(() -> authService.refresh(first.rawRefreshToken()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.AUTH_REFRESH_INVALID);

        // 회전으로 살아 있던 최신 토큰까지 전부 폐기됐다
        assertThatThrownBy(() -> authService.refresh(rotated.tokens().rawRefreshToken()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("만료된 토큰 → 401")
    void 만료는_401() {
        String raw = "expired-raw-token";
        refreshTokenRepository.save(RefreshToken.issue(
                userId, TokenHasher.sha256(raw),
                Instant.now().minus(Duration.ofDays(15)), Duration.ofDays(14)));

        assertThatThrownBy(() -> authService.refresh(raw))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.AUTH_REFRESH_INVALID);
    }

    @Test
    @DisplayName("logout: 토큰 폐기 → 이후 refresh 불가. 없는 토큰·null도 예외 없음(멱등)")
    void 로그아웃은_멱등이다() {
        IssuedTokens issued = authService.issueTokens(userId);
        authService.logout(issued.rawRefreshToken());
        authService.logout(issued.rawRefreshToken());
        authService.logout(null);

        assertThatThrownBy(() -> authService.refresh(issued.rawRefreshToken()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("알 수 없는 토큰 → 401")
    void 모르는_토큰은_401() {
        assertThatThrownBy(() -> authService.refresh("unknown-token"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.AUTH_REFRESH_INVALID);
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
cd be && ./gradlew test --tests 'com.ymc.user.service.AuthServiceIntegrationTest'
```
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`be/src/main/java/com/ymc/user/infra/security/TokenHasher.java`:
```java
package com.ymc.user.infra.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** refresh token 원문은 저장하지 않는다 — DB 유출 시에도 세션 탈취가 안 되게 해시만 둔다. */
public final class TokenHasher {

    private TokenHasher() {
    }

    public static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원 JVM", e);
        }
    }
}
```

`be/src/main/java/com/ymc/user/infra/security/RefreshTokenCookie.java`:
```java
package com.ymc.user.infra.security;

import java.time.Duration;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.ymc.common.config.AuthProperties;

import lombok.RequiredArgsConstructor;

/**
 * ymc_refresh 쿠키. Path를 /api/auth로 좁혀 다른 API 요청에는 실리지 않게 한다.
 * SameSite=Lax — 크로스사이트 POST에 쿠키가 실리지 않아 CSRF 방어의 근거다 (design §5).
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenCookie {

    public static final String NAME = "ymc_refresh";

    private final AuthProperties props;

    public ResponseCookie issue(String rawToken) {
        return base(rawToken, props.refreshTtl());
    }

    public ResponseCookie expire() {
        return base("", Duration.ZERO);
    }

    private ResponseCookie base(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(maxAge)
                .build();
    }
}
```

`be/src/main/java/com/ymc/user/service/AuthService.java`:
```java
package com.ymc.user.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.common.config.AuthProperties;
import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.user.domain.RefreshToken;
import com.ymc.user.domain.RefreshTokenRepository;
import com.ymc.user.domain.User;
import com.ymc.user.domain.UserRepository;
import com.ymc.user.infra.security.JwtTokenProvider;
import com.ymc.user.infra.security.TokenHasher;

import lombok.RequiredArgsConstructor;

/** 세션(토큰 쌍) 발급·회전·폐기. 회전·재사용 탐지 규칙은 design §5. */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthProperties props;

    public record IssuedTokens(String accessToken, long expiresInSeconds, String rawRefreshToken) {
    }

    public record RefreshResult(IssuedTokens tokens, User user) {
    }

    @Transactional
    public IssuedTokens issueTokens(UUID userId) {
        Instant now = Instant.now();
        String raw = generateRefreshToken();
        refreshTokenRepository.save(
                RefreshToken.issue(userId, TokenHasher.sha256(raw), now, props.refreshTtl()));
        return new IssuedTokens(
                jwtTokenProvider.issueAccessToken(userId, now),
                jwtTokenProvider.accessTtlSeconds(),
                raw);
    }

    // noRollbackFor: 재사용 감지 경로는 revokeAllActiveByUserId 실행 후 401을 던진다 —
    // 기본 롤백 규칙이면 그 예외가 전체 폐기를 롤백시켜 보안 기능이 무력화된다.
    @Transactional(noRollbackFor = ApiException.class)
    public RefreshResult refresh(String rawToken) {
        Instant now = Instant.now();
        RefreshToken current = refreshTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))
                .orElseThrow(AuthService::invalidRefresh);

        if (current.isRevoked()) {
            // 회전으로 폐기된 토큰의 재사용 = 탈취 신호. 응답은 일반 401과 동일 — 감지 사실 비노출.
            int closed = refreshTokenRepository.revokeAllActiveByUserId(current.getUserId(), now);
            log.warn("폐기된 refresh token 재사용 감지 — userId={} 활성 세션 {}건 폐기",
                    current.getUserId(), closed);
            throw invalidRefresh();
        }
        if (current.isExpired(now)) {
            throw invalidRefresh();
        }

        current.revoke(now);
        User user = userRepository.findById(current.getUserId())
                .orElseThrow(AuthService::invalidRefresh);
        // 같은 트랜잭션 안의 직접 호출 — issueTokens의 @Transactional은 REQUIRED라 의미가 같다.
        return new RefreshResult(issueTokens(user.getId()), user);
    }

    /** 멱등 — 쿠키가 없거나 이미 폐기된 토큰이어도 조용히 성공한다 (계약: 항상 204). */
    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))
                .ifPresent(token -> token.revoke(Instant.now()));
    }

    @Transactional(readOnly = true)
    public User getUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "알 수 없는 사용자입니다."));
    }

    private static String generateRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ApiException invalidRefresh() {
        return new ApiException(ErrorCode.AUTH_REFRESH_INVALID, "유효하지 않은 세션입니다.");
    }
}
```

- [ ] **Step 4: 통과 확인 → 전체 스위트 → 커밋**

```bash
cd be && ./gradlew test --tests 'com.ymc.user.service.AuthServiceIntegrationTest' && ./gradlew test
git add be/ && git commit -m "[YMC-213] feat(auth): 토큰 발급·회전·재사용 탐지·로그아웃"
```

---

### Task 6: AuthController — /api/auth/refresh·logout·me

**Files:**
- Create: `be/src/main/java/com/ymc/user/api/AuthController.java`, `be/src/main/java/com/ymc/user/api/dto/AuthUserDto.java`, `TokenResponse.java`, `MeResponse.java`
- Test: `be/src/test/java/com/ymc/user/api/AuthControllerIntegrationTest.java`

**Interfaces:**
- Consumes: Task 5 `AuthService`·`RefreshTokenCookie`
- Produces: 계약 0.1.2의 auth 3종 HTTP 표면. FE(Task 9)가 이 응답 형태(`{accessToken, expiresIn, user:{userId,email,displayName}}`)에 의존한다.

- [ ] **Step 1: 실패 테스트 작성**

`be/src/test/java/com/ymc/user/api/AuthControllerIntegrationTest.java`:
```java
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
```

- [ ] **Step 2: 실패 확인**

```bash
cd be && ./gradlew test --tests 'com.ymc.user.api.AuthControllerIntegrationTest'
```
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`be/src/main/java/com/ymc/user/api/dto/AuthUserDto.java`:
```java
package com.ymc.user.api.dto;

import java.util.UUID;

import com.ymc.user.domain.User;

/** 계약 AuthUser 스키마. */
public record AuthUserDto(UUID userId, String email, String displayName) {

    public static AuthUserDto from(User user) {
        return new AuthUserDto(user.getId(), user.getEmail(), user.getDisplayName());
    }
}
```

`be/src/main/java/com/ymc/user/api/dto/TokenResponse.java`:
```java
package com.ymc.user.api.dto;

import com.ymc.user.service.AuthService;

/** 계약 TokenResponse 스키마 — POST /api/auth/refresh 응답. */
public record TokenResponse(String accessToken, long expiresIn, AuthUserDto user) {

    public static TokenResponse from(AuthService.RefreshResult result) {
        return new TokenResponse(
                result.tokens().accessToken(),
                result.tokens().expiresInSeconds(),
                AuthUserDto.from(result.user()));
    }
}
```

`be/src/main/java/com/ymc/user/api/dto/MeResponse.java`:
```java
package com.ymc.user.api.dto;

import java.util.UUID;

import com.ymc.user.domain.User;

/** 계약 AuthUser 스키마 — GET /api/auth/me 응답. */
public record MeResponse(UUID userId, String email, String displayName) {

    public static MeResponse from(User user) {
        return new MeResponse(user.getId(), user.getEmail(), user.getDisplayName());
    }
}
```

`be/src/main/java/com/ymc/user/api/AuthController.java`:
```java
package com.ymc.user.api;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.user.api.dto.MeResponse;
import com.ymc.user.api.dto.TokenResponse;
import com.ymc.user.infra.security.RefreshTokenCookie;
import com.ymc.user.service.AuthService;

import lombok.RequiredArgsConstructor;

/** 계약(openapi.yaml 0.1.2)의 /api/auth. HTTP ↔ DTO 변환만 한다 (be/CLAUDE.md). */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookie refreshTokenCookie;

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @CookieValue(value = RefreshTokenCookie.NAME, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(ErrorCode.AUTH_REFRESH_INVALID, "세션이 없습니다.");
        }
        AuthService.RefreshResult result = authService.refresh(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshTokenCookie.issue(result.tokens().rawRefreshToken()).toString())
                .body(TokenResponse.from(result));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(value = RefreshTokenCookie.NAME, required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.expire().toString())
                .build();
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        return MeResponse.from(authService.getUser(UUID.fromString(jwt.getSubject())));
    }
}
```

- [ ] **Step 4: 통과 확인 → 전체 스위트 → 커밋**

```bash
cd be && ./gradlew test --tests 'com.ymc.user.api.AuthControllerIntegrationTest' && ./gradlew test
git add be/ && git commit -m "[YMC-213] feat(auth): /api/auth refresh·logout·me 엔드포인트"
```

---

### Task 7: oauth2Login 체인 + 성공/실패 핸들러

**Files:**
- Modify: `be/src/main/resources/application.yml` (google registration)
- Create: `be/src/main/java/com/ymc/user/infra/security/OAuthLoginSuccessHandler.java`, `OAuthLoginFailureHandler.java`
- Modify: `be/src/main/java/com/ymc/user/infra/security/SecurityConfig.java` (@Order(1) 체인 추가)
- Test: `be/src/test/java/com/ymc/user/infra/security/OAuthLoginSuccessHandlerTest.java`, `OAuthLoginFailureHandlerTest.java`

**Interfaces:**
- Consumes: Task 4 `OAuthUserService.upsert`, Task 5 `AuthService.issueTokens`·`RefreshTokenCookie`
- Produces: `GET /api/oauth2/authorization/google` 로그인 시작 → 콜백 → `{fe-origin}/auth/popup-done.html` 302 (실패 시 `?error=oauth_failed`). FE(Task 9)의 `login()`이 이 경로에 의존.

- [ ] **Step 1: google registration 설정** — `application.yml` 공통 블록에 추가 (`spring:` 키 아래 병합):

```yaml
  # FT-001 Google OAuth (YMC-227에서 콘솔 등록 완료 — 실값은 환경변수로).
  # redirect-uri의 baseUrl은 요청 Host 기준 → vite proxy(5173) 경유 시 5173으로 생성돼
  # 콘솔에 등록된 http://localhost:5173/api/login/oauth2/code/google 과 일치한다.
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID:dummy-client-id}
            client-secret: ${GOOGLE_CLIENT_SECRET:dummy-client-secret}
            scope: [openid, email, profile]
            redirect-uri: "{baseUrl}/api/login/oauth2/code/{registrationId}"
```

- [ ] **Step 2: 핸들러 실패 테스트 작성 (Mockito 단위)**

`be/src/test/java/com/ymc/user/infra/security/OAuthLoginSuccessHandlerTest.java`:
```java
package com.ymc.user.infra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import com.ymc.common.config.AuthProperties;
import com.ymc.user.domain.AuthProvider;
import com.ymc.user.domain.User;
import com.ymc.user.service.AuthService;
import com.ymc.user.service.OAuthUserService;

@ExtendWith(MockitoExtension.class)
class OAuthLoginSuccessHandlerTest {

    @Mock
    private OAuthUserService oAuthUserService;

    @Mock
    private AuthService authService;

    // AuthProperties는 record(final)라 Mockito 대상이 아님 — 실객체로 조립한다.
    private final AuthProperties props = new AuthProperties(
            "test-secret-key-that-is-32-bytes-long!!",
            Duration.ofMinutes(30), Duration.ofDays(14), "http://localhost:5173", false);

    @Test
    void 성공_시_upsert_쿠키_브릿지_리다이렉트() throws Exception {
        OAuthLoginSuccessHandler handler = new OAuthLoginSuccessHandler(
                oAuthUserService, authService, new RefreshTokenCookie(props), props);
        User user = User.register(AuthProvider.GOOGLE, "sub-1", "a@b.c", "홍길동", Instant.now());
        when(oAuthUserService.upsert(AuthProvider.GOOGLE, "sub-1", "a@b.c", "홍길동")).thenReturn(user);
        when(authService.issueTokens(any(UUID.class)))
                .thenReturn(new AuthService.IssuedTokens("access", 1800L, "raw-refresh"));

        DefaultOAuth2User principal = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of("sub", "sub-1", "email", "a@b.c", "name", "홍길동"),
                "sub");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                new TestingAuthenticationToken(principal, null));

        verify(oAuthUserService).upsert(AuthProvider.GOOGLE, "sub-1", "a@b.c", "홍길동");
        assertThat(response.getHeader("Set-Cookie")).contains("ymc_refresh=raw-refresh");
        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/auth/popup-done.html");
    }
}
```

`be/src/test/java/com/ymc/user/infra/security/OAuthLoginFailureHandlerTest.java`:
```java
package com.ymc.user.infra.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.AuthenticationException;

import com.ymc.common.config.AuthProperties;

class OAuthLoginFailureHandlerTest {

    @Test
    void 실패_시_error_쿼리를_실어_브릿지로_리다이렉트() throws Exception {
        AuthProperties props = new AuthProperties(
                "test-secret-key-that-is-32-bytes-long!!",
                Duration.ofMinutes(30), Duration.ofDays(14), "http://localhost:5173", false);
        OAuthLoginFailureHandler handler = new OAuthLoginFailureHandler(props);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new AuthenticationException("취소됨") {});

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/auth/popup-done.html?error=oauth_failed");
    }
}
```

- [ ] **Step 3: 실패 확인**

```bash
cd be && ./gradlew test --tests 'com.ymc.user.infra.security.OAuthLogin*'
```
Expected: 컴파일 실패

- [ ] **Step 4: 핸들러 구현**

`OAuthLoginSuccessHandler.java`:
```java
package com.ymc.user.infra.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.ymc.common.config.AuthProperties;
import com.ymc.user.domain.AuthProvider;
import com.ymc.user.domain.User;
import com.ymc.user.service.AuthService;
import com.ymc.user.service.OAuthUserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Google 인증 성공 → 사용자 upsert → refresh 쿠키 → FE 브릿지 페이지로 302.
 * access token은 여기서 주지 않는다 — FE가 /api/auth/refresh로 받아 메모리에만 둔다 (design §3).
 */
@Component
@RequiredArgsConstructor
public class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final OAuthUserService oAuthUserService;
    private final AuthService authService;
    private final RefreshTokenCookie refreshTokenCookie;
    private final AuthProperties props;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        OAuth2User principal = (OAuth2User) authentication.getPrincipal();
        String providerId = principal.getName();   // google user-name-attribute = sub
        String email = principal.getAttribute("email");
        String displayName = principal.getAttribute("name");

        User user = oAuthUserService.upsert(AuthProvider.GOOGLE, providerId, email, displayName);
        AuthService.IssuedTokens tokens = authService.issueTokens(user.getId());

        response.addHeader(HttpHeaders.SET_COOKIE,
                refreshTokenCookie.issue(tokens.rawRefreshToken()).toString());
        response.sendRedirect(props.feOrigin() + "/auth/popup-done.html");
    }
}
```

`OAuthLoginFailureHandler.java`:
```java
package com.ymc.user.infra.security;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import com.ymc.common.config.AuthProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** 인증 실패·사용자 취소 → 브릿지에 error 쿼리로 전달. 복구 플로우는 out of scope (FT-001). */
@Component
@RequiredArgsConstructor
public class OAuthLoginFailureHandler implements AuthenticationFailureHandler {

    private final AuthProperties props;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        response.sendRedirect(props.feOrigin() + "/auth/popup-done.html?error=oauth_failed");
    }
}
```

- [ ] **Step 5: SecurityConfig에 로그인 체인 추가** — `SecurityConfig`에 빈 추가:

```java
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
```
(import 추가: `org.springframework.core.annotation.Order`는 이미 있음 — `OAuthLoginSuccessHandler` 등은 같은 패키지라 불필요.)

- [ ] **Step 6: 통과 확인 → 전체 스위트 → 커밋**

```bash
cd be && ./gradlew test --tests 'com.ymc.user.infra.security.OAuthLogin*' && ./gradlew test
git add be/ && git commit -m "[YMC-213] feat(auth): Google oauth2Login — 성공 시 refresh 쿠키 발급 후 브릿지로 복귀"
```

- [ ] **Step 7: 로그인 시작 경로 스모크 확인** (선택 — Docker로 PG만 있으면 됨)

```bash
cd be && ./gradlew bootRun &
sleep 15
curl -s -o /dev/null -w "%{http_code} %{redirect_url}\n" "http://localhost:8080/api/oauth2/authorization/google"
kill %1
```
Expected: `302 https://accounts.google.com/o/oauth2/v2/auth?...&redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Flogin%2Foauth2%2Fcode%2Fgoogle...` (직접 호출이라 8080 — 5173 경유 시 5173으로 생성되는 것은 Task 13에서 확인)

---

### Task 8: papers 인증 연동 — 고정 소유자 제거 + fileKey 형식 (YMC-215)

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/domain/Paper.java` (fileKey 형식)
- Modify: `be/src/main/java/com/ymc/paper/service/PaperRegistrationService.java` (ownerId 파라미터화)
- Modify: `be/src/main/java/com/ymc/paper/api/PaperController.java` (JWT에서 userId 추출)
- Delete: `be/src/main/java/com/ymc/common/config/AppProperties.java`
- Modify: `be/src/main/java/com/ymc/common/config/AppConfig.java`, `be/src/main/resources/application.yml` (`app.fixed-owner-id` 제거)
- Modify: `be/src/test/java/com/ymc/support/IntegrationTest.java`, `be/src/test/java/com/ymc/paper/domain/PaperTest.java`, `be/src/test/java/com/ymc/paper/api/PaperRegistrationIntegrationTest.java`

**Interfaces:**
- Consumes: Task 2의 JWT principal (`jwt.getSubject()` = userId)
- Produces: `PaperRegistrationService.register(UUID ownerId, String filename, String contentType)`, fileKey 형식 `uploads/{ownerId}/{paperId}.pdf`

- [ ] **Step 1: 브랜치 생성**

```bash
git checkout -b YMC-215-upload-auth YMC-213-social-auth
```

- [ ] **Step 2: 실패 테스트 — 기존 테스트 갱신 + 신규**

`PaperTest.java`의 fileKey 검증을 새 형식으로 수정 (기존 `papers/{id}/original.pdf` 단언을 찾아 교체):
```java
    @Test
    void register는_uploads_ownerId_paperId_형식의_fileKey를_만든다() {
        UUID ownerId = UUID.randomUUID();
        Paper paper = Paper.register(ownerId, "a.pdf", Instant.now());
        assertThat(paper.getFileKey())
                .isEqualTo("uploads/%s/%s.pdf".formatted(ownerId, paper.getId()));
    }
```

`PaperRegistrationIntegrationTest.java`에 테스트 2개 추가:
```java
    @Test
    @DisplayName("등록된 Paper의 ownerId는 JWT subject다 (YMC-215)")
    void 소유자는_인증_주체다() throws Exception {
        mockMvc.perform(post("/api/papers").with(userJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"owner.pdf\",\"contentType\":\"application/pdf\"}"))
                .andExpect(status().isCreated());

        Paper saved = paperRepository.findAll().get(0);
        assertThat(saved.getOwnerId()).isEqualTo(TEST_USER_ID);
        assertThat(saved.getFileKey())
                .isEqualTo("uploads/%s/%s.pdf".formatted(TEST_USER_ID, saved.getId()));
    }

    @Test
    @DisplayName("파일명 중복 판정은 사용자 단위 — 다른 사용자는 같은 파일명 등록 가능 (YMC-215)")
    void 중복_판정은_사용자_스코프다() throws Exception {
        String body = "{\"filename\":\"same.pdf\",\"contentType\":\"application/pdf\"}";
        mockMvc.perform(post("/api/papers").with(userJwt())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        UUID otherUser = UUID.randomUUID();
        mockMvc.perform(post("/api/papers")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .jwt().jwt(j -> j.subject(otherUser.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }
```

- [ ] **Step 3: 실패 확인**

```bash
cd be && ./gradlew test --tests 'com.ymc.paper.domain.PaperTest' --tests 'com.ymc.paper.api.PaperRegistrationIntegrationTest'
```
Expected: FAIL (fileKey 형식·시그니처)

- [ ] **Step 4: 구현**

`Paper.java` — 상수·생성자 수정:
```java
    /** 계약(openapi.yaml `PaperCreated.fileKey`)의 형식 — uploads/{ownerId}/{paperId}.pdf (ADR-002). */
    private static final String FILE_KEY_FORMAT = "uploads/%s/%s.pdf";
```
생성자에서:
```java
        this.fileKey = FILE_KEY_FORMAT.formatted(ownerId, id);
```

`PaperRegistrationService.java` — `AppProperties` 필드·import 제거, 시그니처 변경:
```java
    @Transactional
    public PaperRegistrationResult register(UUID ownerId, String filename, String contentType) {
```
본문의 `UUID ownerId = appProperties.fixedOwnerId();` 줄 삭제 (이하 동일).

`PaperController.java` — create 메서드 수정:
```java
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
```
```java
    /** 논문 레코드 생성 및 presigned 업로드 URL 발급. 소유자는 인증 주체다 (YMC-215). */
    @PostMapping
    public ResponseEntity<PaperCreated> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreatePaperRequest request) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        PaperCreated body = PaperCreated.from(
                registrationService.register(ownerId, request.filename(), request.contentType()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
```

`AppProperties.java` 삭제, `AppConfig.java`:
```java
@EnableConfigurationProperties(AuthProperties.class)
```
`application.yml`의 `app:` 블록(`fixed-owner-id` 주석 포함) 삭제.

`IntegrationTest.java` — `AppProperties` import·필드 삭제, `givenPendingPaper`를:
```java
    protected Paper givenPendingPaper(String filename) {
        return paperRepository.save(Paper.register(TEST_USER_ID, filename, Instant.now()));
    }
```

- [ ] **Step 5: 전체 스위트 → 커밋**

```bash
cd be && ./gradlew test
git add -A be/ && git commit -m "[YMC-215] feat(paper): 소유자를 인증 주체로 교체, fileKey uploads/{userId}/{paperId}.pdf (ADR-002)"
```

---

### Task 9: FE auth.js + 브릿지 페이지

**Files:**
- Create: `fe/src/auth.js`, `fe/public/auth/popup-done.html`
- Test: `fe/src/auth.test.js`

**Interfaces:**
- Consumes: BE `/api/auth/refresh`(`{accessToken, expiresIn, user}`)·`/api/auth/logout`, `/api/oauth2/authorization/google`
- Produces: `bootstrap(): Promise<user|null>`, `login({onComplete(user|null, error|null)}): () => void`(리스너 해제 함수), `logout(): Promise<void>`, `authFetch(url, opts): Promise<Response>`, `onSessionExpired(cb)`, `_resetForTest()`

- [ ] **Step 1: 브랜치 생성**

```bash
git checkout -b YMC-214-login-ui YMC-215-upload-auth
```

- [ ] **Step 2: 실패 테스트 작성**

`fe/src/auth.test.js`:
```js
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { bootstrap, authFetch, login, logout, onSessionExpired, _resetForTest } from './auth';

function jsonRes(status, body = {}) {
  return { ok: status < 400, status, json: async () => body };
}
const TOKEN_BODY = { accessToken: 'A1', expiresIn: 1800, user: { userId: 'u1', email: 'e@x.y', displayName: '홍길동' } };

describe('auth.js', () => {
  beforeEach(() => _resetForTest());
  afterEach(() => vi.restoreAllMocks());

  it('bootstrap: refresh 성공 → user 반환, 이후 요청에 Bearer 부착', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce(jsonRes(200, TOKEN_BODY))
      .mockResolvedValueOnce(jsonRes(200));
    const user = await bootstrap();
    expect(user.email).toBe('e@x.y');
    expect(global.fetch).toHaveBeenCalledWith('/api/auth/refresh', { method: 'POST' });

    await authFetch('/api/papers');
    expect(global.fetch).toHaveBeenLastCalledWith('/api/papers',
      expect.objectContaining({ headers: { Authorization: 'Bearer A1' } }));
  });

  it('bootstrap: 401(쿠키 없음) → null', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonRes(401));
    expect(await bootstrap()).toBeNull();
  });

  it('authFetch: 401 → refresh 1회 → 원 요청 재시도 성공', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce(jsonRes(401))            // 원 요청
      .mockResolvedValueOnce(jsonRes(200, TOKEN_BODY)) // refresh
      .mockResolvedValueOnce(jsonRes(200, { papers: [] })); // 재시도
    const res = await authFetch('/api/papers');
    expect(res.status).toBe(200);
    expect(global.fetch).toHaveBeenCalledTimes(3);
    expect(global.fetch.mock.calls[1][0]).toBe('/api/auth/refresh');
  });

  it('authFetch: refresh도 401 → 원 401 반환 + onSessionExpired 통지', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce(jsonRes(401))
      .mockResolvedValueOnce(jsonRes(401));
    const expired = vi.fn();
    onSessionExpired(expired);
    const res = await authFetch('/api/papers');
    expect(res.status).toBe(401);
    expect(expired).toHaveBeenCalledOnce();
  });

  it('single-flight: 동시 401 두 건 → refresh는 1번만', async () => {
    let refreshCalls = 0;
    global.fetch = vi.fn(async (url) => {
      if (url === '/api/auth/refresh') { refreshCalls += 1; return jsonRes(200, TOKEN_BODY); }
      return refreshCalls === 0 ? jsonRes(401) : jsonRes(200);
    });
    await Promise.all([authFetch('/api/a'), authFetch('/api/b')]);
    expect(refreshCalls).toBe(1);
  });

  it('login: 팝업 후 same-origin auth:complete 수신 → refresh → onComplete(user)', async () => {
    vi.stubGlobal('open', vi.fn(() => ({})));
    global.fetch = vi.fn().mockResolvedValue(jsonRes(200, TOKEN_BODY));
    const onComplete = vi.fn();

    login({ onComplete });
    expect(window.open).toHaveBeenCalledWith('/api/oauth2/authorization/google', 'ymc-auth', expect.any(String));

    window.dispatchEvent(new MessageEvent('message', {
      data: { type: 'auth:complete', error: null }, origin: window.location.origin,
    }));
    await vi.waitFor(() => expect(onComplete).toHaveBeenCalled());
    expect(onComplete).toHaveBeenCalledWith(expect.objectContaining({ email: 'e@x.y' }), null);
  });

  it('login: 다른 origin 메시지는 무시', async () => {
    vi.stubGlobal('open', vi.fn(() => ({})));
    global.fetch = vi.fn();
    const onComplete = vi.fn();
    login({ onComplete });
    window.dispatchEvent(new MessageEvent('message', {
      data: { type: 'auth:complete' }, origin: 'https://evil.example',
    }));
    await new Promise((r) => setTimeout(r, 0));
    expect(onComplete).not.toHaveBeenCalled();
  });

  it('logout: POST 후 Authorization 미부착', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonRes(200, TOKEN_BODY));
    await bootstrap();
    global.fetch = vi.fn().mockResolvedValue(jsonRes(204));
    await logout();
    expect(global.fetch).toHaveBeenCalledWith('/api/auth/logout', { method: 'POST' });

    global.fetch = vi.fn().mockResolvedValue(jsonRes(200));
    await authFetch('/api/papers');
    expect(global.fetch).toHaveBeenLastCalledWith('/api/papers',
      expect.objectContaining({ headers: {} }));
  });
});
```

- [ ] **Step 3: 실패 확인**

```bash
cd fe && npx vitest run src/auth.test.js
```
Expected: FAIL — `./auth` 모듈 없음

- [ ] **Step 4: auth.js 구현**

`fe/src/auth.js`:
```js
// 인증 연동 단일 접점 — api.js와 같은 결. access token은 이 모듈 메모리에만 존재한다
// (URL·스토리지 노출 금지, design §3). 표현 층과 분리 — 진짜 FE가 이 모듈을 승계한다.

let accessToken = null;
let refreshPromise = null;
let sessionExpiredHandler = null;

/** 세션이 죽었을 때(재갱신 실패) 한 곳에서 비로그인 전환을 처리하게 한다. */
export function onSessionExpired(handler) {
  sessionExpiredHandler = handler;
}

/** 앱 시작 시 1회 — refresh 쿠키로 세션 복원. 성공: user, 실패(비로그인): null. */
export function bootstrap() {
  return doRefresh();
}

// 동시 401들이 refresh를 중복 호출하지 않게 진행 중 Promise를 공유한다 (single-flight).
function doRefresh() {
  if (!refreshPromise) {
    refreshPromise = (async () => {
      try {
        const res = await fetch('/api/auth/refresh', { method: 'POST' });
        if (!res.ok) {
          accessToken = null;
          return null;
        }
        const body = await res.json();
        accessToken = body.accessToken;
        return body.user;
      } finally {
        refreshPromise = null;
      }
    })();
  }
  return refreshPromise;
}

/**
 * Google 로그인 시작 — 팝업에서 진행하고 랜딩을 떠나지 않는다 (design §3).
 * 팝업 차단 시 전체 리다이렉트 폴백. 반환값은 message 리스너 해제 함수.
 */
export function login({ onComplete }) {
  const url = '/api/oauth2/authorization/google';
  const popup = window.open(url, 'ymc-auth', 'width=480,height=640');
  if (!popup) {
    window.location.href = url; // 폴백 — 복귀는 브릿지가 루트로 돌려보내고 bootstrap이 집어 올린다
    return () => {};
  }
  const listener = async (event) => {
    if (event.origin !== window.location.origin) return;
    if (!event.data || event.data.type !== 'auth:complete') return;
    window.removeEventListener('message', listener);
    if (event.data.error) {
      onComplete(null, event.data.error);
      return;
    }
    const user = await doRefresh();
    onComplete(user, user ? null : 'refresh_failed');
  };
  window.addEventListener('message', listener);
  return () => window.removeEventListener('message', listener);
}

export async function logout() {
  await fetch('/api/auth/logout', { method: 'POST' });
  accessToken = null;
}

/**
 * Authorization 자동 부착 fetch. 401이면 refresh 1회 후 재시도 —
 * 그래도 401이면 원 응답을 돌려주고 onSessionExpired로 통지한다.
 * S3 presigned 요청(uploadToS3)에는 쓰지 않는다.
 */
export async function authFetch(url, opts = {}) {
  const res = await doFetch(url, opts);
  if (res.status !== 401) return res;
  const user = await doRefresh();
  if (!user) {
    if (sessionExpiredHandler) sessionExpiredHandler();
    return res;
  }
  return doFetch(url, opts);
}

function doFetch(url, opts) {
  const headers = { ...(opts.headers || {}) };
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  return fetch(url, { ...opts, headers });
}

/** 테스트 전용 — 모듈 상태 초기화. */
export function _resetForTest() {
  accessToken = null;
  refreshPromise = null;
  sessionExpiredHandler = null;
}
```

- [ ] **Step 5: 브릿지 페이지 작성**

`fe/public/auth/popup-done.html`:
```html
<!doctype html>
<html lang="ko">
  <head><meta charset="utf-8" /><title>로그인 처리 중…</title></head>
  <body>
    <script>
      // OAuth 복귀 지점. 팝업이면 본창에 알리고 닫힌다. 전체 리다이렉트 폴백이면 루트로.
      var error = new URLSearchParams(location.search).get('error');
      if (window.opener) {
        window.opener.postMessage({ type: 'auth:complete', error: error }, location.origin);
        window.close();
      } else {
        location.replace(error ? '/?error=' + encodeURIComponent(error) : '/');
      }
    </script>
  </body>
</html>
```

- [ ] **Step 6: 통과 확인**

```bash
cd fe && npx vitest run src/auth.test.js
```
Expected: PASS (8 tests)

- [ ] **Step 7: 커밋**

```bash
git add fe/src/auth.js fe/src/auth.test.js fe/public/auth/popup-done.html
git commit -m "[YMC-214] feat(fe): auth 연동층 — 부트스트랩·팝업 로그인·refresh 재시도·로그아웃"
```

---

### Task 10: api.js를 authFetch로 전환

**Files:**
- Modify: `fe/src/api.js`, `fe/src/api.test.js`

**Interfaces:**
- Consumes: Task 9 `authFetch`
- Produces: api.js 공개 함수 시그니처·반환 무변경 (App.jsx 호출부 그대로)

- [ ] **Step 1: api.test.js 기대값 갱신 (실패 먼저)** — authFetch는 항상 두 번째 인자(headers 포함)를 넘기므로:

```js
// completeUpload 테스트의 기대를:
expect(global.fetch).toHaveBeenCalledWith('/api/papers/p1/complete',
  expect.objectContaining({ method: 'POST' }));
// getStatus / getDownloadUrl / listPapers 의 1-인자 기대를 각각:
expect(global.fetch).toHaveBeenCalledWith('/api/papers/p1/status', expect.objectContaining({}));
expect(global.fetch).toHaveBeenCalledWith('/api/papers/p1/download', expect.objectContaining({}));
expect(global.fetch).toHaveBeenCalledWith('/api/papers', expect.objectContaining({}));
```
(createPaper·실패 응답 테스트는 그대로 통과한다. `uploadToS3` 테스트는 손대지 않는다.)

- [ ] **Step 2: 실행 — 아직 api.js 미변경이므로 기대 갱신분은 통과, 이후 변경 검증용 기준 확립**

```bash
cd fe && npx vitest run src/api.test.js
```
Expected: PASS

- [ ] **Step 3: api.js 전환** — 상단에 import 추가, `fetch(` 5곳(`createPaper`·`completeUpload`·`getStatus`·`getDownloadUrl`·`listPapers`)을 `authFetch(`로 교체. **`uploadToS3`(XHR·presigned)는 그대로 둔다.**

```js
import { authFetch } from './auth';
```
파일 머리 주석에 한 줄 추가:
```js
// BE 호출은 authFetch(자동 Bearer + 401 재시도)를 쓴다. S3 presigned PUT은 예외 — 서명 URL이 인가다.
```

- [ ] **Step 4: 전체 FE 테스트 통과 확인 → 커밋**

```bash
cd fe && npx vitest run
git add fe/src/api.js fe/src/api.test.js
git commit -m "[YMC-214] feat(fe): api.js를 authFetch로 전환 — 호출부 시그니처 무변경"
```

---

### Task 11: Landing 컴포넌트 — WF-001·002·003

**Files:**
- Create: `fe/src/Landing.jsx`

**Interfaces:**
- Consumes: Task 9 `login`
- Produces: `<Landing user={user|null} initialError={string|null} onAuthed={(user)=>{}} onEnterLibrary={()=>{}} />` — 비로그인(WF-001)+모달(WF-003) / 로그인(WF-002) 렌더

(FE 표현 층은 기존 관례대로 자동 테스트 없음 — App.jsx와 동일. 검증은 Task 13 수동 E2E.)

- [ ] **Step 1: 구현**

`fe/src/Landing.jsx`:
```jsx
// 랜딩(WF-001/002) + 인증 모달(WF-003). 와이어프레임 수준 구현 — 시안 확정 시 표현만 교체한다.
import { useState } from 'react';
import { login } from './auth';

const center = {
  minHeight: '100vh', display: 'flex', flexDirection: 'column',
  alignItems: 'center', justifyContent: 'center', gap: 16, textAlign: 'center',
};
const primaryBtn = {
  padding: '12px 28px', fontSize: 16, borderRadius: 8, border: 'none',
  background: '#1a73e8', color: '#fff', cursor: 'pointer',
};
const providerBtn = (disabled) => ({
  display: 'block', width: 280, margin: '8px auto', padding: '12px 16px',
  fontSize: 15, borderRadius: 8, border: '1px solid #dadce0',
  background: disabled ? '#f1f3f4' : '#fff',
  color: disabled ? '#9aa0a6' : '#3c4043',
  cursor: disabled ? 'not-allowed' : 'pointer',
});

export default function Landing({ user, initialError, onAuthed, onEnterLibrary }) {
  const [modalOpen, setModalOpen] = useState(false);
  const [error, setError] = useState(initialError);
  const [pending, setPending] = useState(false);

  const handleGoogle = () => {
    setPending(true);
    setError(null);
    login({
      onComplete: (authedUser, loginError) => {
        setPending(false);
        if (loginError || !authedUser) {
          setError('로그인에 실패했습니다. 다시 시도해 주세요.');
          return;
        }
        setModalOpen(false);
        onAuthed(authedUser); // AuthRoot가 서재로 라우팅 (FT-001 Story 1 AC)
      },
    });
  };

  // WF-002: 로그인 랜딩 — 내 서재 가기
  if (user) {
    return (
      <div style={center}>
        <div style={{ fontSize: 48 }}>📄</div>
        <h1 style={{ fontSize: 24 }}>논문을 이해하도록 가르치는<br />AI 리딩 튜터</h1>
        <button type="button" style={primaryBtn} onClick={onEnterLibrary}>내 서재 가기 →</button>
      </div>
    );
  }

  // WF-001: 비로그인 랜딩 (+ WF-003 모달 오버레이)
  return (
    <div style={center}>
      <div style={{ fontSize: 48 }}>📄</div>
      <h1 style={{ fontSize: 24 }}>논문을 이해하도록 가르치는<br />AI 리딩 튜터</h1>
      <button type="button" style={primaryBtn} onClick={() => setModalOpen(true)}>
        로그인 / 회원가입
      </button>
      {error && !modalOpen && <p style={{ color: '#d93025' }}>{error}</p>}

      {modalOpen && (
        <div
          style={{
            position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}
          onClick={() => setModalOpen(false)}
        >
          <div
            style={{ background: '#fff', borderRadius: 12, padding: 32, width: 360, position: 'relative' }}
            onClick={(e) => e.stopPropagation()}
          >
            <button
              type="button" aria-label="닫기"
              style={{ position: 'absolute', top: 12, right: 12, border: 'none', background: 'none', fontSize: 18, cursor: 'pointer' }}
              onClick={() => setModalOpen(false)}
            >
              ✕
            </button>
            <h2 style={{ fontSize: 20, marginBottom: 8 }}>로그인 또는 회원가입</h2>
            <p style={{ color: '#5f6368', fontSize: 14, marginBottom: 16 }}>
              논문을 읽고 AI 튜터와 대화하려면 계속 진행해 주세요
            </p>
            <button type="button" style={providerBtn(false)} onClick={handleGoogle} disabled={pending}>
              {pending ? '로그인 진행 중…' : 'Google로 계속하기'}
            </button>
            <button type="button" style={providerBtn(true)} disabled>Kakao로 계속하기 (준비 중)</button>
            <button type="button" style={providerBtn(true)} disabled>Naver로 계속하기 (준비 중)</button>
            {error && <p style={{ color: '#d93025', fontSize: 13 }}>{error}</p>}
            <p style={{ color: '#9aa0a6', fontSize: 12, marginTop: 16 }}>
              계속하면 이용약관 및 개인정보 처리방침에 동의하는 것으로 간주됩니다
            </p>
          </div>
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: 린트 수준 확인(빌드) → 커밋**

```bash
cd fe && npx vitest run && npm run build
git add fe/src/Landing.jsx
git commit -m "[YMC-214] feat(fe): 랜딩·인증 모달 — WF-001/002/003 와이어프레임 구현"
```

---

### Task 12: AuthRoot 배선 — 부트스트랩 분기 + 서재 가드 + 로그아웃

**Files:**
- Create: `fe/src/AuthRoot.jsx`
- Modify: `fe/src/main.jsx`

**Interfaces:**
- Consumes: Task 9 `bootstrap`·`logout`·`onSessionExpired`, Task 11 `Landing`, 기존 `App`(서재/검증 UI)
- Produces: 루트 컴포넌트 `<AuthRoot />` — loading → guest(WF-001) / authed(WF-002 → 서재)

- [ ] **Step 1: 구현**

`fe/src/AuthRoot.jsx`:
```jsx
// 인증 게이트 — 부트스트랩 후 비로그인 랜딩 / 로그인 랜딩 / 서재를 분기한다.
// 서재(App)는 인증 상태에서만 마운트되므로 App 내부는 인증을 모른다.
import { useEffect, useState } from 'react';
import App from './App.jsx';
import Landing from './Landing.jsx';
import { bootstrap, logout, onSessionExpired } from './auth';

export default function AuthRoot() {
  const [auth, setAuth] = useState({ status: 'loading', user: null });
  const [view, setView] = useState('landing'); // 'landing' | 'library'
  // 전체 리다이렉트 폴백의 실패 복귀(/?error=...) 표시용 — 1회 읽고 URL에서 지운다.
  const [initialError] = useState(() => {
    const params = new URLSearchParams(window.location.search);
    const error = params.get('error');
    if (error) window.history.replaceState(null, '', '/');
    return error ? '로그인에 실패했습니다. 다시 시도해 주세요.' : null;
  });

  useEffect(() => {
    onSessionExpired(() => {
      setAuth({ status: 'guest', user: null });
      setView('landing');
    });
    bootstrap().then((user) =>
      setAuth({ status: user ? 'authed' : 'guest', user }));
  }, []);

  const handleLogout = async () => {
    await logout();
    setAuth({ status: 'guest', user: null });
    setView('landing');
  };

  if (auth.status === 'loading') {
    return <div style={{ padding: 48, textAlign: 'center' }}>불러오는 중…</div>;
  }

  if (auth.status === 'guest' || view === 'landing') {
    return (
      <Landing
        user={auth.user}
        initialError={initialError}
        onAuthed={(user) => {
          setAuth({ status: 'authed', user });
          setView('library'); // 인증 완료 → 서재 (FT-001 Story 1 AC)
        }}
        onEnterLibrary={() => setView('library')}
      />
    );
  }

  return (
    <div>
      <header
        style={{
          display: 'flex', justifyContent: 'flex-end', alignItems: 'center',
          gap: 12, padding: '10px 20px', borderBottom: '1px solid #eee', fontSize: 14,
        }}
      >
        <span style={{ color: '#5f6368' }}>{auth.user.email ?? auth.user.displayName}</span>
        <button
          type="button"
          style={{ border: '1px solid #dadce0', background: '#fff', borderRadius: 6, padding: '6px 12px', cursor: 'pointer' }}
          onClick={handleLogout}
        >
          로그아웃
        </button>
      </header>
      <App />
    </div>
  );
}
```

`fe/src/main.jsx` — `App` 대신 `AuthRoot` 렌더:
```jsx
import React from 'react';
import { createRoot } from 'react-dom/client';
import AuthRoot from './AuthRoot.jsx';

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <AuthRoot />
  </React.StrictMode>,
);
```

- [ ] **Step 2: 테스트·빌드 → 커밋**

```bash
cd fe && npx vitest run && npm run build
git add fe/src/AuthRoot.jsx fe/src/main.jsx
git commit -m "[YMC-214] feat(fe): AuthRoot 인증 게이트 — 부트스트랩 분기·서재 가드·로그아웃"
```

---

### Task 13: 수동 E2E 검증 + PR 정리

**Files:** 없음 (검증·PR)

- [ ] **Step 1: 환경 기동**

```bash
# 사전: Docker 실행 중, YMC-227의 client JSON에서 값 확보
export GOOGLE_CLIENT_ID="<콘솔 발급값>"
export GOOGLE_CLIENT_SECRET="<콘솔 발급값>"
cd "/Users/geunhh/Library/Mobile Documents/com~apple~CloudDocs/Desktop/team-ymc/app"
docker compose -f infra/local/docker-compose.yml up -d
(cd be && ./gradlew bootRun) &
(cd fe && npm install && npm run dev) &
```

- [ ] **Step 2: Google 콘솔 사전 확인** — YMC-227에서 등록한 redirect URI에 `http://localhost:5173/api/login/oauth2/code/google`이 **정확히** 있는지, Audience가 Testing이면 본인 계정이 테스트 사용자로 등록돼 있는지 확인.

- [ ] **Step 3: FT-001 Done Criteria 체크리스트** — `http://localhost:5173` 접속 후 순서대로:

1. 비로그인 랜딩(WF-001) 노출 → "로그인/회원가입" → 모달(WF-003: 딤·닫기·버튼 3개·약관 문구, Kakao/Naver 비활성)
2. 모달 닫기(X·바깥 클릭) → WF-001 복귀
3. "Google로 계속하기" → **팝업**에서 Google 로그인 → 팝업 자동 닫힘 → 모달 닫히고 **서재 진입** (랜딩 페이지는 이동 없음)
4. DB 확인: `docker compose -f infra/local/docker-compose.yml exec postgres psql -U ymc -c "select provider, provider_id, email from users;"` → 1행
5. 새로고침 → 로그인 유지(WF-002 또는 서재), users 행 그대로(재로그인 시에도 1행 — 기존 식별)
6. PDF 업로드 1건 → `select owner_id, file_key from paper;` → owner_id가 users.id와 일치, file_key가 `uploads/{userId}/{paperId}.pdf`
7. 로그아웃 → 비로그인 랜딩 전환 → 새로고침해도 비로그인 유지
8. 브라우저에서 팝업 차단 설정 후 로그인 → 전체 리다이렉트로 진행되고 복귀 후 로그인 상태 확인 (폴백)
9. Google 로그인 창에서 취소 → 모달에 "로그인에 실패했습니다" 표시
10. 미로그인 상태에서 `curl -i http://localhost:5173/api/papers` → `401` + `{"code":"UNAUTHORIZED",...}`

문제 발견 시: BE 무변경 원칙 없음(이번엔 우리 코드) — 고치고 해당 Task의 테스트를 보강한 뒤 재검증.

- [ ] **Step 4: PR 생성 (스택 순서대로, 트레일러 금지)**

```bash
git push -u origin YMC-213-social-auth YMC-215-upload-auth YMC-214-login-ui
gh pr create --base main --head YMC-213-social-auth \
  --title "[YMC-213] feat(auth): Google 소셜 로그인 — oauth2Login + JWT/refresh 회전" \
  --body "Source: project-docs/features/FT-001-소셜-인증.md / Jira: YMC-213. 계약: project-docs#<Task1 PR>. Spec: docs/superpowers/specs/2026-07-17-google-auth-design.md"
gh pr create --base YMC-213-social-auth --head YMC-215-upload-auth \
  --title "[YMC-215] feat(paper): 업로드 인증 연동 — 소유자 교체·fileKey 형식" \
  --body "Jira: YMC-215. Depends: YMC-213 PR."
gh pr create --base YMC-215-upload-auth --head YMC-214-login-ui \
  --title "[YMC-214] feat(fe): 로그인 UI — 팝업 OAuth·랜딩·인증 모달(WF-001/002/003)" \
  --body "Jira: YMC-214. Depends: YMC-215 PR."
```

- [ ] **Step 5: Jira 상태 갱신** — YMC-213·214·215를 "진행 중"→리뷰 상태로 이동 (또는 사용자에게 보고).
