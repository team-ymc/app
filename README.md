# app — 서비스 코드 (be + fe)

Paper Teacher의 백엔드·프론트엔드를 함께 담는 monorepo다. 요구사항·설계·계약의 SSOT는 이 repo가 아니라 형제 repo `project-docs/`에 있다.

```
app/
  be/         # Spring Boot 3.5 / Java 21 / JPA / AWS SDK v2 (S3·SQS) / PostgreSQL
  fe/         # 프론트엔드
```

> AI(Claude Code)용 작업 규칙은 [`CLAUDE.md`](./CLAUDE.md)에 있다. 이 문서는 사람용 온보딩이다.

## 어디서 무엇을 읽나 (SSOT)

코드 repo 안에 스펙 사본을 두지 않는다. 항상 아래 원본을 읽는다.

- **요구사항**: `project-docs/features/FT-XXX-*.md`
- **API·이벤트 계약**: `project-docs/contracts/` (먼저 `contracts/README.md`) — `frontend-backend/openapi.yaml`(FE↔BE), `backend-ai/openapi.yml`·`backend-ai/sse/`(BE↔AI)
- **아키텍처 결정**: `project-docs/decisions/ADR-XXX-*.md`

## 로컬에서 띄우기

로컬 환경(LocalStack = SQS+S3, PostgreSQL)은 형제 repo **`infra/`**가 띄운다. repo들은 형제로 clone돼 있어야 한다 (`team-ymc/{infra, app, project-docs, …}`). 자세한 전제·엔드포인트·큐/버킷 이름은 [`infra/README.md`](../infra/README.md)가 SSOT다.

**순서:**

```bash
# 1) 환경 — LocalStack + PostgreSQL (bootstrap.sh가 큐·버킷을 자동 생성)
cd ../infra/local
docker compose up localstack postgres -d

# 2) 백엔드 — :8080 (네이티브 실행 권장. 컨테이너로도 가능: docker compose up main_server)
cd ../../app/be
./gradlew bootRun

# 3) 프론트엔드 — 실행법은 fe/README.md 참고
cd ../fe
```

- BE 네이티브 실행은 `application.yml` 기본값(`localhost`)을 그대로 쓴다. 컨테이너로 띄우면 compose가 DB 호스트만 덮어쓴다.
- 인증은 Google 소셜 로그인(FT-001)의 BE 코어(`oauth2Login` + JWT access·refresh 쿠키)가 구현돼 있다. 엔드포인트별 인증 요구는 `openapi.yaml`의 security 정의가 SSOT다.

## be

Spring Boot. 세부 규칙·아키텍처는 [`be/CLAUDE.md`](./be/CLAUDE.md), [`be/docs/adr/`](./be/docs/adr/). 현재 FT-003(논문 등록·분석) BE 구간과 FT-001(인증) BE 코어가 구현돼 있다.

## fe

프론트엔드. **현재는 진짜 FE가 아니라 BE 검증용 임시 UI**가 들어 있다 — 무엇이고 왜 그런지는 [`fe/README.md`](./fe/README.md). 실제 FE가 착수되면 이 문서의 실행 순서(환경 → be → fe)는 그대로 유효하고, `fe/`의 내용만 교체된다.
