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

## 실행:preview
```bash
npm run dev:preview
```

`dev:preview`에서 뷰어(`/papers/preview-transformers`)를 열면 기존 상단 바의 지식 그래프~번역 버튼 범위에만 얇은 진행 선과 '분석 중 0%'부터의 예상 진행률이 자동으로 나타난다. 버튼이 줄바꿈되면 각 줄의 해당 버튼 아래로 선이 이어진다. 상단 바의 배치·높이·버튼 스타일은 바꾸지 않고 별도 영역도 추가하지 않는다.
휴대폰(680px 이하)에서는 기존 목차·AI 튜터 전환 버튼 사이의 빈 공간에 짧은 진행 막대 하나와 작은 진행률을 표시한다. 새 행을 추가하지 않는다.
100초 동안 진행 표시를 보여준 뒤 100%로 채워지고 사라진다. 새로고침하거나 뷰어를 다시 열면 다시 시작한다.
이 데모는 `dev:preview`에서만 적용되며, 샘플 데이터·버튼 동작·API 상태는 바꾸지 않는다.
실제 분석 중에는 100초에 99%까지 올라간 뒤 서버의 준비 완료 상태가 올 때까지 유지한다. 완료가 빨리 오면 즉시 100%를 표시하고, 서재처럼 600ms 동안 끝까지 채운 뒤 650ms에 표시를 없앤다. 실패는 100% 완료 연출 없이 표시를 없앤다. 이미 준비된 논문에는 처음부터 표시하지 않는다. 표시 시간이나 진행률로 기능을 활성화하지 않는다.

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
