# Backend database migrations

Backend가 소유하는 PostgreSQL 스키마의 원본은
`src/main/resources/db/migration/`의 Flyway migration이다.

- 모든 환경에서 Flyway가 애플리케이션 시작 중 미적용 migration을 순서대로 실행한다.
- Hibernate는 `ddl-auto: validate`로 entity와 적용된 스키마의 일치만 확인한다.
- 적용된 migration 파일은 수정하거나 삭제하지 않는다. 변경은 다음 버전 파일로 추가한다.
- 기존 local·dev DB는 최초 실행 시 version `0`으로 baseline된 뒤 `V1`부터 적용된다.
- PROD의 `baseline-on-migrate` 기본값은 `false`다. 기존 스키마를 편입해야 한다면 대상 DB를
  확인한 뒤 일회성으로 명시적으로 활성화한다.

`plan-beta-pro.sql`은 스키마 migration이 아니라 베타 사용자 권한을 부여하는 운영 DML이므로
이 디렉터리에 별도로 유지한다.
