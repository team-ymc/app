# app/be — Spring Boot 백엔드

근거·전체 맥락: [docs/adr/0001-backend-architecture.md](docs/adr/0001-backend-architecture.md). 아래는 코드 작성 시 따를 요약.

## 패키지 구조

컨텍스트 우선. `com.ymc.<context>` 아래 `api / service / domain / infra`.

```
com.ymc.paper
├── api/        # Controller + 요청/응답 DTO. HTTP ↔ DTO 변환만
├── service/    # 조율(트랜잭션 경계) + 외부 연동 포트 인터페이스 소유
├── domain/     # JPA 엔티티(=도메인) + 리포지토리 인터페이스(~Custom 포함)
└── infra/      # 손으로 쓴 구현 — 포트 구현체(S3·SQS), 리포지토리 ~CustomImpl
```

- 핵심 용어 **Paper**. 계약(`openapi.yaml`)이 `/api/papers`·`paperId`·`PaperStatus`를 쓰니 코드도 동일 — 경계에서 이름 안 바꾼다.
- 파이프라인은 도메인 중립(FT-003). 확장 시 재명명 검토, 그전까진 계약·코드 용어 일치 우선.
- 새 컨텍스트(최상위 패키지) 추가는 **사람이 결정** — 임의로 만들지 말 것.

## 의존성 규칙

- 컨텍스트 간 참조는 **ID로** (엔티티 직접 참조·`@ManyToOne` 금지). 데이터는 서비스 호출/이벤트로 조립.
  - 조립이 과하게 비싼 지점만 예외 — 이유를 주석/PR에 남긴다. 원칙은 ID.
- 포트(인터페이스)는 **외부 시스템(S3·SQS 등)에만** — 인터페이스 `service/`, 구현 `infra/`.
- 영속성엔 포트/Impl을 씌우지 않는다 (리포지토리 규칙 참고).

## 비즈니스 로직 배치

- 단일 엔티티 상태 전이·불변식 → **엔티티 메서드**
- 여러 엔티티/외부 연동 조율 → **service**
- 엔티티는 도메인 겸용(JPA 어노테이션 허용). setter 남발 금지 — 의도 드러나는 메서드로.

## Lombok

- **빈(`api`·`service`·`infra`)의 의존성 주입은 `@RequiredArgsConstructor`.** 필드는 `final`로 둔다.
  - 생성자에서 검증·변환·기본값 같은 **작업이 필요하면 그때만 명시적 생성자를 쓴다**.
- **`domain` 엔티티에는 `@Getter`만 허용.** `@Setter`(불변식 무력화)·`@Data`/`@EqualsAndHashCode`(프록시·신규 엔티티 동등성 깨짐)·`@ToString`(연관관계 지연로딩·무한재귀) 금지.
  - 상태 변경은 의도 드러나는 엔티티 메서드로만 (생성=정적 팩토리, 전이=`markProcessing` 등).

## JPA 연관관계·로딩

- 컨텍스트 내부 연관관계 자유. 단 **모든 연관관계 `fetch = LAZY` 명시** (`@ManyToOne`·`@OneToOne` 기본값이 EAGER). 최적화는 fetch join.
- **OSIV off** (`open-in-view: false`). 지연로딩은 service(트랜잭션) 안에서 끝낸다 — controller/DTO 변환 시 LAZY 접근하면 `LazyInitializationException`.

## 리포지토리 규칙

- `JpaRepository<T, ID>` 상속.
- 쿼리 3단계 사다리: ① 파생 쿼리 → ② `@Query`(JPQL) → ③ custom fragment.
  - ③: `~Custom` 인터페이스는 `domain/`, `~CustomImpl` 구현은 `infra/`. 이름 `~CustomImpl` 지켜야 Spring Data가 합성.
  - ③이 필요해 보이면 임의로 말고 **확인받는다** (동적 조건 다수 등).
- 서비스가 보는 진입점은 리포지토리 인터페이스 하나 — 구현체(`~CustomImpl`, `JPAQueryFactory`)는 뒤에 숨는다.

```java
// domain/ — 서비스는 이 인터페이스 하나만 주입
public interface PaperRepository extends JpaRepository<Paper, UUID>, PaperRepositoryCustom {
    List<Paper> findAllByOwnerId(UUID ownerId);   // ① 파생
}
public interface PaperRepositoryCustom {          // domain/ — ③ 선언
    List<Paper> search(SearchCond cond);
}
// infra/ — ③ 구현만 손으로
public class PaperRepositoryCustomImpl implements PaperRepositoryCustom {
    private final JPAQueryFactory query;
}
```