# BE Server (ymc-api)

Spring Boot 백엔드 서버입니다. 현재 아래 기능을 제공합니다.

- `auth`: Google OAuth 로그인 + 자체 발급 JWT
- `paper`: 논문 업로드(S3 presigned URL) → 파싱 요청/결과 파이프라인
- `chat`: AI 서버의 SSE 스트림을 중계하는 논문 채팅

API 경로·요청/응답 스키마·이벤트 메시지의 SSOT는
`project-docs/contracts/`입니다 (제일 먼저 `contracts/README.md`).
이 저장소에는 스펙 문서를 복사해 두지 않습니다.

## Run (Docker)

Docker로 빌드·실행하는 절차는 [LOCALDEPLOY.md](LOCALDEPLOY.md) 참고.

## Setup (네이티브 실행)

BE 코드를 직접 개발할 때는 네이티브로 띄웁니다. JDK 21이 필요합니다
(Gradle toolchain이 없으면 자동 다운로드).

### Configure Environment

DB 등 로컬 기본값은 `application.yml`에 있어 공용 인프라만 떠 있으면 추가 설정
없이 기동됩니다. 구글 로그인까지 쓰려면 `src/main/resources/application-local.properties`
설정이 필요합니다 (gitignore됨. OAuth 클라이언트는 각자 만듭니다 — [LOCALDEPLOY.md](LOCALDEPLOY.md)).
`.env`는 docker compose용이라 `bootRun`은 읽지 않습니다.

### Run

```bash
./gradlew bootRun
```

테스트는 Docker가 필요합니다 (Testcontainers로 PostgreSQL·LocalStack 기동):

```bash
./gradlew test
```

## AI 서버 연동

BE↔AI 접점은 아래와 같습니다. HTTP API 스키마는
`project-docs/contracts/backend-ai/openapi.yml`, SQS 채널 이름과 전달 의미론은
`project-docs/decisions/ADR-002-be-ai-messaging-sqs.md`가 SSOT입니다.

| 접점 | 방향 | 로컬 주소/이름 |
| --- | --- | --- |
| 채팅 SSE (`ai.base-url`) | BE → AI HTTP | 네이티브 `http://localhost:8000` · 컨테이너 `http://ymc-ai:8000` |
| SQS `parse-requests` | BE → AI | LocalStack `http://localhost.localstack.cloud:4566` |
| SQS `parse-results` | AI → BE | 〃 |
| S3 `ymc-documents` | 문서 저장 | 〃 |

- AI 서버 없이 채팅 API를 테스트하려면 `ai.fake-stream: true`(고정 delta fake 어댑터).
- AI 컨테이너에서 BE를 호출할 일이 있으면 `http://ymc-be:8080`.
- LocalStack 리소스·연동 예시는 `infra/local/localstack/README.md`.
