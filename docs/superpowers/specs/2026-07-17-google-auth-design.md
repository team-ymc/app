# DESIGN — Google 소셜 인증 (FE + BE)

Source: project-docs/features/FT-001-소셜-인증.md
Wireframes: project-docs/wireframes/frames/WF-001·WF-002·WF-003
날짜: 2026-07-17 (브레인스토밍 세션 확정본, v2 — 팝업 로그인·와이어프레임 반영)

## 1. 개요

사용자가 Google 계정으로 로그인/회원가입하고, 세션을 유지하며, 로그아웃할 수 있게 한다.
BE(Spring Boot)와 FE(기존 Vite + React 앱) 양쪽을 이번 change에서 함께 구현한다.

### Goals

- Google 로그인 → 사용자 upsert(`provider + providerId`) → 세션 발급이 실제로 동작
- 새로고침/재방문 시 세션 유지
- 로그아웃 시 refresh token 즉시 무효화 + FE의 access token 폐기
  (이미 발급된 access JWT는 회수 불가 — 만료까지 최대 30분 유효. 수용된 트레이드오프, 아래 §3)
- 랜딩·인증 모달을 와이어프레임(WF-001/002/003) 기준으로 구현 — 로그인 중 랜딩을 떠나지 않는 팝업 방식
- 기존 papers API를 인증 필수로 보호(401), Paper 생성 시 `ownerId` 기록
- FT-001의 Open Question이던 세션 방식 확정: **JWT access + HttpOnly refresh 쿠키**

### Non-Goals (후속 작업)

- 소유권 필터링("내 것만 보임") — FT-002 서재 소유. 이번엔 ownerId **기록만**
- 시각 디자인(시안) 폴리싱 — 와이어프레임 수준까지만. 스타일 확정은 디자인 나온 뒤
- Kakao·Naver **동작** — 버튼은 WF-003대로 노출하되 비활성("준비 중"). 실제 연동은 post-MVP
- 계정 병합, 약관 동의 플로우(문구 표시만), 권한/역할 — FT-001 out of scope 그대로
- OAuth 실패/취소의 복구 플로우 — 에러 문구 표시까지만
- FE의 TypeScript 전환·react-router 도입 — 표현 층 수명이 불확실한 시점이라 보류

## 2. 확정된 결정 요약

| 결정 | 선택 | 근거 |
|---|---|---|
| 세션 방식 | JWT access(30분) + HttpOnly refresh 쿠키(14일, 회전) | 추후 타 서비스의 토큰 검증 확장 대비. 만료값은 관행적 기본값이며 설정으로 조정 가능 |
| OAuth 플로우 | BE 주도 (authorization code, Spring oauth2Login) | state 검증·code 교환을 검증된 구현에 위임. Kakao·Naver 추가가 설정으로 끝남 |
| 로그인 UX | 모달에서 **팝업 창**으로 Google 인증, 팝업 차단 시 전체 리다이렉트 폴백 | 랜딩을 떠나지 않음 (WF-003 모달 맥락 유지) |
| access 전달 | 콜백은 refresh 쿠키만 심음 → FE가 `POST /api/auth/refresh`로 access 수령, 메모리 보관 | XSS에 안전, URL/스토리지에 토큰 노출 없음 |
| FE 전략 | 기존 Vite + React 앱에 auth 층 + 와이어프레임 화면 추가 (Next.js 철회) | 시안 미확정 → API·auth 로직 층에 투자, 화면은 와이어프레임 수준 |
| 타 provider 버튼 | WF-003대로 노출, Kakao·Naver는 disabled + "준비 중" | 와이어프레임 충실 + 후속 추가 시 UI 변경 없음 |
| papers 스코프 | 인증 필수(401) + ownerId 기록. 필터링은 FT-002 | feature 경계 일치, 데이터는 미리 축적 |

## 3. 아키텍처 & 인증 플로우

```
브라우저 ── Vite dev server (fe, :5173)
              │  /api proxy (기존 설정 그대로)
              ▼
          Spring Boot (be, :8080)
              │  oauth2Login (code 교환·검증)
              ▼
           Google OAuth ─── users·refresh_tokens (PostgreSQL)
```

### 로그인 (신규/기존 동일, 팝업 방식)

1. 랜딩(WF-001)에서 "로그인/회원가입" → 인증 모달(WF-003) 오픈
2. "Google 계속하기" 클릭 → `window.open('/api/oauth2/authorization/google', ...)` 팝업.
   `window.open`이 null(차단)이면 `location.href`로 전체 리다이렉트 폴백
3. 팝업 안에서: Spring Security가 state 생성 → Google 동의 화면 → 콜백 `/api/login/oauth2/code/google`
   — 프레임워크가 code 교환·검증 후 성공 핸들러 실행:
   - `provider='google' + providerId(sub)`로 users 조회, 없으면 생성 (Story 2)
   - refresh token 발급 → DB에 SHA-256 해시 저장 + `HttpOnly; Secure; SameSite=Lax; Path=/api/auth` 쿠키(`ymc_refresh`)
   - **브릿지 페이지** `{FE_ORIGIN}/auth/popup-done.html`로 302
4. 브릿지 페이지(정적 HTML): `window.opener`가 있으면 `postMessage('auth:complete')` 후 `window.close()`.
   opener가 없으면(리다이렉트 폴백으로 왔을 때) 루트(`/`)로 이동
5. 본창: message 수신(또는 폴백 복귀 후 부트스트랩) → `POST /api/auth/refresh` → access token(JSON) 수령,
   메모리에만 보관 → 모달 닫고 서재(WF-004) 진입
6. 이후 API 호출은 `Authorization: Bearer <access>`. 401 시 refresh 1회 → 원 요청 재시도

### 세션 유지·로그아웃

- 재방문/새로고침: 메모리 토큰이 없으므로 부트스트랩 refresh 시도 → 성공하면 로그인 랜딩(WF-002), 실패(쿠키 없음/만료)면 비로그인 랜딩(WF-001)
- 로그아웃: `POST /api/auth/logout` → **refresh token 즉시 폐기(DB) + 쿠키 삭제**, FE는 메모리의 access 폐기 → 비로그인 전환.
  단, 로그아웃 전에 탈취된 access JWT가 있다면 만료까지(최대 30분) 유효 — 요청마다 블록리스트를 조회하면 stateless의 의미가 없어 수용. 창을 줄이려면 access 만료를 단축(설정값)
- refresh 회전: 호출마다 이전 행 revoke + 새 행 삽입 + 새 쿠키. revoked 토큰 재사용 감지 시 해당 사용자 전체 세션 폐기

## 4. Contract 변경 (project-docs/contracts/openapi.yaml, 0.1.x → 0.1.2)

**코드보다 contract 먼저 — project-docs repo에 별도 PR.**

### 추가 엔드포인트

| 메서드 | 경로 | 설명 |
|---|---|---|
| `POST` | `/api/auth/refresh` | refresh 쿠키로 access 발급·회전. 200: `{ accessToken, expiresIn, user: { userId, email, displayName } }`. 쿠키 없음/만료/재사용: 401 |
| `POST` | `/api/auth/logout` | refresh 무효화 + 쿠키 삭제. 항상 204 (멱등) |
| `GET` | `/api/auth/me` | access로 현재 사용자 조회. 401이면 비로그인 |

### 서술로만 기록 (스키마 없음)

- `GET /api/oauth2/authorization/google`, `GET /api/login/oauth2/code/google` — Spring Security 소유의 브라우저 네비게이션 경로. description 수준으로만
- 성공 시 복귀: `{FE_ORIGIN}/auth/popup-done.html` (브릿지) / 실패 시: `{FE_ORIGIN}/auth/popup-done.html?error=oauth_failed`
- refresh 쿠키 이름·속성은 BE 내부 구현 — 계약에는 "쿠키 기반 refresh + bearer access" 개요만

### 기존 papers 엔드포인트

- `components.securitySchemes`에 `bearerAuth` 추가, 전 엔드포인트에 적용 + `401` 응답 추가
- 요청/응답 본문 무변경 (ownerId는 서버가 토큰에서 추출 — 클라이언트가 보내지 않음)

## 5. BE 컴포넌트

### 의존성 추가 (build.gradle — 둘 다 Spring Boot 공식 스타터)

- `spring-boot-starter-oauth2-client` — **필수.** BE 주도 Google 로그인 그 자체 (code 교환·state 검증 수행)
- `spring-boot-starter-oauth2-resource-server` — 자체 발급 access JWT 검증 (HMAC 대칭키). 직접 필터를 짜는 대신 검증된 표준 구현 사용. 발급은 `NimbusJwtEncoder` — 별도 jjwt 불필요

### `com.ymc.user` 컨텍스트 (be/CLAUDE.md 규칙 준수)

```
com.ymc.user
├── api/
│   ├── AuthController          # POST /auth/refresh · POST /auth/logout · GET /auth/me
│   └── dto/  (TokenResponse, MeResponse)
├── service/
│   ├── AuthService             # refresh 회전·로그아웃 (트랜잭션 경계)
│   └── OAuthUserService        # provider+providerId upsert (성공 핸들러가 호출)
├── domain/
│   ├── User                    # id(UUID)·provider·providerId·email(nullable)·displayName·createdAt
│   │                           #   UNIQUE(provider, providerId)
│   ├── RefreshToken            # id·userId·tokenHash(SHA-256)·expiresAt·revokedAt
│   ├── UserRepository
│   └── RefreshTokenRepository
└── infra/security/
    ├── SecurityConfig           # 필터체인 구성
    ├── OAuthLoginSuccessHandler # upsert → refresh 발급·쿠키 → 브릿지 페이지로 302
    ├── OAuthLoginFailureHandler # 브릿지 페이지 ?error=oauth_failed 302
    ├── JwtTokenProvider         # access JWT 발급 (sub=userId, exp=30m)
    └── RefreshTokenCookie       # ymc_refresh 쿠키 생성·삭제
```

### 보안 규칙 (SecurityConfig)

- `oauth2Login()`: 인증 시작·콜백 전담, 성공/실패 핸들러 연결. 서버 세션 미사용(stateless)
- OAuth 경로에 `/api` prefix 적용 (authorization endpoint base URI 변경) — vite proxy가 그대로 커버하도록
- `oauth2ResourceServer(jwt)`: `/api/**` 보호. `permitAll`: `/api/auth/refresh`·`/api/auth/logout`·actuator health
- CSRF 비활성 — 쿠키 인증 POST(refresh/logout)는 `SameSite=Lax`가 크로스사이트 전송을 차단 (MVP 수준 충분)
- 미인증 401은 커스텀 `AuthenticationEntryPoint`로 기존 `ErrorResponse` JSON 포맷 유지

### refresh 회전·재사용 탐지

- 성공: 기존 행 `revokedAt` 마킹 → 새 행 삽입 → 새 쿠키
- 제시 토큰이 revoked 행과 일치(탈취 신호): 해당 사용자 모든 refresh 폐기 + 401 (응답은 일반 401과 동일 — 감지 사실 비노출, 내부 WARN 로그)
- 만료 행은 조회 시 무시 (스케줄 정리는 MVP 밖)

### Paper 변경 (최소)

- `Paper.ownerId(UUID, nullable)` 추가 — 생성 시 principal의 userId 기록. 기존 행 null 허용
- `PaperController`가 `@AuthenticationPrincipal Jwt`에서 userId 추출해 서비스로 전달 (컨텍스트 간 참조는 ID로 — 규칙 준수)

## 6. FE — 기존 Vite 앱에 auth 층 + 와이어프레임 화면

**건드리지 않는 것**: Vite + React(JS) 유지, TS 전환 없음, 프레임워크 이전 없음, 기존 검증 UI(업로드·폴링·다운로드)는 서재 화면 안에서 유지.

### 새 파일 `fe/src/auth.js` (핵심 산출물 — 디자인 재작업 후에도 승계)

- 모듈 스코프에 access token 보관 (메모리 전용)
- `bootstrap()` — 앱 시작 시 refresh 1회 → `{ user }` 또는 `null`
- `login()` — `window.open('/api/oauth2/authorization/google', ...)`. null 반환(팝업 차단) 시 `location.href` 폴백.
  브릿지의 `postMessage('auth:complete')` 수신 → refresh → 로그인 상태 전환 (origin 검사 필수)
- `logout()` — `POST /api/auth/logout` → 토큰 폐기
- `authFetch(url, opts)` — Authorization 부착. 401 시 refresh 1회(single-flight Promise 공유) 후 재시도, 그래도 401이면 비로그인 전파

### 새 파일 `fe/public/auth/popup-done.html` (정적 브릿지, 앱 번들과 무관)

- `window.opener` 있으면: `opener.postMessage({ type: 'auth:complete', error }, FE_ORIGIN)` → `window.close()`
- 없으면(전체 리다이렉트 폴백으로 진입): `location.replace('/')` (에러면 `/?error=...`)

### 화면 (와이어프레임 기준)

- **WF-001 비로그인 랜딩**: 로고·소개 문구·"로그인/회원가입" 버튼 (중앙 배치)
- **WF-003 인증 모달**: 딤 오버레이·닫기(X)·제목·안내 문구·provider 버튼 3개·약관 안내 문구.
  Google만 활성, **Kakao·Naver는 disabled + "준비 중"**. 닫기 → WF-001 복귀
- **WF-002 로그인 랜딩**: 로그인 상태 노출(사용자 표시·로그아웃·서재 진입) — 상세는 WF-002 프레임 문서 따름
- 서재(WF-004 계열)는 기존 검증 UI를 로그인 상태에서만 접근 가능하게 감쌈

### 기존 파일 변경

- `api.js`: 각 함수의 `fetch` → `authFetch` 교체 (시그니처·반환 무변경)
- `App.jsx`: 부트스트랩 분기(로딩 → WF-001 / WF-002·서재) + 인증 모달 상태 + 에러 문구(`?error` 또는 postMessage의 error)

### 테스트

- `auth.test.js` (기존 `api.test.js` 패턴): refresh single-flight / 401→refresh→재시도 성공 / 재시도 후 401이면 비로그인 전파 / postMessage origin 검사
- auth.js는 승계 대상 로직이므로 테스트 가치 있음 (fe/DESIGN.md D8 "버릴 코드 무테스트" 원칙과 모순 없음)

## 7. 에러 처리 · 설정 · 테스트 (BE)

### 에러 처리

- `ErrorCode` 추가: `AUTH_REFRESH_INVALID`(401 — 쿠키 없음·만료·폐기). 재사용 감지도 동일 응답
- 미인증 `/api/**`: 커스텀 EntryPoint → `ErrorResponse` JSON 401 (FE `apiError()` 그대로 파싱)
- OAuth 실패·취소: FailureHandler → 브릿지 `?error=oauth_failed` → 모달/랜딩에 문구 표시
- Google이 email 미제공 시: nullable 통과 (요청 스코프: `openid email profile`)

### 설정·시크릿

- `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET`/`AUTH_JWT_SECRET` 환경변수 주입
- 만료(30m/14d)·FE origin은 `AuthProperties` (기존 `AppProperties` 패턴)
- 사전 준비: Google Cloud Console OAuth 클라이언트 등록, redirect URI = `http://localhost:5173/api/login/oauth2/code/google` (브라우저 기준 — 프록시가 BE 전달)

### BE 테스트 (기존 Testcontainers 패턴)

- AuthController 통합: refresh 성공(새 쿠키+새 access) / 회전 후 이전 토큰 401 / revoked 재사용 → 전체 세션 폐기 / logout 멱등 204 / logout 후 refresh 401
- JWT 보호: 무토큰 `/api/papers` 401(ErrorResponse 포맷) / 유효 200 / 만료 401
- OAuthUserService: 신규 생성 / 기존 식별 / (provider, providerId) 유니크
- 성공 핸들러 단위: upsert 호출·쿠키 세팅·리다이렉트 URL (Google 왕복 자체는 프레임워크 신뢰)
- Paper 생성 시 ownerId 기록 확인

### 수동 E2E (FT-001 Done Criteria)

신규 가입 → users 행 생성 / 재로그인 → 같은 행 식별 / 팝업 완료 시 모달 닫히고 서재 진입 / 팝업 차단 시 리다이렉트 폴백 동작 / 새로고침 → 세션 유지 / 로그아웃 → 랜딩 복귀 + 이후 refresh 401 / 미로그인 API 호출 → 401. 실제 Google 계정으로 로컬 확인.

## 8. 구현 순서 (제안)

1. **contract** — project-docs PR: openapi 0.1.2 (auth 3종 + bearerAuth + 401)
2. **BE** — 의존성·SecurityConfig·user 컨텍스트·papers 보호·ownerId (+테스트)
3. **FE** — auth.js·브릿지·와이어프레임 화면(WF-001/002/003)·api.js 교체 (+테스트)
4. **E2E** — Google 실계정 수동 검증 (팝업·폴백 포함)
