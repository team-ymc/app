# Local BE Server Deployment

## Prerequisites

### 공용 인프라 (PostgreSQL + LocalStack)

BE는 기동 시 DB 연결이 필요하므로 공용 인프라를 먼저 띄운다.
기동 방법은 **infra 레포**의 `local/README.md` 참고.

### `.env`

`.env` 없이도 컨테이너는 뜨지만(DB 등 로컬 기본값은 `application.yml`에 있다),
`/api/**`가 전부 인증을 요구하므로 **브라우저로 쓰려면 사실상 필수다.** 구글
로그인이 되어야 논문·채팅 API에 접근할 수 있다. BE 저장소 루트에서 복사한다.

```bash
cp .env.example .env
```

로그인 플로우까지 테스트하려면 두 가지를 모두 지켜야 한다.

1. **각자 자기 로컬 전용 OAuth 클라이언트를 만들어** `GOOGLE_CLIENT_ID`·
   `GOOGLE_CLIENT_SECRET`에 넣는다 (아래 참고). 팀이 secret을 공유하지 않는다.
2. **FE dev 서버(5173)를 띄워 FE 경유로 로그인한다.**

### 로컬 전용 OAuth 클라이언트 만들기

Google Cloud Console에서 본인 계정으로 만든다. 배포용 클라이언트와 분리하므로
secret이 새어도 서비스에 영향이 없고, 팀원 간 secret 전달도 필요 없다.

1. 사용자 인증 정보 → OAuth 클라이언트 ID 만들기 → 유형 **웹 애플리케이션**
2. 승인된 리디렉션 URI에 아래를 등록한다 (FE 경유 기준이라 5173이다)

```text
http://localhost:5173/api/login/oauth2/code/google
```

3. 발급된 client ID·secret을 `.env`에 넣는다

GCP 프로젝트는 개인 프로젝트를 새로 만들어도 되고 팀 프로젝트를 써도 된다.
팀 프로젝트가 **Testing** 상태면 Audience 탭에 본인 Google 계정을 테스트
사용자로 등록해야 로그인이 된다.

## Run

BE 저장소 루트에서 이미지를 빌드하고 서버를 실행한다.

```bash
docker compose up --build -d
docker compose ps          # 컨테이너 상태 확인 — be가 (healthy)면 정상
docker compose logs -f be  # be 로그 팔로우 (Ctrl+C로 종료)
```

health check:

```bash
curl http://localhost:8080/actuator/health   # {"status":"UP"}
```

## Stop

```bash
docker compose down
```

이 명령은 외부 `ymc-local` 네트워크, PostgreSQL·LocalStack 컨테이너,
DB 데이터를 삭제하지 않는다.
