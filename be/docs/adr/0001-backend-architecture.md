# ADR-0001: 백엔드 아키텍처 — 레이어드 + 실용적 DIP, Entity=Domain으로 시작

## 1. Overview

- Date: 2026-07-02
- Deciders: 근흐흐 (BE 담당)
- Scope: `app/be` (Spring Boot, `com.ymc`)

## 2. Context

제품 초기라 도메인 경계가 아직 안정적이지 않다.

처음부터 헥사고날 아키텍처나 도메인/영속성 분리를 전면 적용하면 파일 수와 리뷰 비용이 커지고, 아직 확실하지 않은 추상화를 먼저 고정하게 된다.
다만 전역 `controller/service/repository` 구조로 시작하면 기능이 늘어날수록 컨텍스트 경계가 흐려지고 서비스 간 직접 참조가 늘어날 위험이 있다.
또한 대부분의 코드를 AI 에이전트가 작성하므로, 구조 규칙은 에이전트가 일관되게 따를 수 있도록 명시적이어야 한다.

## 3. Decision

백엔드는 **컨텍스트 우선 레이어드 구조**로 시작한다.

```txt
com.ymc.<context>
  api
  service
  domain
  infra
```

1. **컨텍스트 우선 패키지** — 기능 작업은 해당 컨텍스트 폴더 안에서 끝낸다.
2. **컨텍스트 간 참조는 ID로만** — 다른 컨텍스트의 Entity 직접 참조 금지. 필요한 데이터는 service 호출이나 이벤트로 조회한다.
3. **Entity = Domain 겸용** — 초기에는 JPA Entity를 Domain 객체로 함께 사용한다.
4. **계약은 안쪽에, 손으로 쓴 구현은 `infra`에** — 외부 시스템(S3·SQS)은 service에 Port 인터페이스를 두고 구현체는 infra에 둔다. Repository는 `JpaRepository` 직상속 대신 메서드 0개인 `Repository<T, ID>` 마커를 상속해 필요한 메서드만 노출한다.
5. **로직 배치** — 단일 엔티티의 상태 전이·불변식은 엔티티 메서드에, 여러 엔티티/외부 연동의 조율은 service에 둔다.

세부 컨벤션(쿼리 작성 단계, LAZY 강제 등)은 `app/be/CLAUDE.md`에 둔다.

## 4. Options Considered

### Option A. 컨텍스트 우선 레이어드 ✅ 채택
- 기능 단위 응집과 초기 단순함을 동시에 얻는다.
- 경계·ID 참조 규칙 덕에 헥사고날이나 모듈 분리로의 전환 경로가 열려 있다.

### Option B. Hexagonal Architecture
- 도메인 순수성과 인프라 분리에는 유리하다.
- 탈락: 현재 단계에서는 파일과 추상화가 많아져 개발/리뷰 비용이 커지고, 아직 확실하지 않은 추상화를 먼저 고정하게 된다. 도메인 경계가 안정된 뒤 필요한 컨텍스트부터 도입한다.

### Option C. Layer-first Architecture (전역 controller/service/repository)
- 초기 구현은 가장 단순하다.
- 탈락: 기능이 늘어나면 컨텍스트 경계가 흐려지고 서비스 간 결합이 커진다. 과거에 이 구조를 고르던 이유는 초기 속도였지만, 구조를 갖추는 작성 비용이 AI로 거의 0이 된 지금은 속도를 위해 이 부채를 살 이유가 없다.

## 5. Consequences

장점:

- 기능 단위 코드가 한 컨텍스트 아래 모인다.
- 초기 개발 속도와 구조적 가드레일 사이의 균형을 잡을 수 있다.
- 나중에 컨텍스트를 모듈이나 서비스로 분리하기 쉽다.

단점:

- Domain이 JPA에 의존한다 (의도적 타협).
- 컨텍스트 경계를 잘못 잡으면 이후 수정 비용이 생긴다 — 새 컨텍스트 추가는 사람이 결정한다.
- 순수 도메인 테스트가 필요한 영역에서는 분리가 필요할 수 있다.

### Revisit When

다음 조건이 생기면 해당 컨텍스트부터 Domain/Persistence 분리를 검토한다.

- JPA 제약 때문에 도메인 모델이 어색해질 때
- 순수 도메인 테스트가 중요해질 때
- 조회 모델과 명령 모델의 요구가 크게 갈라질 때
- 외부 연동이나 저장소 교체 가능성이 실제 요구사항이 될 때

## 6. Updates

- 2026-07-02 최초 작성 (팀 컴펌 대기 — Proposed).

## 부록: 샘플 구조 (document 컨텍스트)

규칙 1~5가 실제 파일로 어떻게 놓이는지의 기준 예시. 새 컨텍스트는 이 모양을 복제한다.
(용어: 등록·파싱 대상은 논문에 한정되지 않으므로 `Paper`가 아니라 **`Document`** — 파일 포맷명(`PDF` 등)은 도메인 개념이 아니므로 금지. 단, 파싱 서버와의 메시지 필드명 `paperId`(ADR-001 D9)는 계약이므로 `documentId`로 바꿀지 AI/파싱 담당과 합의 전까지 유지한다.)

```
com.ymc.document
├── api/
│   ├── DocumentController.java             # HTTP ↔ DTO 변환만. 엔티티 직접 노출 금지
│   └── dto/
│       ├── RegisterDocumentRequest.java
│       └── DocumentResponse.java
├── service/
│   ├── DocumentService.java                # 조율 + 트랜잭션 경계 (규칙 5)
│   ├── FileStoragePort.java                # S3 포트 — 사용하는 쪽이 소유 (규칙 4)
│   └── ParseRequestPort.java               # SQS 포트
├── domain/
│   ├── Document.java                       # JPA 엔티티 = 도메인 (규칙 3). 상태 전이·불변식은 여기 (규칙 5)
│   ├── DocumentStatus.java
│   ├── DocumentRepository.java             # extends Repository<Document, UUID> 마커 + 필요 메서드만 (규칙 4)
│   └── DocumentRepositoryCustom.java       # 복잡한 쿼리가 필요해질 때만 — 쿼리 계약 (CLAUDE.md 쿼리 사다리 참고)
└── infra/                                  # 손으로 쓴 구현 전부 (규칙 4)
    ├── S3FileStorage.java                  # implements FileStoragePort
    ├── SqsParsePublisher.java              # implements ParseRequestPort
    └── DocumentRepositoryCustomImpl.java   # 복잡한 쿼리가 필요해질 때만 — QueryDSL/JdbcTemplate
```

- 컨텍스트 간 참조(규칙 2): `Document`는 `Member`를 모른다 — `private UUID ownerId` 필드만 갖고, 소유자 정보가 필요하면 service에서 `memberService.getMember(ownerId)`를 명시적으로 호출한다. 같은 컨텍스트 안(`Document` ↔ `DocumentSection` 등)에서는 JPA 연관관계 자유(단, 전부 `LAZY` 명시).
- `~Custom`/`~CustomImpl` 두 파일은 처음부터 만들지 않는다 — 복잡한 쿼리가 실제로 필요해진 시점에 추가.
