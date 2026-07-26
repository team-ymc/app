# app — 서비스 코드 (be + fe)

Paper Teacher의 백엔드·프론트엔드를 함께 담는 monorepo다.

```text
app/
  be/         
  fe/         
```

> AI(Claude Code)용 작업 규칙은 [`CLAUDE.md`](./CLAUDE.md)에 있다.

## 어디서 무엇을 읽나 (SSOT)

코드 repo 안에 스펙 사본을 두지 않는다. 항상 아래 원본을 읽는다.

- **요구사항**: `project-docs/features/FT-XXX-*.md`
- **API·이벤트 계약**: `project-docs/contracts/`
- **아키텍처 결정**: `project-docs/decisions/ADR-XXX-*.md`

## 로컬에서 띄우기

로컬 환경(LocalStack = SQS+S3, PostgreSQL)은 형제 repo **`infra/`**가 띄운다. repo들은 형제로 clone돼 있어야 한다 (`team-ymc/{infra, app, project-docs, …}`). 실행법은 [`infra/README.md`](../infra/README.md)가 SSOT다.

## be

Spring Boot. [`be/README.md`](./be/README.md)

## fe

프론트엔드. [`fe/README.md`](./fe/README.md)
