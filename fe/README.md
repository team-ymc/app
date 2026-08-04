# app/fe — Paper Teacher 프론트엔드

Jira 에픽: [YMC-289](https://geunhh.atlassian.net/browse/YMC-289) · 설계: [`docs/superpowers/specs/2026-07-31-fe-v1-redesign-design.md`](../docs/superpowers/specs/2026-07-31-fe-v1-redesign-design.md) · 왜 이렇게 만들었나(구 임시 UI 이력): [`DESIGN.md`](./DESIGN.md)

React 19 + TypeScript(Vite) SPA. 페이지 3개 — Landing(`/`) · Bookshelf(`/library`) · Study(`/papers/:id`).

## 설치

```bash
npm i
```

## 실행

```bash
npm run dev
```

BE(Spring Boot)와 LocalStack이 함께 떠 있어야 로그인·업로드·챗이 동작한다. `infra/local`을 참조.

```bash
cd ../../infra/local && ./up.sh          # LocalStack + PostgreSQL
cd ../../app/be && ./gradlew bootRun     # 또는 docker compose up --build -d
```

파싱 워커가 없으므로 업로드한 논문은 `PROCESSING`에서 멈춘다. 수동으로 완료/실패 처리:

```bash
cd ../../infra/local
./publish-parse-result.sh <paperId> COMPLETED
./publish-parse-result.sh <paperId> FAILED
```

## 테스트

```bash
npm test
```

## 타입체크

```bash
npm run typecheck
```

## 빌드

```bash
npm run build
```

## 계약 미확정 구간

BE 계약(`project-docs/contracts/`)이 아직 정하지 않은 두 곳은 어댑터로 격리해 두었다. 계약이 확정되면 아래 함수 내부만 교체하면 된다 — 소비하는 컴포넌트(뷰어, 인라인 액션)는 그대로 둔다.

- **`getPaperContent`** (`src/markdown/paperContent.ts`) — 논문 본문 구조(`DocumentParseResponse`)가 미확정이라 `src/fixtures/sample-paper.md` 픽스처를 반환한다.
- **`translateSelection`** (`src/api/translate.ts`) — 인라인 선택 번역 엔드포인트가 없어 지연 후 더미 문자열을 돌려주는 목이다.

## 원칙

**BE 코드는 건드리지 않는다.** 검증 중 BE/계약 문제를 발견하면 여기서 고치지 말고 별도 이슈로 등록한다.
