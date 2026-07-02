# app/be — Spring Boot 백엔드

아키텍처 규칙의 근거와 전체 맥락은 [docs/adr/0001-backend-architecture.md](docs/adr/0001-backend-architecture.md) 참고. 아래는 코드 작성 시 반드시 따를 요약.

## 패키지 구조

컨텍스트 우선. `com.ymc.<context>` 아래 `api / service / domain / infra`.

```
com.ymc.document
├── api/        # Controller + 요청/응답 DTO. HTTP ↔ DTO 변환만
├── service/    # 조율(트랜잭션 경계). 외부 연동 포트 인터페이스도 여기 소유
├── domain/     # JPA 엔티티(=도메인) + 리포지토리 인터페이스(~Custom 포함)
└── infra/      # 손으로 쓴 구현 전부 — 포트 구현체(S3·SQS), 리포지토리 ~CustomImpl
```

- 핵심 용어: 등록·파싱 대상은 **Document** (논문에 한정되지 않음). `Paper`·`PDF` 같은 이름 금지.

- 새 컨텍스트(최상위 패키지) 추가는 **사람이 결정한다** — 임의로 만들지 말고 사용자에게 확인.

## 의존성 규칙

- 컨텍스트 간 참조는 **ID로만**. 다른 컨텍스트 엔티티 직접 참조·`@ManyToOne` 금지. 필요한 데이터는 서비스 호출 또는 이벤트로.
- 포트(인터페이스)는 **외부 시스템(S3·SQS 등)에만**. 인터페이스는 `service/`에, 구현은 `infra/`에.
- 영속성에는 포트/Impl을 씌우지 않는다 (아래 리포지토리 규칙 참고).

## 비즈니스 로직 배치

- 단일 엔티티의 상태 전이·불변식 → **엔티티 메서드**
- 여러 엔티티/외부 연동을 엮는 조율 → **service**
- 엔티티는 도메인 겸용(JPA 어노테이션 허용). setter 남발 금지 — 의도가 드러나는 메서드로.

## JPA 연관관계·로딩 규칙

- 같은 컨텍스트 안에서는 연관관계 자유. 단 **모든 연관관계에 `fetch = FetchType.LAZY` 명시** — `@ManyToOne`·`@OneToOne`은 기본값이 EAGER라서 명시 없이 쓰면 N+1의 원인이 된다. 조회 최적화가 필요하면 fetch join.
- **OSIV는 꺼져 있다** (`spring.jpa.open-in-view: false`, application.yml). 지연로딩은 트랜잭션이 열린 service 안에서 끝낼 것 — controller나 DTO 변환 시점에 LAZY 필드에 접근하면 `LazyInitializationException`이 난다.

## 리포지토리 규칙

- **`JpaRepository` 직상속 금지.** 메서드 0개인 `Repository<T, ID>` 마커를 상속하고 필요한 메서드만 선언한다.

```java
public interface DocumentRepository extends Repository<Document, UUID> {
    Document save(Document document);
    Optional<Document> findById(UUID id);
}
```

- 쿼리는 3단계 사다리 순서로 시도:
  1. 파생 쿼리 메서드 (`findAllByOwnerId` 등)
  2. `@Query`(JPQL)
  3. custom fragment — `~Custom` 인터페이스는 `domain/`(리포지토리 옆), `~CustomImpl`은 `infra/`(QueryDSL/JdbcTemplate 자유). 이름 규칙 `~CustomImpl`을 지켜야 Spring Data가 합성한다
- **②로 감당이 안 되어 ③(fragment)이 필요해 보이면, 임의로 진행하지 말고 사용자에게 확인받는다.** (동적 조건이 여럿이거나 JPQL이 과도하게 길어지는 경우 등)
- 어느 단계든 service가 보는 진입점은 리포지토리 인터페이스 하나다.
