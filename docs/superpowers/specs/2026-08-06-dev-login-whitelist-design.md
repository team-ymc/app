# dev 로그인 화이트리스트 — 설계

- Jira: YMC-310
- 관련: FT-001 소셜 인증 / `infra/deploy/modules/environment` (dev·prod 공용 모듈)
- 계약 영향: 없음 — OAuth 핸드셰이크는 Spring Security 소유 경로라 `openapi.yaml`에 스키마가 없다

## 1. 목적과 범위

dev 서버가 공개 도메인(`dev.papertutor.co.kr`)에 열려 있는데 Google 로그인만 통과하면
`OAuthUserService.upsert`가 무조건 User를 만든다. 게이트가 없다.

허용 이메일 명단에 없는 계정의 **로그인 자체를 차단**한다. 이 서비스엔 별도 회원가입 플로우가
없어 가입 = 첫 로그인이므로, 로그인을 막으면 가입도 함께 막힌다. 이미 dev DB에 들어온 계정도
명단에 없으면 다음 로그인부터 잠긴다.

- In: BE 게이트 + dev 설정 + FE 거부 문구
- Out: 명단 관리 UI·API, prod 초대제, DB 기반 명단, 도메인 단위 허용

## 2. 핵심 제약 — dev·prod가 같은 이미지다

`infra/deploy/modules/environment/main.tf`를 dev·prod가 공유한다.

- `main.tf:207` — 이미지는 `ecr/backend:${backend_image_tag}` (커밋 SHA, ECR은 `IMMUTABLE`)
- `main.tf:223` — `SPRING_PROFILES_ACTIVE = var.environment`

같은 이미지를 승격시키고 프로필·환경변수로만 갈라진다. 따라서 게이트는 **설정으로 켜고 끄며,
Java 코드엔 프로필 분기를 넣지 않는다.**

## 3. 설정

기본(로컬·테스트) 블록은 빈 값 → 게이트 off. dev 블록만 기본값 없는 플레이스홀더를 둔다 —
prod의 `jwt-secret: ${AUTH_JWT_SECRET}`와 같은 패턴으로, 미설정 시 첫 로그인이 아니라
**기동에서 실패**한다. prod 블록엔 적지 않으므로 빈 목록 → off.

```yaml
auth:
  login-whitelist: ""                    # 기본 블록

---
# dev 블록
auth:
  login-whitelist: ${LOGIN_WHITELIST}    # 기본값 없음 → 미설정 시 기동 실패
```

`LOGIN_WHITELIST=a@x.com, b@y.com` 콤마 구분으로 바인딩된다. 값은 tfvars → ECS 태스크 환경변수로
전달한다. 명단 변경은 태스크 재기동이 필요하다 (팀 규모에서 변경 빈도가 낮아 감수한다).

**플레이스홀더가 막아주는 범위:** 환경변수가 **없으면** 해석 실패로 기동이 막히지만,
`LOGIN_WHITELIST=""`처럼 **빈 값으로 있으면** 해석에 성공해 빈 목록 → 게이트 off로 조용히 뜬다.
이 구멍은 Terraform 쪽에서 막는다 — `infra/deploy/dev/variables.tf`의 `login_whitelist`를
default 없이 선언하면 값이 없을 때 `terraform apply`가 먼저 실패한다. 빈 문자열을 명시적으로
넣는 것까지는 막지 않으며, 그건 의도된 비활성화로 본다.

`AuthProperties`에 컴포넌트를 추가하고 기존 `jwtSecret` 검증과 같은 자리에서 정규화한다.

```java
public record AuthProperties(
        String jwtSecret, Duration accessTtl, Duration refreshTtl,
        String feOrigin, boolean cookieSecure, Set<String> loginWhitelist) {

    public AuthProperties {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(/* 기존 메시지 유지 */);
        }
        loginWhitelist = loginWhitelist == null ? Set.of()
                : loginWhitelist.stream()
                        .map(e -> e.trim().toLowerCase(Locale.ROOT))
                        .filter(e -> !e.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
    }
}
```

## 4. 게이트

정책은 `service/`, 스프링 배선은 `infra/` (be/CLAUDE.md 의존성 규칙).

### 4.1 정책 — `user/service/LoginWhitelist`

```java
@Component
@RequiredArgsConstructor
public class LoginWhitelist {
    private final AuthProperties props;

    /** 목록이 비면 게이트 off — prod·로컬 기본 동작. */
    public boolean isAllowed(String email) {
        Set<String> allowed = props.loginWhitelist();
        return allowed.isEmpty()
                || (email != null && allowed.contains(email.trim().toLowerCase(Locale.ROOT)));
    }
}
```

### 4.2 배선 — `user/infra/security/WhitelistedOidcUserService`

**OIDC 경로여야 한다.** `application.yml`의 Google 등록이 `scope: [openid, email, profile]`이라
스프링이 `DefaultOAuth2UserService`가 아니라 `OidcUserService`를 쓴다 (레퍼런스 6.5,
servlet/oauth2: openid 스코프가 있으면 OIDC 컴포넌트, 없으면 OAuth2 컴포넌트). 따라서
`OAuth2UserService<OidcUserRequest, OidcUser>`를 구현하고 `.oidcUserService(...)`로 등록한다 —
`.userService(...)`에 걸면 **호출되지 않아 게이트가 죽은 코드가 된다.**

`OidcUserService`를 **상속하지 않고 delegate로 주입**한다. 상속해도
`setRestOperations`로 HTTP를 목으로 바꿔 테스트할 수는 있지만, 그러면 테스트가 userinfo JSON
응답을 흉내내야 한다. 위임이면 seam이 인터페이스 자체라 `OidcUser`를 바로 만들어 넘기면 된다.
`FileStorage`·`AiAgentStreamPort`가 이미 쓰는 방식과 같다.

```java
@Component
public class WhitelistedOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    static final String NOT_ALLOWED_CODE = "not_allowed";
    private static final OAuth2Error NOT_ALLOWED =
            new OAuth2Error(NOT_ALLOWED_CODE, "허용되지 않은 계정입니다.", null);

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final LoginWhitelist whitelist;

    /** 생성자가 둘이라 스프링이 쓸 쪽을 명시해야 한다 — 없으면 후보 모호로 기동 실패한다. */
    @Autowired
    public WhitelistedOidcUserService(LoginWhitelist whitelist) {
        this(new OidcUserService(), whitelist);
    }

    WhitelistedOidcUserService(
            OAuth2UserService<OidcUserRequest, OidcUser> delegate, LoginWhitelist whitelist) {
        this.delegate = delegate;
        this.whitelist = whitelist;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        OidcUser user = delegate.loadUser(request);
        if (!whitelist.isAllowed(user.getEmail())) {   // OidcUser는 표준 클레임 접근자를 갖는다
            throw new OAuth2AuthenticationException(NOT_ALLOWED);
        }
        return user;
    }
}
```

여기서 던지면 `OAuthLoginSuccessHandler`까지 가지 않으므로 **User 행도 refresh 토큰도 생기지 않는다.**

`OidcUser`는 `OAuth2User`를 상속하므로 성공 핸들러의 기존 캐스팅·`getName()`은 그대로 동작한다
(지금도 이미 OIDC 경로로 돌고 있다).

### 4.3 SecurityConfig

`oauthLoginChain`의 `oauth2Login`에 한 줄 추가한다. 나머지는 그대로.

```java
.oauth2Login(oauth2 -> oauth2
        .userInfoEndpoint(u -> u.oidcUserService(whitelistedOidcUserService))
        .authorizationEndpoint(a -> a.baseUri("/api/oauth2/authorization"))
        .redirectionEndpoint(r -> r.baseUri("/api/login/oauth2/code/*"))
        .successHandler(successHandler)
        .failureHandler(failureHandler));
```

**검증 함정:** 배선이 틀려도 로그인은 그냥 성공한다 (게이트만 안 걸릴 뿐). 그래서 dev 확인 시
**비허용 계정이 실제로 막히는지**를 반드시 봐야 한다 — 허용 계정 성공만으로는 아무것도 증명 못 한다.

## 5. 거부 경로

스프링이 `OAuth2AuthenticationException`을 기존 `OAuthLoginFailureHandler`로 보낸다.
지금은 무조건 `?error=oauth_failed`인데, 우리 코드만 통과시키고 나머지는 뭉갠다 —
스프링 내부 오류 코드가 URL로 새는 것을 막는다.

```java
String code = exception instanceof OAuth2AuthenticationException e
        && WhitelistedOidcUserService.NOT_ALLOWED_CODE.equals(e.getError().getErrorCode())
        ? WhitelistedOidcUserService.NOT_ALLOWED_CODE
        : "oauth_failed";
response.sendRedirect(props.feOrigin() + "/auth/popup-done.html?error=" + code);
```

`popup-done.html`은 `error` 쿼리를 그대로 실어 보내므로 수정하지 않는다.

FE는 `auth/AuthContext.tsx:41`이 error 코드와 무관하게 "로그인에 실패했습니다"로 고정이다.
`not_allowed`만 분기한다.

```ts
else if (error)
  setInitialError(error === 'not_allowed'
    ? '허용되지 않은 계정입니다. 관리자에게 문의해 주세요.'
    : '로그인에 실패했습니다. 다시 시도해 주세요.');
```

## 6. 테스트

| 대상 | 방식 | 케이스 |
|---|---|---|
| `AuthProperties` 정규화 | 단위 | 대문자·앞뒤 공백·빈 항목 제거, null → `Set.of()` |
| `LoginWhitelist` | 단위 | 빈 목록이면 전부 통과 / 일치 / 불일치 / email null / 대소문자·공백 무시 |
| `WhitelistedOidcUserService` | 단위 (delegate 주입) | 허용 시 delegate 결과 그대로 반환 / 거부 시 `OAuth2AuthenticationException`에 `not_allowed` |
| `OAuthLoginFailureHandler` | 단위 | `not_allowed`는 그대로, 그 외 예외는 `oauth_failed`로 뭉갬 |

`OAuth2UserService`는 메서드가 하나뿐이라 delegate를 람다로 넣으면 HTTP 없이 끝난다.

**덮지 못하는 것:** Google 실제 핸드셰이크. 기존 테스트도 이 구간은 덮지 않는다
(`OAuthUserServiceIntegrationTest`는 `upsert`만). 배선이 실제로 물렸는지는 dev 배포 후
허용 계정·비허용 계정으로 각각 로그인해 확인한다.

## 7. 배포 순서

1. BE·FE 머지
2. `infra/deploy/dev/variables.tf`에 `login_whitelist`를 default 없이 선언, `terraform.tfvars`에
   값 추가, `modules/environment`의 태스크 환경변수에 `LOGIN_WHITELIST` 배선
3. 새 이미지 태그로 dev 배포 — 값을 빠뜨리면 `terraform apply`에서, 환경변수 배선을 빠뜨리면
   컨테이너 기동에서 각각 막힌다
4. 허용 계정 로그인 성공 + **비허용 계정 차단** 둘 다 확인 (후자가 진짜 검증이다)

## 8. 기각한 대안

| 대안 | 기각 이유 |
|---|---|
| 이메일 도메인 허용 | 팀이 개인 gmail을 쓰면 무용지물 |
| Google sub(providerId) 명단 | 사람이 값을 미리 알 수 없어 한 번 로그인해봐야 함 |
| DB 테이블 명단 | 재배포 없이 추가되지만 마이그레이션·관리 수단이 늘고 prod에 빈 테이블이 생긴다 |
| Google Console 테스트 사용자 | 코드는 0줄이지만 dev·prod가 OAuth 클라이언트를 공유하면 prod까지 막힌다 |
| 빈 목록 = 게이트 off만으로 처리 | dev에서 환경변수 이름 오타 시 조용히 열린 채 뜬다 (fail-open) |
| `@Profile`로 빈 분기 | prod엔 게이트 코드가 없는 셈이라 기본 프로필 테스트로 검증 불가, 빈이 둘로 늘어남 |
| `OidcUserService` 상속 | 테스트가 불가능하진 않다 (`setRestOperations`로 HTTP 목 주입 가능). 다만 userinfo JSON을 흉내내야 해 위임보다 seam이 낮다 |
| 신규 가입만 차단 | 이미 dev DB에 들어온 계정이 계속 접근 가능 |
