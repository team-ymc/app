# FT-012 선행지식 하이라이트·설명 설계

- 날짜: 2026-09-22
- 티켓: YMC-414
- 범위: BE(`app/be`) + FE(`app/fe`). project-docs·AI 변경 없음. Valkey 인프라와 BE 환경변수 주입은 YMC-413.
- SSOT: `features/FT-012-선행지식.md`, `design/v3/README.md`, `contracts/frontend-backend/openapi.yaml`의 `getPaperContent`·`createPrerequisiteDefinition`, `contracts/backend-ai/openapi.yml`의 `prerequisite-knowledge-agent-run`, `decisions/ADR-011`
- 선행: YMC-404(ai#30, compile이 하이라이트 sidecar 생성, 설명 agent), YMC-389(app#67, compile 결과 처리와 번역 적재)
- 합의 상태: 1~6장 모두 사용자와 확정(2026-09-22). Codex 리뷰 반영 완료.

## 0. 배경

compile 워커는 `frontend/prerequisite-highlights.json`에 하이라이트 범위만 기록한다. 항목은 `highlight_id`·`block_id`·`start_offset`·`end_offset`·`text`이고 offset은 UTF-16 code unit, start 포함·end 제외다. 설명은 사용자가 하이라이트를 처음 누를 때 AI가 만든다. AI 설명 endpoint는 요청 범위가 `structure/prerequisite-highlights.json`의 한 항목과 정확히 일치해야 받아 준다.

현재 BE에는 Redis 연동과 non-streaming AI 클라이언트가 없다. 선행지식은 sidecar 읽기·엔티티·V2 마이그레이션 초안만 worktree에 있고 적재·projection·설명·캐시는 미구현이다. FE에는 본문 구간을 감싸는 하이라이트 구현이 없지만 원문 offset을 DOM에 심는 `rehypeSourcePos`가 있다.

고정 조건:

- 기존 BE↔AI 계약을 그대로 쓴다. 새 계약을 만들지 않는다.
- 설명은 non-streaming endpoint를 쓴다.
- S3 sidecar는 compile 완료 때 BE가 한 번만 읽는다. 본문 조회와 설명 요청은 S3 sidecar를 읽지 않는다.
- 하이라이트 anchor는 PostgreSQL, 생성된 설명만 Valkey 30일 캐시다.
- 설명 생성은 사용자 사용량을 차감하지 않는다.

## 1. 하이라이트 적재·저장 — 확정

| 항목 | 결정 | 근거 |
|---|---|---|
| 저장 모델 | 별도 테이블 `document_prerequisite_highlight`. Flyway `V2` 파일로 추가 | 설명 API의 핵심 접근이 `(document_id, highlight_id)` 한 건 조회다. 블록·asset과 같은 자식 테이블 관례를 따른다. 헤더 JSONB 컬럼 안과 블록 content 병합 안은 기각 |
| 행 규모 | Document당 하이라이트 수만큼. 샘플 논문은 블록 163개에 하이라이트 157개 | 사용자별 Paper가 아니라 공유 Document 단위라 같은 PDF를 여러 명이 올려도 늘지 않는다 |
| 적재 시점 | `KnowledgeCompileResultService`의 COMPLETED 분기에서 번역 병합 다음, `markCompiled` 앞 | 같은 트랜잭션이라 상태만 COMPLETED이고 하이라이트가 없는 중간 상태가 없다 |
| 재전달 직렬화 | 결과 반영은 기존 `findWithLockByRequestPaperId`로 Document 행을 잠그고 시작한다 | 같은 completed 메시지가 동시에 두 번 오면 잠금 없이는 둘 다 적재에 들어가 unique 충돌이 난다. 잠금 뒤에는 두 번째가 종결 가드에서 조용히 빠진다 |
| sidecar 문제 | manifest에 artifact 없음, 파일 없음, JSON 오류, `schema_version != 1`, `offset_encoding != "utf-16"`이면 WARN 후 0건으로 compile을 COMPLETED 종결. S3 일시 장애는 전파해 재전달 | 번역 sidecar와 같은 정책. 하이라이트 파일 하나로 번역과 지식 그래프까지 막지 않는다 |
| 항목 검증 | 필드 누락, `start >= end`, 빈 `text`는 그 항목만 WARN 후 건너뜀. 적재된 본문에 없는 `block_id`도 건너뜀. 블록 `content.text`를 `substring(start, end)`로 잘라 `text`와 다르면 그 항목만 건너뜀 | 계약이 `text`는 해당 범위와 정확히 일치한다고 정의한다. Java 문자열이 UTF-16 code unit 기준이라 substring 한 번으로 대조된다. 어긋난 항목은 화면에 엉뚱한 범위가 칠해지고 AI도 거절한다 |
| 0건 | 정상 결과 | 계약이 READY 뒤 빈 배열을 정상으로 정의한다 |
| 재적재 | `deleteByDocumentId` 후 `saveAll` | 본문 적재와 같은 패턴 |
| 기존 Document | sidecar가 있는 것만 일회성 채우기 작업으로 적재. sidecar가 없는 Document는 그대로 둔다 | AI 추출은 2026-09-21에 머지돼 대상이 dev의 몇 개뿐이다. prod는 첫 배포 전이다 |

스키마:

```sql
create table document_prerequisite_highlight (
    id           bigserial    not null,
    document_id  uuid         not null,
    highlight_id varchar(64)  not null,
    block_id     varchar(255) not null,
    start_offset integer      not null,
    end_offset   integer      not null,
    text         text         not null,
    primary key (id),
    constraint uk_document_prerequisite_highlight unique (document_id, highlight_id),
    constraint ck_document_prerequisite_highlight_range check (start_offset >= 0 and start_offset < end_offset)
);
```

구성 요소:

- `paper/service/port/ParsedPrerequisiteHighlight` — sidecar 항목 record
- `PaperPackageReader.readPrerequisiteHighlights(manifestKey)` — manifest의 `frontend_prerequisite_highlights.path`로 key를 만든다. 경로를 하드코딩하지 않는다
- `paper/domain/DocumentPrerequisiteHighlight`, `DocumentPrerequisiteHighlightRepository` — `findAllByDocumentIdOrderByIdAsc`, `findByDocumentIdAndHighlightId`, `deleteByDocumentId`. sidecar가 문서 순서로 정렬돼 오므로 삽입 순서가 문서 순서다
- `paper/service/DocumentPrerequisiteHighlightIngestService.ingest(documentId, manifestKey)` — 적재한 개수 반환

일회성 채우기:

- `prerequisite.backfill.enabled=true`일 때만 BE 시작 시 한 번 실행. 기본 false
- 대상은 compile COMPLETED이면서 하이라이트 행이 0개인 Document. Document마다 독립 트랜잭션으로 `ingest`를 호출한다. manifest key는 `papers/{requestPaperId}/manifest.json`으로 만든다
- 표시용 컬럼은 두지 않는다. 0건 Document를 다시 훑어도 결과가 같고, 켜서 한 번 돌리고 끄는 용도다
- 별도 커밋으로 분리한다. dev에서 돌린 뒤 제거한다
- "sidecar는 compile 완료 때 한 번 읽는다"는 조건은 본문 조회·설명 요청에서 반복 조회하지 않는다는 뜻이다. 채우기는 Document당 한 번 읽는 운영 작업이라 어긋나지 않는다

## 2. 본문 조회 projection — 확정

- `PaperContentQueryService.getContent`가 `findAllByDocumentIdOrderByIdAsc`를 한 번 더 호출해 `PaperContentView.prerequisiteHighlights`에 싣는다. 고정 SELECT가 하나 늘고 S3 읽기는 없다.
- compile 상태로 따로 거르지 않는다. 행은 compile 완료 트랜잭션에서만 생기므로 완료 전과 실패 시에는 자연히 빈 배열이다.
- 응답 필드는 계약의 `PrerequisiteHighlight` 그대로: `highlightId`·`blockId`·`startOffset`·`endOffset`·`text`.

## 3. 설명 API와 AI 호출 — 확정

`POST /api/papers/{paperId}/prerequisite-highlights/{highlightId}/definition`, body 없음.

처리 순서:

1. 짧은 읽기 트랜잭션에서 검증한다. Paper 없음 → `PAPER_NOT_FOUND` 404, 소유자 아님 → `FORBIDDEN` 403, Document가 COMPLETED가 아니거나 본문 미적재이거나 `compileStatus != COMPLETED` → `PREREQUISITE_NOT_READY` 409, 하이라이트 없음 → `PREREQUISITE_HIGHLIGHT_NOT_FOUND` 404.
2. 트랜잭션 밖에서 캐시를 조회한다. HIT면 바로 반환한다.
3. MISS면 사용자 동시 생성 소유권을 얻는다. 실패 → `PREREQUISITE_CONCURRENCY_LIMIT_EXCEEDED` 429.
4. AI를 non-streaming으로 호출하고 결과를 캐시에 저장한 뒤 반환한다. 소유권은 `finally`에서 놓는다.

AI 호출 중에는 DB 트랜잭션과 커넥션을 잡지 않는다. `paper.markAccessed`는 호출하지 않는다.

AI 요청:

- `POST /api/v1/agents/prerequisite-knowledge-agent/runs`
- `paper_id`는 `document.requestPaperId`. 파싱·compile 때 AI에 넘긴 값이고 S3 패키지 prefix와 같다
- `thread_id`는 호출마다 새 UUID
- `selection.start = {block_id, offset: start_offset}`, `selection.end = {block_id, offset: end_offset}`. 계약의 anchor 필드명은 `offset`이다

AI 응답 처리:

- 응답의 `definition`은 고정 형식 Markdown이다: `### {term}`, 빈 줄, `**Definition (정의)**`, 빈 줄, 영문 한 줄과 공백 두 칸 줄바꿈, 국문 한 줄.
- BE는 전체 형식을 anchored 정규식 `^### .+\n\n\*\*Definition \(정의\)\*\*\n\n(.+)  \n(.+)$`으로 맞춰 두 그룹을 `definitionEn`·`definitionKo`로 꺼낸다. 형식이 어긋나면 실패로 처리하고 캐시에 저장하지 않는다. AI 형식이 바뀌면 조용히 틀린 값을 내지 않고 바로 드러나게 한다.
- 응답의 `term`은 캐시나 AI 응답이 아니라 저장된 하이라이트의 `text`다.
- AI의 4xx·5xx, 타임아웃, 형식 오류는 모두 `PREREQUISITE_DEFINITION_FAILED` 502. AI의 에러 code와 `detail.estimated_cost_usd`는 로그로 남긴다.
- `UsageService`를 호출하지 않는다. `estimated_cost_usd`는 INFO 로그와 Micrometer로만 기록한다.

구성 요소:

- `paper/api/PaperController`에 operation 추가, `PrerequisiteDefinitionResponse`
- `paper/service/PrerequisiteDefinitionService`
- 포트 `paper/service/port/PrerequisiteDefinitionGenerator`, 구현 `paper/infra/ai/AiPrerequisiteDefinitionAdapter`. 기존 `aiWebClient` 빈을 주입받아 `.block(timeout)`으로 호출한다. 프로덕션 조건부 fake 빈은 두지 않고 테스트에서 가짜 AI 서버를 쓴다
- 타임아웃 설정 `ai.prerequisite-definition-timeout`, 기본 60초. non-streaming이라 delta 침묵이 아니라 요청부터 응답까지 전체 상한이다

**결정 기록**

- D3-1 코드 위치 — 확정. `paper` 컨텍스트에 두고 `chat`이 만든 `aiWebClient` 빈만 공유한다. 새 최상위 컨텍스트는 만들지 않는다.
- D3-2 Markdown 분리 — 확정. 정규식 전체 대조. AI 응답에 영문·국문 필드를 따로 두는 계약 변경은 AI 담당자와의 후속 논의로 남긴다.
- D3-3 타임아웃 — 확정. 60초, 설정값.

## 4. Valkey 캐시와 사용자별 동시 생성 제한 — 확정

- 의존성 `spring-boot-starter-data-redis`, `StringRedisTemplate`. 연결 설정은 YMC-413이 주입하는 `SPRING_DATA_REDIS_*`를 쓴다.
- Valkey는 외부 시스템이므로 포트를 둔다. `paper/service/port/PrerequisiteDefinitionCache`와 `PrerequisiteGenerationLock`, 구현은 `paper/infra/cache`.

캐시:

- 키 `prerequisite-definition:v1:{documentId}:{generatorVersion}:{normalizedTermHash}` — ADR-011 그대로
- 정규화는 NFC, 앞뒤 공백 제거, 연속 공백을 한 칸으로, `Locale.ROOT` 소문자. 해시는 SHA-256 hex
- `generatorVersion`은 `PREREQUISITE_KNOWLEDGE_AGENT_ACTIVE_VARIANT`. 기본값을 두지 않아 누락되면 시작에 실패한다
- 값은 `definitionEn`·`definitionKo`·`generatedAt` JSON
- 저장은 `SET NX`와 TTL. TTL은 `PREREQUISITE_DEFINITION_CACHE_TTL`, 기본 30일. 조회로 TTL을 늘리지 않는다
- 서로 다른 사용자의 동시 MISS는 병합하지 않는다. 먼저 저장된 값이 남고 각자는 자기가 생성한 값을 반환한다

동시 생성 소유권:

- 키 `prerequisite-definition:lock:{userId}`, 값은 호출마다 새 토큰, `SET NX PX`
- 만료는 AI 타임아웃에 5초를 더한 값. 정상 요청이 상한을 채워도 잠금이 먼저 풀리지 않고, BE가 죽어도 그 시간 뒤에 풀린다
- 해제는 토큰이 같을 때만 지우는 Lua 스크립트. 만료 뒤 다른 요청이 잡은 소유권을 지우지 않는다
- 캐시 HIT는 소유권을 얻지 않는다

Valkey 장애:

- 캐시 조회 실패는 WARN 후 MISS로 본다
- 소유권 획득 실패는 `PREREQUISITE_DEFINITION_FAILED` 502. 동시 생성 제한 없이 AI를 부르지 않는다. 결과적으로 Valkey가 죽어 있으면 AI 호출이 증폭되지 않는다
- 캐시 저장 실패는 WARN 후 생성한 설명을 그대로 반환한다
- `management.health.redis.enabled=false`. Valkey 장애가 BE health check를 떨어뜨려 task가 교체되지 않게 한다

관측은 Micrometer counter로 HIT, MISS 생성 성공, 생성 실패, 동시 생성 거절, 캐시 장애를 센다.

**결정 기록**

- D4-1 Valkey 장애 — 확정. 잠금을 못 얻으면 AI를 부르지 않고 502. 설명 생성만 멈추고 다른 기능은 영향 없다. ADR-011 반영은 보류.
- D4-2 소유권 만료 — 확정. AI 타임아웃 + 5초.

## 5. Frontend — 확정

Design v3를 따른다. 별도 아트보드가 없는 부분은 v2를 상속한다.

데이터:

- `api/types.ts`에 `PrerequisiteHighlight`, `PrerequisiteDefinitionResponse`, `PaperContentResponse.prerequisiteHighlights` 추가. `api/papers.ts`에 `createPrerequisiteDefinition`
- `adaptPaperContent`가 하이라이트를 `blockId`로 묶어 블록별 범위 목록을 만든다
- `knowledgeGraphStatus`가 READY로 바뀌면 `['paper-content', paperId]`를 한 번 무효화한다. 번역 READY 재조회 effect와 같은 방식이다. 별도 준비 상태는 만들지 않는다

하이라이트 렌더:

- 새 rehype 플러그인 `rehypePrerequisiteHighlight`. 텍스트 노드를 범위 경계에서 나눠 `<mark class="term-highlight" data-highlight-id role="button" tabindex="0" aria-expanded>`로 감싼다
- 범위는 원문 offset이므로 `sourceOffsetShift`를 더해 markdown offset으로 바꿔 넘긴다
- 순서는 `rehypePrerequisiteHighlight` → `rehypeSourcePos` → KaTeX. 하이라이트 플러그인이 텍스트 노드를 앞·하이라이트·뒤로 쪼갠 뒤, 기존 좌표 플러그인이 쪼개진 각 노드에 자기 `data-src-start/end`를 붙인다. `resolveOffset`이 "가장 가까운 span 시작 + 노드 안 위치"로 계산하므로 조각마다 좌표가 있어야 하이라이트 뒤쪽 선택 anchor가 밀리지 않는다. `mark` 안의 텍스트도 같은 규칙으로 span에 감싸인다
- 1:1 매핑이 증명되지 않은 텍스트 노드에 걸친 범위는 그리지 않는다. 본문 읽기를 막지 않는다
- 토글이 꺼져 있으면 컨테이너 class로 스타일만 지우고 클릭을 무시한다. 받은 배열은 유지한다

토글:

- `StudyTopBar` 오른쪽에 `role="switch"` 토글. 상태는 `StudyPage`의 `useState`, 처음에 꺼짐, 저장하지 않는다
- 하이라이트 배열이 비어 있으면 비활성이고 `title`에 `표시할 선행지식이 없습니다`
- 끄면 열린 팝오버도 닫는다

팝오버:

- 새 `PrerequisiteLayer`를 뷰어 칸의 `position:relative` 컨테이너 안에 `SelectionLayer`·`ContentAskLayer`와 형제로 둔다. 위치는 `computeToolbarPosition`을 재사용해 선택 구간 아래에 둔다
- 구성은 개념명, 영문 정의, 구분선, 한국어 설명. 태그·근거 문구·닫기 버튼은 없다. `role="dialog" aria-modal="false"`
- 상태는 loading → success 또는 failed. loading은 개념명 아래 점 3개, failed는 `설명을 불러오지 못했습니다.`. 재시도 버튼은 없고 다시 누르면 새 요청이다
- 닫힘은 캡처 단계 `mousedown`으로 판정한다. 팝오버 안, 선택한 하이라이트, 목차, AI 튜터 영역은 무시하고 그 밖의 뷰어 영역이면 닫는다. 다른 하이라이트를 누르면 그쪽으로 바꾼다
- 세 오버레이의 상호 닫힘은 `StudyPage`가 조율한다. 선행지식 팝오버가 열리면 선택 툴바·번역 팝업·질문하기 팝오버를 닫고, 선택 툴바나 질문하기가 열리면 선행지식 팝오버를 닫는다. 기존 두 레이어는 뷰어 안 클릭을 바깥으로 보지 않으므로 콜백으로 명시해야 한다
- 뷰어 스크롤 시 번역 팝업처럼 위치를 따라간다
- 성공한 설명은 페이지가 살아 있는 동안 `highlightId`별로 메모리에 두고 다시 열 때 로딩 없이 보여준다. 실패는 두지 않는다. 본문을 다시 조회하면 비운다. 화면용 임시 저장일 뿐 30일 정책은 서버 캐시가 지킨다
- 429를 포함한 모든 실패는 같은 실패 문구를 보여준다

**결정 기록**

- D5-1 FE 메모리 — 확정. 둔다. FT-012 Story 4의 "로딩 상태 없이 즉시 표시"를 지키기 위해서다.
- D5-2 429 표시 — 확정. 일반 실패 문구와 같다. Design v3가 실패 문구를 하나만 정의한다.

## 6. 테스트 — 확정

BE:

- `S3PaperPackageReaderTest` — 정상, artifact 없음, 파일 없음, schema·encoding 불일치, 불량 항목 건너뜀
- `DocumentPrerequisiteHighlightIngestIntegrationTest` — 적재, 재적재 교체, 없는 block 건너뜀, 글자 불일치 건너뜀, sidecar 없는 패키지
- `KnowledgeCompileResultConsumptionIntegrationTest` — compile 완료 메시지로 하이라이트가 적재되는지, sidecar 없이도 COMPLETED 종결되는지, 같은 completed 메시지 동시 2건에서 예외 없이 1세트만 남는지
- 본문 조회 통합 테스트 — compile 전 빈 배열, 완료 뒤 배열
- 설명 API 통합 테스트 — 가짜 AI 서버와 Testcontainers Valkey `valkey/valkey:7.2.14-alpine`로 MISS 생성, HIT 시 AI 미호출, 같은 Document의 같은 표기 공유, 실패 미저장 후 재생성, 동시 생성 429, 사용량 미차감, 404·409·403, AI 요청 body가 `selection.start.offset`·`selection.end.offset` 형태인지, Valkey 중단 시 AI 미호출과 502
- 단위 테스트 — 정규화와 해시, Markdown 분리, 소유권 해제 토큰 검증
- `FlywayMigrationTest`가 V2를 포함해 통과

FE:

- `rehypePrerequisiteHighlight` — 단일 범위, 한 블록 여러 범위, heading offset 보정, 인라인 수식 옆, 매핑 불가 노드, 이모지·보조평면 문자가 앞에 있을 때 UTF-16 offset 일치, 쪼개진 조각마다 `data-src-start`가 붙어 하이라이트 뒤쪽 선택 anchor가 맞는지
- `PrerequisiteLayer` — loading·success·failed, 바깥 클릭 닫힘과 무시 영역, 재열기 시 즉시 표시
- 토글 — 빈 배열 비활성과 툴팁, 끄면 팝오버 닫힘
- `StudyPage` — READY 전환 시 본문 재조회
- 기존 `SelectionLayer`·`ContentAskLayer`와의 상호 닫힘, 하이라이트가 있는 블록에서 선택 anchor 계산 회귀

dev 실화면 확인은 YMC-413 인프라 적용 뒤에 한다.

## 7. 커밋과 배포

커밋은 책임별로 나눈다.

1. BE 하이라이트 저장과 compile 적재
2. BE 기존 Document 일회성 채우기
3. BE 본문 조회 projection
4. BE 설명 API와 AI 호출
5. BE Valkey 캐시와 동시 생성 제한
6. FE 타입·API와 하이라이트 렌더
7. FE 토글과 팝오버

배포 순서는 YMC-413의 Valkey 인프라와 BE 환경변수가 먼저다. `PREREQUISITE_KNOWLEDGE_AGENT_ACTIVE_VARIANT`가 없으면 BE가 시작하지 않는다.

## 8. 제외

- 사용자가 고른 텍스트를 선행지식으로 등록, 설명 편집·수동 새로고침·이력
- 설명 부분 스트리밍
- 서로 다른 사용자의 동일 MISS 병합
- 분당 요청 수와 IP 기반 rate limit — YMC-350
- sidecar가 없는 기존 Document의 재compile
- AI 응답의 영문·국문 필드 분리 — AI 담당자와의 후속 논의
