# dev 로그인 화이트리스트 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** dev 서버에서 허용 이메일 명단에 없는 Google 계정의 로그인(=가입)을 차단한다.

**Architecture:** 정책(`LoginWhitelist`)은 `user/service/`, 스프링 배선(`WhitelistedOidcUserService`)은 `user/infra/security/`에 둔다. 게이트는 `auth.login-whitelist` 설정으로만 켜지고 꺼지며 Java에 프로필 분기가 없다 — dev·prod가 같은 이미지를 승격시키기 때문이다. 거부는 `OAuth2AuthenticationException`으로 던져 기존 `OAuthLoginFailureHandler` 배선을 재사용한다.

**Tech Stack:** Spring Boot 3 / Spring Security 6 (OAuth2 Client, OIDC), JUnit 5 + AssertJ, React + vitest, Terraform (ECS)

**Spec:** `docs/superpowers/specs/2026-08-06-dev-login-whitelist-design.md`
**Jira:** YMC-310 / **브랜치:** `YMC-310-dev-login-whitelist` (이미 체크아웃돼 있음, main 기준)

## Global Constraints

- 커밋 메시지는 `[YMC-310] type(scope): subject` 형식. **`Co-Authored-By`·`Generated with` 트레일러 금지** (팀 규칙).
- **Java 코드에 프로필 분기(`@Profile`, `Environment.getActiveProfiles()`) 금지.** 게이트 on/off는 설정값(빈 목록이면 off)으로만 결정한다.
- Google 등록 스코프가 `[openid, email, profile]`이므로 스프링은 `OidcUserService`를 쓴다. 게이트는 **반드시** `.oidcUserService(...)`에 등록한다. `.userService(...)`에 걸면 호출되지 않는다.
- 이메일 비교는 `trim()` + `toLowerCase(Locale.ROOT)` 정규화 후 수행한다. 명단과 입력 양쪽 다.
- 빈 목록 = 게이트 off (prod·로컬 기본 동작). 이 규칙을 뒤집지 말 것.
- `be/CLAUDE.md`: 빈 주입은 `@RequiredArgsConstructor` + `final` 필드가 기본. 생성자에서 작업이 필요할 때만 명시적 생성자.
- 주석에 티켓 키·스펙 절 번호를 괄호로 인용하지 않는다. 제약의 내용만 적는다.

## File Structure

| 파일 | 책임 |
|---|---|
| `be/src/main/java/com/ymc/common/config/AuthProperties.java` (수정) | `loginWhitelist` 컴포넌트 추가 + 정규화 |
| `be/src/main/java/com/ymc/user/service/LoginWhitelist.java` (신규) | 허용 여부 판정. 순수 로직 |
| `be/src/main/java/com/ymc/user/infra/security/WhitelistedOidcUserService.java` (신규) | OIDC userinfo 위임 + 거부 예외 |
| `be/src/main/java/com/ymc/user/infra/security/OAuthLoginFailureHandler.java` (수정) | `not_allowed` 코드만 통과, 나머지는 `oauth_failed` |
| `be/src/main/java/com/ymc/user/infra/security/SecurityConfig.java` (수정) | `.oidcUserService(...)` 배선 |
| `be/src/main/resources/application.yml` (수정) | 기본 블록 빈 값 / dev 블록 `${LOGIN_WHITELIST}` |
| `fe/src/auth/AuthContext.tsx` (수정) | `not_allowed` 문구 분기 (2곳) |
| `infra/deploy/**` (수정) | `login_whitelist` 변수 + 태스크 환경변수 |

---

### Task 1: `AuthProperties`에 명단 추가와 정규화

**Files:**
- Modify: `be/src/main/java/com/ymc/common/config/AuthProperties.java`
- Modify: `be/src/main/resources/application.yml`
- Modify: `be/src/test/java/com/ymc/user/infra/security/JwtTokenProviderTest.java:21`
- Modify: `be/src/test/java/com/ymc/user/infra/security/OAuthLoginFailureHandlerTest.java:18`
- Modify: `be/src/test/java/com/ymc/user/infra/security/OAuthLoginSuccessHandlerTest.java:39`
- Test: `be/src/test/java/com/ymc/common/config/AuthPropertiesTest.java` (신규)

**Interfaces:**
- Consumes: 없음
- Produces: `AuthProperties.loginWhitelist()` → `Set<String>` (정규화·불변, 절대 null 아님)

> **주의:** 레코드에 컴포넌트를 추가하면 생성자 시그니처가 바뀐다. 위 테스트 3곳이 컴파일 에러가 나므로 같은 태스크에서 함께 고친다.

- [ ] **Step 1: 실패 테스트 작성**

`be/src/test/java/com/ymc/common/config/AuthPropertiesTest.java` 생성:

```java
package com.ymc.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AuthPropertiesTest {

    private static AuthProperties withWhitelist(Set<String> whitelist) {
        return new AuthProperties(
                "test-secret-key-that-is-32-bytes-long!!",
                Duration.ofMinutes(30), Duration.ofDays(14), "http://localhost:5173", false,
                whitelist);
    }

    @Test
    void 명단이_null이면_빈_집합이다() {
        assertThat(withWhitelist(null).loginWhitelist()).isEmpty();
    }

    @Test
    void 명단은_소문자로_정규화되고_앞뒤_공백이_제거된다() {
        AuthProperties props = withWhitelist(Set.copyOf(List.of("  A@Example.COM ", "b@x.com")));
        assertThat(props.loginWhitelist()).containsExactlyInAnyOrder("a@example.com", "b@x.com");
    }

    @Test
    void 빈_문자열_항목은_버린다() {
        AuthProperties props = withWhitelist(Set.copyOf(List.of("a@x.com", "   ")));
        assertThat(props.loginWhitelist()).containsExactly("a@x.com");
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
cd be && ./gradlew compileTestJava
```

Expected: FAIL — `constructor AuthProperties cannot be applied to given types` (컴포넌트 6개 vs 5개). `AuthPropertiesTest` 외에 `JwtTokenProviderTest`·`OAuthLoginFailureHandlerTest`·`OAuthLoginSuccessHandlerTest`에서도 같은 에러가 난다.

- [ ] **Step 3: `AuthProperties` 수정**

`be/src/main/java/com/ymc/common/config/AuthProperties.java` 전체를 아래로 교체:

```java
package com.ymc.common.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml의 auth.* 바인딩 (FT-001). 만료값은 UX 파라미터 — 설정으로 조정한다 (FT-001). */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        String jwtSecret,
        Duration accessTtl,
        Duration refreshTtl,
        String feOrigin,
        boolean cookieSecure,
        Set<String> loginWhitelist) {

    public AuthProperties {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(
                    "auth.jwt-secret은 32바이트 이상이어야 합니다 (HS256). 첫 토큰 발급이 아니라 기동에서 실패하게 한다.");
        }
        // 비교 양쪽을 같은 규칙으로 맞춘다 — 여기서 한 번 정규화해두면 판정부는 입력만 정규화하면 된다.
        loginWhitelist = loginWhitelist == null ? Set.of()
                : loginWhitelist.stream()
                        .map(email -> email.trim().toLowerCase(Locale.ROOT))
                        .filter(email -> !email.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
    }
}
```

- [ ] **Step 4: 기존 테스트 3곳의 생성자 호출 수정**

세 파일 모두 `new AuthProperties(...)`의 마지막 인자로 `Set.of()`를 추가한다.

`be/src/test/java/com/ymc/user/infra/security/JwtTokenProviderTest.java:21` 부근 — 기존:

```java
    private final AuthProperties props = new AuthProperties(
```

이 호출의 인자 목록 끝(`false` 뒤)에 `, Set.of()`를 추가하고 `import java.util.Set;`를 넣는다.

`OAuthLoginFailureHandlerTest.java:18`도 동일:

```java
        AuthProperties props = new AuthProperties(
                "test-secret-key-that-is-32-bytes-long!!",
                Duration.ofMinutes(30), Duration.ofDays(14), "http://localhost:5173", false,
                Set.of());
```

`OAuthLoginSuccessHandlerTest.java:39`도 동일하게 `, Set.of()` 추가 + `import java.util.Set;`.

- [ ] **Step 5: application.yml 설정 추가**

기본 블록(`auth:` 아래, `cookie-secure: false` 다음 줄)에 추가:

```yaml
  # 허용 이메일 명단. 비어 있으면 게이트 off — 로컬·prod 기본 동작이다.
  login-whitelist: ""
```

파일 맨 아래 dev 전용 블록(`on-profile: dev`)의 `spring:` 블록 **뒤**에 추가:

```yaml
auth:
  # 기본값을 두지 않는다 — 미설정이면 첫 로그인이 아니라 기동에서 실패해야 한다.
  login-whitelist: ${LOGIN_WHITELIST}
```

- [ ] **Step 6: 테스트 통과 확인**

```bash
cd be && ./gradlew test --tests 'com.ymc.common.config.AuthPropertiesTest' --tests 'com.ymc.user.infra.security.*'
```

Expected: PASS (Docker 필요 — `JwtTokenProviderTest` 등은 순수 단위 테스트라 컨테이너 없이 통과한다)

- [ ] **Step 7: 커밋**

```bash
git add be/src/main/java/com/ymc/common/config/AuthProperties.java be/src/main/resources/application.yml be/src/test/java/com/ymc/common/config/AuthPropertiesTest.java be/src/test/java/com/ymc/user/infra/security/
git commit -m "[YMC-310] feat(be): auth.login-whitelist 설정과 정규화 추가"
```

---

### Task 2: `LoginWhitelist` 판정 로직

**Files:**
- Create: `be/src/main/java/com/ymc/user/service/LoginWhitelist.java`
- Test: `be/src/test/java/com/ymc/user/service/LoginWhitelistTest.java` (신규)

**Interfaces:**
- Consumes: `AuthProperties.loginWhitelist()` → `Set<String>` (Task 1)
- Produces: `LoginWhitelist.isAllowed(String email)` → `boolean`. 생성자는 `LoginWhitelist(AuthProperties props)`

- [ ] **Step 1: 실패 테스트 작성**

`be/src/test/java/com/ymc/user/service/LoginWhitelistTest.java` 생성:

```java
package com.ymc.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ymc.common.config.AuthProperties;

class LoginWhitelistTest {

    private static LoginWhitelist with(Set<String> whitelist) {
        return new LoginWhitelist(new AuthProperties(
                "test-secret-key-that-is-32-bytes-long!!",
                Duration.ofMinutes(30), Duration.ofDays(14), "http://localhost:5173", false,
                whitelist));
    }

    @Test
    void 명단이_비면_모두_허용된다() {
        LoginWhitelist whitelist = with(Set.of());
        assertThat(whitelist.isAllowed("아무나@x.com")).isTrue();
        assertThat(whitelist.isAllowed(null)).isTrue();
    }

    @Test
    void 명단에_있으면_허용된다() {
        assertThat(with(Set.of("a@x.com")).isAllowed("a@x.com")).isTrue();
    }

    @Test
    void 명단에_없으면_거부된다() {
        assertThat(with(Set.of("a@x.com")).isAllowed("b@x.com")).isFalse();
    }

    @Test
    void 대소문자와_앞뒤_공백을_무시한다() {
        LoginWhitelist whitelist = with(Set.of("a@x.com"));
        assertThat(whitelist.isAllowed("  A@X.CoM ")).isTrue();
    }

    @Test
    void 명단이_있는데_email이_null이면_거부된다() {
        assertThat(with(Set.of("a@x.com")).isAllowed(null)).isFalse();
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
cd be && ./gradlew test --tests 'com.ymc.user.service.LoginWhitelistTest'
```

Expected: FAIL — `cannot find symbol: class LoginWhitelist`

- [ ] **Step 3: 구현**

`be/src/main/java/com/ymc/user/service/LoginWhitelist.java` 생성:

```java
package com.ymc.user.service;

import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.ymc.common.config.AuthProperties;

import lombok.RequiredArgsConstructor;

/**
 * 로그인 허용 여부 판정. 별도 회원가입 플로우가 없어 가입 = 첫 로그인이므로,
 * 여기서 막으면 신규 가입과 기존 계정 재로그인이 함께 막힌다.
 */
@Component
@RequiredArgsConstructor
public class LoginWhitelist {

    private final AuthProperties props;

    /** 목록이 비면 게이트가 꺼진 것으로 본다 — 설정을 넣지 않은 환경의 기본 동작이다. */
    public boolean isAllowed(String email) {
        Set<String> allowed = props.loginWhitelist();
        return allowed.isEmpty()
                || (email != null && allowed.contains(email.trim().toLowerCase(Locale.ROOT)));
    }
}
```

- [ ] **Step 4: 통과 확인**

```bash
cd be && ./gradlew test --tests 'com.ymc.user.service.LoginWhitelistTest'
```

Expected: PASS (5개)

- [ ] **Step 5: 커밋**

```bash
git add be/src/main/java/com/ymc/user/service/LoginWhitelist.java be/src/test/java/com/ymc/user/service/LoginWhitelistTest.java
git commit -m "[YMC-310] feat(be): 로그인 허용 명단 판정 LoginWhitelist 추가"
```

---

### Task 3: `WhitelistedOidcUserService` — OIDC 배선

**Files:**
- Create: `be/src/main/java/com/ymc/user/infra/security/WhitelistedOidcUserService.java`
- Test: `be/src/test/java/com/ymc/user/infra/security/WhitelistedOidcUserServiceTest.java` (신규)

**Interfaces:**
- Consumes: `LoginWhitelist.isAllowed(String)` → `boolean` (Task 2), `AuthProperties.loginWhitelist()` → `Set<String>` (Task 1, 기동 로그의 건수용)
- Produces:
  - `WhitelistedOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser>`
  - `WhitelistedOidcUserService.NOT_ALLOWED_CODE` — package-private `static final String` = `"not_allowed"` (Task 4가 참조)
  - 공개 생성자 `WhitelistedOidcUserService(LoginWhitelist, AuthProperties)`, 테스트용 package-private 생성자 `WhitelistedOidcUserService(OAuth2UserService<OidcUserRequest, OidcUser>, LoginWhitelist)`

> **기동 로그를 넣는 이유:** 설정만 채우고 배선을 빠뜨리면 게이트가 조용히 죽는다 — 외부 리뷰가 정확히 이 위험을 지적했다. 기동 로그로 dev 배포 직후 상태를 눈으로 확인할 수 있게 한다. 다만 로그는 빈 생성만 증명하지 배선을 증명하지 않으므로, Task 7의 비허용 계정 차단 확인이 여전히 유일한 최종 검증이다. 이메일은 로그에 남기지 않고 건수만 남긴다.

- [ ] **Step 1: 실패 테스트 작성**

`be/src/test/java/com/ymc/user/infra/security/WhitelistedOidcUserServiceTest.java` 생성. delegate를 람다로 넣어 Google HTTP 호출 없이 검증한다.

```java
package com.ymc.user.infra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

import com.ymc.common.config.AuthProperties;
import com.ymc.user.service.LoginWhitelist;

class WhitelistedOidcUserServiceTest {

    private static OidcUser userWithEmail(String email) {
        OidcIdToken idToken = new OidcIdToken(
                "token-value", Instant.now(), Instant.now().plusSeconds(60),
                Map.of(IdTokenClaimNames.SUB, "sub-1", "email", email));
        // authorities는 null 대신 비어있지 않은 목록으로 준다 — 생성자 계약을 건드리지 않는다.
        return new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), idToken);
    }

    private static WhitelistedOidcUserService serviceWith(Set<String> whitelist, OidcUser loaded) {
        LoginWhitelist gate = new LoginWhitelist(new AuthProperties(
                "test-secret-key-that-is-32-bytes-long!!",
                Duration.ofMinutes(30), Duration.ofDays(14), "http://localhost:5173", false,
                whitelist));
        return new WhitelistedOidcUserService(request -> loaded, gate);
    }

    @Test
    void 허용_계정이면_delegate_결과를_그대로_돌려준다() {
        OidcUser loaded = userWithEmail("a@x.com");
        WhitelistedOidcUserService service = serviceWith(Set.of("a@x.com"), loaded);

        assertThat(service.loadUser(null)).isSameAs(loaded);
    }

    @Test
    void 명단이_비면_그대로_통과시킨다() {
        OidcUser loaded = userWithEmail("아무나@x.com");
        WhitelistedOidcUserService service = serviceWith(Set.of(), loaded);

        assertThat(service.loadUser(null)).isSameAs(loaded);
    }

    @Test
    void 비허용_계정이면_not_allowed로_거부한다() {
        WhitelistedOidcUserService service =
                serviceWith(Set.of("a@x.com"), userWithEmail("남@x.com"));

        assertThatThrownBy(() -> service.loadUser(null))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(thrown -> assertThat(
                        ((OAuth2AuthenticationException) thrown).getError().getErrorCode())
                        .isEqualTo(WhitelistedOidcUserService.NOT_ALLOWED_CODE));
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
cd be && ./gradlew test --tests 'com.ymc.user.infra.security.WhitelistedOidcUserServiceTest'
```

Expected: FAIL — `cannot find symbol: class WhitelistedOidcUserService`

- [ ] **Step 3: 구현**

`be/src/main/java/com/ymc/user/infra/security/WhitelistedOidcUserService.java` 생성:

```java
package com.ymc.user.infra.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import com.ymc.common.config.AuthProperties;
import com.ymc.user.service.LoginWhitelist;

/**
 * 허용 명단에 없는 계정을 인증 파이프라인 안에서 거부한다. 여기서 던지면
 * {@link OAuthLoginSuccessHandler}까지 가지 않으므로 User 행도 refresh 토큰도 생기지 않는다.
 *
 * <p>Google 등록 스코프에 openid가 있어 스프링은 OAuth2가 아니라 OIDC 컴포넌트를 쓴다.
 * 그래서 이 클래스는 OidcUserRequest/OidcUser 타입이어야 하고 oidcUserService로 등록해야 한다.
 *
 * <p>{@link OidcUserService}를 상속하지 않고 delegate로 갖는 이유는 테스트다 — 상속하면
 * userinfo HTTP 응답을 흉내내야 하지만, 위임이면 OidcUser를 바로 만들어 넘길 수 있다.
 */
@Component
public class WhitelistedOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    static final String NOT_ALLOWED_CODE = "not_allowed";

    private static final Logger log = LoggerFactory.getLogger(WhitelistedOidcUserService.class);

    private static final OAuth2Error NOT_ALLOWED =
            new OAuth2Error(NOT_ALLOWED_CODE, "허용되지 않은 계정입니다.", null);

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final LoginWhitelist whitelist;

    /** 생성자가 둘이라 스프링이 쓸 쪽을 명시해야 한다 — 없으면 후보 모호로 기동에 실패한다. */
    @Autowired
    public WhitelistedOidcUserService(LoginWhitelist whitelist, AuthProperties props) {
        this(new OidcUserService(), whitelist);
        // 설정만 넣고 배선을 빠뜨리면 게이트가 조용히 죽는다. 기동 로그에 상태를 남겨
        // 배포 직후 로그만으로 켜졌는지 확인할 수 있게 한다. 이메일 자체는 남기지 않는다.
        int size = props.loginWhitelist().size();
        if (size > 0) {
            log.info("로그인 화이트리스트 활성: 허용 {}건", size);
        } else {
            log.info("로그인 화이트리스트 비활성 — 모든 계정 로그인 허용");
        }
    }

    WhitelistedOidcUserService(
            OAuth2UserService<OidcUserRequest, OidcUser> delegate, LoginWhitelist whitelist) {
        this.delegate = delegate;
        this.whitelist = whitelist;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        OidcUser user = delegate.loadUser(request);
        if (!whitelist.isAllowed(user.getEmail())) {
            throw new OAuth2AuthenticationException(NOT_ALLOWED);
        }
        return user;
    }
}
```

- [ ] **Step 4: 통과 확인**

```bash
cd be && ./gradlew test --tests 'com.ymc.user.infra.security.WhitelistedOidcUserServiceTest'
```

Expected: PASS (3개)

- [ ] **Step 5: 커밋**

```bash
git add be/src/main/java/com/ymc/user/infra/security/WhitelistedOidcUserService.java be/src/test/java/com/ymc/user/infra/security/WhitelistedOidcUserServiceTest.java
git commit -m "[YMC-310] feat(be): OIDC userinfo 단계에서 비허용 계정 거부"
```

---

### Task 4: 거부 코드를 FE까지 전달

**Files:**
- Modify: `be/src/main/java/com/ymc/user/infra/security/OAuthLoginFailureHandler.java`
- Modify: `be/src/test/java/com/ymc/user/infra/security/OAuthLoginFailureHandlerTest.java`

**Interfaces:**
- Consumes: `WhitelistedOidcUserService.NOT_ALLOWED_CODE` (Task 3)
- Produces: 리다이렉트 URL의 `?error=not_allowed` (비허용) 또는 `?error=oauth_failed` (그 외). Task 5가 이 두 값을 소비한다.

- [ ] **Step 1: 실패 테스트 추가**

`OAuthLoginFailureHandlerTest.java`의 기존 테스트는 그대로 두고, 아래 두 테스트를 클래스 안에 추가한다. 상단에 import를 추가한다:

```java
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
```

```java
    @Test
    void 비허용_계정_거부는_not_allowed로_전달한다() throws Exception {
        AuthProperties props = new AuthProperties(
                "test-secret-key-that-is-32-bytes-long!!",
                Duration.ofMinutes(30), Duration.ofDays(14), "http://localhost:5173", false,
                Set.of());
        OAuthLoginFailureHandler handler = new OAuthLoginFailureHandler(props);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("not_allowed", "거부", null)));

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/auth/popup-done.html?error=not_allowed");
    }

    @Test
    void 그_외_OAuth2_오류코드는_URL로_새지_않는다() throws Exception {
        AuthProperties props = new AuthProperties(
                "test-secret-key-that-is-32-bytes-long!!",
                Duration.ofMinutes(30), Duration.ofDays(14), "http://localhost:5173", false,
                Set.of());
        OAuthLoginFailureHandler handler = new OAuthLoginFailureHandler(props);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(
                        new OAuth2Error("invalid_token_response", "내부 사정", null)));

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/auth/popup-done.html?error=oauth_failed");
    }
```

- [ ] **Step 2: 실패 확인**

```bash
cd be && ./gradlew test --tests 'com.ymc.user.infra.security.OAuthLoginFailureHandlerTest'
```

Expected: FAIL — `비허용_계정_거부는_not_allowed로_전달한다`가 `...?error=oauth_failed`를 받아 기대값과 다르다

- [ ] **Step 3: 구현**

`OAuthLoginFailureHandler.java`의 `onAuthenticationFailure` 본문을 교체하고 import를 추가한다:

```java
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
```

```java
    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        // 우리가 정의한 코드만 통과시킨다. 스프링이 만든 오류 코드는 내부 사정을 담을 수 있어
        // URL로 흘리지 않고 일반 실패로 뭉갠다.
        String code = exception instanceof OAuth2AuthenticationException oauthFailure
                && WhitelistedOidcUserService.NOT_ALLOWED_CODE.equals(
                        oauthFailure.getError().getErrorCode())
                ? WhitelistedOidcUserService.NOT_ALLOWED_CODE
                : "oauth_failed";
        response.sendRedirect(props.feOrigin() + "/auth/popup-done.html?error=" + code);
    }
```

- [ ] **Step 4: 통과 확인**

```bash
cd be && ./gradlew test --tests 'com.ymc.user.infra.security.OAuthLoginFailureHandlerTest'
```

Expected: PASS (3개 — 기존 1개 + 신규 2개)

- [ ] **Step 5: 커밋**

```bash
git add be/src/main/java/com/ymc/user/infra/security/OAuthLoginFailureHandler.java be/src/test/java/com/ymc/user/infra/security/OAuthLoginFailureHandlerTest.java
git commit -m "[YMC-310] feat(be): 거부 사유를 not_allowed로 구분해 전달"
```

---

### Task 5: SecurityConfig 배선

**Files:**
- Modify: `be/src/main/java/com/ymc/user/infra/security/SecurityConfig.java:41-54`

**Interfaces:**
- Consumes: `WhitelistedOidcUserService` 빈 (Task 3)
- Produces: 없음 (배선만)

> **왜 통합 테스트가 없는가:** 이 배선이 실제로 물렸는지는 Google 핸드셰이크를 타야 확인된다. 기존 테스트도 이 구간을 덮지 않는다. 배선이 틀려도 **로그인은 그냥 성공**하므로 Task 7의 dev 확인에서 **비허용 계정이 막히는지**를 반드시 봐야 한다.

- [ ] **Step 1: `oauthLoginChain` 수정**

`SecurityConfig.java`의 `oauthLoginChain` 메서드 시그니처에 파라미터를 추가하고 `oauth2Login`에 한 줄을 넣는다. 메서드 전체를 아래로 교체:

```java
    @Bean
    @Order(1)
    SecurityFilterChain oauthLoginChain(HttpSecurity http,
            OAuthLoginSuccessHandler successHandler,
            OAuthLoginFailureHandler failureHandler,
            WhitelistedOidcUserService whitelistedOidcUserService) throws Exception {
        http
                .securityMatcher("/api/oauth2/**", "/api/login/oauth2/**")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .oauth2Login(oauth2 -> oauth2
                        // 스코프에 openid가 있어 OIDC 경로를 탄다 — userService가 아니라 oidcUserService다.
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(whitelistedOidcUserService))
                        .authorizationEndpoint(a -> a.baseUri("/api/oauth2/authorization"))
                        .redirectionEndpoint(r -> r.baseUri("/api/login/oauth2/code/*"))
                        .successHandler(successHandler)
                        .failureHandler(failureHandler));
        return http.build();
    }
```

- [ ] **Step 2: 전체 스위트 실행**

Docker Desktop이 떠 있어야 한다 (Testcontainers).

```bash
cd be && ./gradlew test
```

Expected: BUILD SUCCESSFUL, 0 failed. 컨텍스트가 뜨는 것 자체가 빈 배선(생성자 모호성 포함)이 성립한다는 확인이다.

- [ ] **Step 3: 커밋**

```bash
git add be/src/main/java/com/ymc/user/infra/security/SecurityConfig.java
git commit -m "[YMC-310] feat(be): oidcUserService에 화이트리스트 게이트 배선"
```

---

### Task 6: FE 거부 문구 분기

**Files:**
- Modify: `fe/src/auth/AuthContext.tsx` (2곳 — 전체 리다이렉트 복귀 경로와 팝업 `onComplete` 경로)

**Interfaces:**
- Consumes: `?error=not_allowed` / `?error=oauth_failed` (Task 4)
- Produces: 없음

> **주의:** 문구를 세팅하는 곳이 **두 군데**다. `useEffect` 안의 `params.get('error')` 경로(팝업이 닫히지 않고 전체 리다이렉트로 돌아온 폴백)와 `startLogin`의 `onComplete` 경로. 한쪽만 고치면 절반의 경우에 기존 문구가 그대로 나온다.

- [ ] **Step 1: 메시지 변환 함수 추가**

`AuthContext.tsx`에서 `AuthProvider` 컴포넌트 **바깥**(import 아래)에 추가:

```ts
/** BE가 popup-done.html?error=로 실어 보낸 코드를 사용자 문구로 바꾼다. */
function loginErrorMessage(code: string): string {
  return code === 'not_allowed'
    ? '허용되지 않은 계정입니다. 관리자에게 문의해 주세요.'
    : '로그인에 실패했습니다. 다시 시도해 주세요.';
}
```

- [ ] **Step 2: 두 호출부 교체**

`useEffect` 안 (기존 `if (params.get('error')) { ... }` 블록):

```ts
    const errorCode = params.get('error');
    if (errorCode) {
      window.history.replaceState(null, '', '/');
      setInitialError(loginErrorMessage(errorCode));
    }
```

`startLogin`의 `onComplete` 안:

```ts
        if (user) setState({ status: 'authed', user });
        else if (error) setInitialError(loginErrorMessage(error));
```

- [ ] **Step 3: 타입 체크와 테스트**

```bash
cd fe && npx tsc --noEmit && npm test
```

Expected: 타입 에러 없음, 기존 테스트 전부 통과

- [ ] **Step 4: 커밋**

```bash
git add fe/src/auth/AuthContext.tsx
git commit -m "[YMC-310] feat(fe): 허용되지 않은 계정 거부 문구 분기"
```

---

### Task 7: infra 배선과 dev 검증

**Files:**
- Modify: `infra/deploy/dev/variables.tf`
- Modify: `infra/deploy/dev/terraform.tfvars`
- Modify: `infra/deploy/dev/main.tf`
- Modify: `infra/deploy/modules/environment/variables.tf`
- Modify: `infra/deploy/modules/environment/main.tf:223` 부근 (백엔드 태스크 환경변수 블록)

**Interfaces:**
- Consumes: BE가 읽는 환경변수 이름 `LOGIN_WHITELIST` (Task 1)
- Produces: 없음

> **infra는 별도 repo다.** `app`이 아니라 `infra`에서 커밋한다. `app` PR이 머지되고 새 이미지 태그가 나온 뒤에 적용한다.

- [ ] **Step 1: 모듈 변수 선언**

`infra/deploy/modules/environment/variables.tf`에 추가. **default를 두지 않는다** — 값이 없으면 `terraform apply`가 먼저 실패해야 한다.

```hcl
variable "login_whitelist" {
  description = "로그인 허용 이메일 명단 (콤마 구분). 빈 문자열이면 게이트가 꺼진다."
  type        = string
}
```

- [ ] **Step 2: 태스크 환경변수 배선**

`infra/deploy/modules/environment/main.tf`에서 백엔드 컨테이너의 환경변수 맵(`SPRING_PROFILES_ACTIVE = var.environment`가 있는 블록, 223행 부근)에 한 줄 추가:

```hcl
    LOGIN_WHITELIST = var.login_whitelist
```

- [ ] **Step 3: dev 루트에서 값 전달**

`infra/deploy/dev/variables.tf`에 추가:

```hcl
variable "login_whitelist" {
  description = "dev 로그인 허용 이메일 명단 (콤마 구분)"
  type        = string
}
```

`infra/deploy/dev/main.tf`의 environment 모듈 호출에서 `backend_image_tag = var.backend_image_tag` 아래에 추가:

```hcl
  login_whitelist   = var.login_whitelist
```

`infra/deploy/dev/terraform.tfvars`의 `backend_image_tag` 줄 아래에 팀원 이메일을 넣어 추가한다. **실제 팀원 이메일로 채울 것** — 예시값을 그대로 두면 아무도 로그인하지 못한다:

```hcl
login_whitelist = "팀원1@example.com, 팀원2@example.com"
```

`terraform.tfvars.example`에도 자리표시 값으로 추가:

```hcl
login_whitelist = "someone@example.com"
```

- [ ] **Step 4: plan으로 확인**

```bash
cd infra/deploy/dev && terraform plan
```

Expected: 백엔드 태스크 정의에 `LOGIN_WHITELIST` 환경변수가 추가되는 변경만 나온다. `login_whitelist` 값을 tfvars에서 지우고 다시 돌리면 값을 묻거나 실패하는지도 한 번 확인한다.

- [ ] **Step 5: infra 커밋**

```bash
cd infra && git add deploy/ && git commit -m "[YMC-310] feat(deploy): dev 백엔드에 LOGIN_WHITELIST 전달"
```

- [ ] **Step 6: dev 배포 후 확인 — 이것이 진짜 검증이다**

새 이미지 태그로 `backend_image_tag`를 올리고 apply한 뒤, 브라우저에서 두 계정으로 각각 로그인한다.

| 확인 | 기대 |
|---|---|
| 명단에 **있는** 계정으로 로그인 | 성공, 서재 진입 |
| 명단에 **없는** 계정으로 로그인 | 실패, "허용되지 않은 계정입니다. 관리자에게 문의해 주세요." |
| 위 실패 후 DB 확인 | `users` 테이블에 해당 계정 행이 **생기지 않았다** |

DB 확인 쿼리:

```sql
SELECT email, created_at FROM users ORDER BY created_at DESC LIMIT 5;
```

**허용 계정 성공만 보고 끝내지 말 것.** 게이트 배선이 틀려도 허용 계정은 성공한다 — 비허용 계정 차단이 확인돼야 게이트가 실제로 걸린 것이다.

---

## 실행 순서 요약

1. Task 1–5 (BE) → 6 (FE): `app` repo, `YMC-310-dev-login-whitelist` 브랜치
2. `app` PR 생성·머지 → 새 이미지 태그 확보
3. Task 7: `infra` repo, 새 브랜치
4. dev 배포 후 Task 7 Step 6 확인
