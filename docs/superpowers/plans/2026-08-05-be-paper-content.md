# BE 논문 본문 적재·서빙 (YMC-298) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** parse-results COMPLETED 수신 시 S3 파서 패키지를 DB에 적재하고, `GET /api/papers/{paperId}/content`로 계약(`PaperContentResponse`)대로 서빙한다.

**Architecture:** 리스너와 분리된 `PaperContentIngestService`가 S3(manifest → frontend/document.json + structure 레지스트리)를 읽어 수식 tex·표 html을 인라인한 뒤 신규 3테이블에 저장. 조회는 SELECT 4개 고정 + 이미지 presigned GET URL 발급(만료 창 내 재사용). 패키지 읽기는 `PaperPackageReader` 포트 뒤에 격리. SQS 메시지 코드는 이번에 `sqs/messaging.yml` 0.2.0(snake_case·소문자 status·manifest_key 필수)으로 함께 동기화한다 (YMC-229 머지 반영).

**Tech Stack:** Spring Boot 3.5.15 · JPA(Hibernate 6, `@JdbcTypeCode(SqlTypes.JSON)` → JSONB) · spring-cloud-aws-sqs · AWS SDK v2 S3/Presigner · Testcontainers(PostgreSQL + LocalStack)

**Spec:** `docs/superpowers/specs/2026-08-05-paper-content-integration-design.md`

## Global Constraints

- 계약: `project-docs/contracts/frontend-backend/openapi.yaml`의 `getPaperContent` **초안**(로컬)이 기준. 응답 필드는 camelCase — `paperId, title, schemaVersion, blocks[], assets{}` / block: `blockId, globalOrder, label, headingLevel, sectionPath, content` / asset: `url, mediaType, expiresAt`.
- 에러: 404 `PAPER_NOT_FOUND` · 403 `FORBIDDEN` · 409 `PAPER_NOT_READY`(미완료·미적재 공용). **ErrorCode enum에 새 코드를 만들지 않는다** — 셋 다 이미 있다.
- 기존 테이블(`paper` 등) 무수정. 신규 테이블은 JPA 엔티티로 정의 (dev ddl-auto update).
- be/CLAUDE.md 준수: 엔티티 `@Getter`만·정적 팩토리, 빈은 `@RequiredArgsConstructor`+`final`, OSIV off(트랜잭션 안에서 DTO 데이터 완성), 컨텍스트 간 ID 참조, 포트는 외부 시스템에만.
- Jackson 전역 `fail-on-unknown-properties: true` — 파서 JSON 역직렬화 record에는 반드시 `@JsonIgnoreProperties(ignoreUnknown = true)`를 붙인다 (`ParseResultMessage` 패턴).
- 파서 산출물(snake_case)은 `@JsonProperty`로 명시 매핑. 파서 label은 **String으로 저장** (enum 진화는 계약 소유).
- 커밋: `[YMC-298] type(scope): subject` 한 줄. Claude attribution 금지.
- 테스트 실행: `cd be && ./gradlew test --tests '<클래스명>'` (Docker 필요 — Testcontainers).

## 파서 패키지 사실관계 (구현 근거)

- 패키지 루트: `manifestKey`에서 마지막 `/`까지가 prefix (예: `papers/{paperId}/manifest.json` → `papers/{paperId}/`).
- `manifest.json` → `artifacts.frontend_document.path`(= `frontend/document.json`, schema v1), `artifacts.structure_document.path`(= `structure/document.json`).
- `frontend/document.json`: `schema_version`, `blocks[]` — `block_id, global_block_order, block_label, heading_level, section_path[], block_content{format,...}`. `block_content.format`: `text`(text 필드) / `formula`·`table`·`image`(**asset_key 참조** — 내용은 asset 파일).
- asset 경로는 `structure/document.json`의 `assets` 레지스트리(`asset_key → {path, media_type}`)가 준다. tex·html은 적재 시 읽어 인라인, 이미지·차트는 `s3_key = prefix + path`만 기록.
- `chart` label의 content format은 `image`다 (label과 format은 독립).

---

### Task 1: FileStorage 포트 확장 — readUtf8 · presignAssetGet

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/service/port/FileStorage.java`
- Modify: `be/src/main/java/com/ymc/paper/infra/storage/S3FileStorage.java`
- Test: `be/src/test/java/com/ymc/paper/infra/storage/S3FileStorageContentIntegrationTest.java`

**Interfaces:**
- Consumes: 기존 `S3Client s3`, `S3Presigner presigner`, `AwsProperties props`, `PresignedDownload(String url, Instant expiresAt)`
- Produces: `String readUtf8(String fileKey)` — 객체 본문을 UTF-8 문자열로. `PresignedDownload presignAssetGet(String fileKey)` — Content-Disposition 없는(인라인 표시용) presigned GET.

- [ ] **Step 1: 실패하는 통합 테스트 작성**

```java
package com.ymc.paper.infra.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.service.port.FileStorage;
import com.ymc.paper.service.port.PresignedDownload;
import com.ymc.support.IntegrationTest;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** 본문 적재가 쓰는 S3 읽기·asset presign (YMC-298). */
class S3FileStorageContentIntegrationTest extends IntegrationTest {

    @Autowired
    FileStorage storage;

    @Test
    void readUtf8은_객체_본문을_문자열로_돌려준다() {
        put("papers/p1/assets/formulas/formula_0.tex", "E = mc^2");

        assertThat(storage.readUtf8("papers/p1/assets/formulas/formula_0.tex"))
                .isEqualTo("E = mc^2");
    }

    @Test
    void presignAssetGet은_다운로드_강제_없는_GET_URL을_발급한다() {
        put("papers/p1/assets/images/image_0.jpg", "fake-jpg-bytes");

        PresignedDownload presigned = storage.presignAssetGet("papers/p1/assets/images/image_0.jpg");

        assertThat(presigned.url()).contains("papers/p1/assets/images/image_0.jpg");
        assertThat(presigned.url()).doesNotContain("response-content-disposition");
        assertThat(presigned.expiresAt()).isAfter(java.time.Instant.now());
    }

    private void put(String key, String body) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(awsProperties.s3().bucket()).key(key).build(),
                RequestBody.fromBytes(body.getBytes(StandardCharsets.UTF_8)));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd be && ./gradlew test --tests 'S3FileStorageContentIntegrationTest'`
Expected: 컴파일 실패 — `readUtf8`·`presignAssetGet` 미정의

- [ ] **Step 3: 포트·구현 작성**

`FileStorage.java`에 추가 (기존 메서드는 그대로):

```java
/** 패키지 파일(manifest·document.json·tex·html)을 UTF-8 문자열로 읽는다 (YMC-298 적재용). */
String readUtf8(String fileKey);

/** 이미지·차트 asset의 인라인 표시용 presigned GET. 다운로드용과 달리 Content-Disposition을 싣지 않는다. */
PresignedDownload presignAssetGet(String fileKey);
```

`S3FileStorage.java`에 구현 추가:

```java
@Override
public String readUtf8(String fileKey) {
    return s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(props.s3().bucket())
                    .key(fileKey)
                    .build())
            .asUtf8String();
}

@Override
public PresignedDownload presignAssetGet(String fileKey) {
    PresignedGetObjectRequest presigned = presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                    .signatureDuration(props.s3().presignExpiry())
                    .getObjectRequest(GetObjectRequest.builder()
                            .bucket(props.s3().bucket())
                            .key(fileKey)
                            .build())
                    .build());
    return new PresignedDownload(presigned.url().toString(), presigned.expiration());
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd be && ./gradlew test --tests 'S3FileStorageContentIntegrationTest'`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add be/src/main/java/com/ymc/paper/service/port/FileStorage.java be/src/main/java/com/ymc/paper/infra/storage/S3FileStorage.java be/src/test/java/com/ymc/paper/infra/storage/S3FileStorageContentIntegrationTest.java
git commit -m "[YMC-298] feat(be): FileStorage에 readUtf8·presignAssetGet 추가"
```

---

### Task 2: 본문 엔티티 3종 + 리포지토리

**Files:**
- Create: `be/src/main/java/com/ymc/paper/domain/PaperContent.java`
- Create: `be/src/main/java/com/ymc/paper/domain/PaperContentBlock.java`
- Create: `be/src/main/java/com/ymc/paper/domain/PaperContentAsset.java`
- Create: `be/src/main/java/com/ymc/paper/domain/PaperContentRepository.java`
- Create: `be/src/main/java/com/ymc/paper/domain/PaperContentBlockRepository.java`
- Create: `be/src/main/java/com/ymc/paper/domain/PaperContentAssetRepository.java`
- Test: `be/src/test/java/com/ymc/paper/domain/PaperContentTest.java`

**Interfaces:**
- Consumes: 없음 (신규 도메인)
- Produces:
  - `PaperContent.of(UUID paperId, String title, int schemaVersion, Instant now)` / getter: `paperId, title, schemaVersion, ingestedAt`
  - `PaperContentBlock.of(UUID paperId, String blockId, int globalOrder, String label, Integer headingLevel, List<String> sectionPath, JsonNode content)` / getter 동일 명
  - `PaperContentAsset.of(UUID paperId, String assetKey, String s3Key, String mediaType)` / getter 동일 명
  - repo: `PaperContentRepository extends JpaRepository<PaperContent, UUID>` + `@Modifying deleteByPaperId(UUID)`; `PaperContentBlockRepository.findAllByPaperIdOrderByGlobalOrderAsc(UUID)` + `@Modifying deleteByPaperId(UUID)`; `PaperContentAssetRepository.findAllByPaperId(UUID)` + `@Modifying deleteByPaperId(UUID)`

- [ ] **Step 1: 실패하는 팩토리 단위 테스트 작성** (PaperTest 패턴 — 순수 단위)

```java
package com.ymc.paper.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class PaperContentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID PAPER_ID = UUID.randomUUID();

    @Test
    void PaperContent는_paperId와_시각이_필수다() {
        PaperContent content = PaperContent.of(PAPER_ID, "Attention Is All You Need", 1, Instant.now());
        assertThat(content.getPaperId()).isEqualTo(PAPER_ID);
        assertThat(content.getSchemaVersion()).isEqualTo(1);

        assertThatThrownBy(() -> PaperContent.of(null, "t", 1, Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void title은_null을_허용한다_파서가_제목을_못_찾은_문서() {
        PaperContent content = PaperContent.of(PAPER_ID, null, 1, Instant.now());
        assertThat(content.getTitle()).isNull();
    }

    @Test
    void 블록은_blockId와_content가_필수다() {
        PaperContentBlock block = PaperContentBlock.of(PAPER_ID, "p0000-b0002", 0, "doc_title", 1,
                List.of("p0000-b0002"), MAPPER.createObjectNode().put("format", "text").put("text", "제목"));
        assertThat(block.getBlockId()).isEqualTo("p0000-b0002");
        assertThat(block.getHeadingLevel()).isEqualTo(1);

        assertThatThrownBy(() -> PaperContentBlock.of(PAPER_ID, null, 0, "text", null, List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void asset은_key와_s3Key가_필수다() {
        PaperContentAsset asset = PaperContentAsset.of(PAPER_ID, "image_0",
                "papers/x/assets/images/image_0.jpg", "image/jpeg");
        assertThat(asset.getAssetKey()).isEqualTo("image_0");

        assertThatThrownBy(() -> PaperContentAsset.of(PAPER_ID, "image_0", null, "image/jpeg"))
                .isInstanceOf(NullPointerException.class);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd be && ./gradlew test --tests 'PaperContentTest'`
Expected: 컴파일 실패 — 엔티티 미정의

- [ ] **Step 3: 엔티티·리포지토리 작성**

`PaperContent.java`:

```java
package com.ymc.paper.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;

/**
 * 적재된 논문 본문의 헤더 — 행 존재 자체가 "적재 완료"의 기준이다 (YMC-298 spec §3.2).
 * paper와는 ID로만 연결한다 (be/CLAUDE.md 의존성 규칙).
 */
@Getter
@Entity
@Table(name = "paper_content")
public class PaperContent {

    @Id
    @Column(name = "paper_id", nullable = false, updatable = false)
    private UUID paperId;

    /** doc_title 블록의 텍스트. 파서가 제목을 못 찾은 문서는 null (계약 title). */
    @Column(name = "title")
    private String title;

    /** 파서 frontend projection의 schema_version. */
    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    protected PaperContent() {
        // JPA
    }

    private PaperContent(UUID paperId, String title, int schemaVersion, Instant ingestedAt) {
        this.paperId = paperId;
        this.title = title;
        this.schemaVersion = schemaVersion;
        this.ingestedAt = ingestedAt;
    }

    public static PaperContent of(UUID paperId, String title, int schemaVersion, Instant now) {
        Objects.requireNonNull(paperId, "paperId");
        Objects.requireNonNull(now, "now");
        return new PaperContent(paperId, title, schemaVersion, now);
    }
}
```

`PaperContentBlock.java`:

```java
package com.ymc.paper.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;

/**
 * 본문 블록 1개 = 1행. 수식 tex·표 html은 적재 시점에 content에 인라인돼 있다 (spec §3.3).
 * label은 파서 분류 문자열 그대로 저장한다 — enum은 계약이 소유하고, 새 label은 계약 PR부터다.
 * content는 계약 응답의 content 필드 형태({"format":"text","text":...} 등)로 저장해 조회 시 그대로 내보낸다.
 */
@Getter
@Entity
@Table(
        name = "paper_content_block",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_paper_content_block",
                columnNames = {"paper_id", "block_id"}),
        indexes = @Index(
                name = "ix_paper_content_block_order",
                columnList = "paper_id, global_order"))
public class PaperContentBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "paper_id", nullable = false, updatable = false)
    private UUID paperId;

    /** 파서가 부여한 안정 블록 id (예: p0002-b0006). FE 선택 anchor·DOM id로 쓰인다. */
    @Column(name = "block_id", nullable = false, updatable = false)
    private String blockId;

    @Column(name = "global_order", nullable = false, updatable = false)
    private int globalOrder;

    @Column(name = "label", nullable = false, length = 32)
    private String label;

    @Column(name = "heading_level")
    private Integer headingLevel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "section_path", nullable = false)
    private List<String> sectionPath;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", nullable = false)
    private JsonNode content;

    protected PaperContentBlock() {
        // JPA
    }

    private PaperContentBlock(UUID paperId, String blockId, int globalOrder, String label,
            Integer headingLevel, List<String> sectionPath, JsonNode content) {
        this.paperId = paperId;
        this.blockId = blockId;
        this.globalOrder = globalOrder;
        this.label = label;
        this.headingLevel = headingLevel;
        this.sectionPath = sectionPath;
        this.content = content;
    }

    public static PaperContentBlock of(UUID paperId, String blockId, int globalOrder, String label,
            Integer headingLevel, List<String> sectionPath, JsonNode content) {
        Objects.requireNonNull(paperId, "paperId");
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(sectionPath, "sectionPath");
        Objects.requireNonNull(content, "content");
        return new PaperContentBlock(paperId, blockId, globalOrder, label, headingLevel, sectionPath, content);
    }
}
```

`PaperContentAsset.java`:

```java
package com.ymc.paper.domain;

import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;

/**
 * 이미지·차트 asset 1개 = 1행. URL은 저장하지 않는다 — 조회 시 s3Key로 presigned GET을 발급한다 (spec §3.2).
 */
@Getter
@Entity
@Table(
        name = "paper_content_asset",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_paper_content_asset",
                columnNames = {"paper_id", "asset_key"}))
public class PaperContentAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "paper_id", nullable = false, updatable = false)
    private UUID paperId;

    /** 계약 assets 맵의 키 (예: image_0). 블록 content.assetKey가 이 값을 가리킨다. */
    @Column(name = "asset_key", nullable = false, updatable = false)
    private String assetKey;

    @Column(name = "s3_key", nullable = false, updatable = false)
    private String s3Key;

    @Column(name = "media_type", nullable = false)
    private String mediaType;

    protected PaperContentAsset() {
        // JPA
    }

    private PaperContentAsset(UUID paperId, String assetKey, String s3Key, String mediaType) {
        this.paperId = paperId;
        this.assetKey = assetKey;
        this.s3Key = s3Key;
        this.mediaType = mediaType;
    }

    public static PaperContentAsset of(UUID paperId, String assetKey, String s3Key, String mediaType) {
        Objects.requireNonNull(paperId, "paperId");
        Objects.requireNonNull(assetKey, "assetKey");
        Objects.requireNonNull(s3Key, "s3Key");
        Objects.requireNonNull(mediaType, "mediaType");
        return new PaperContentAsset(paperId, assetKey, s3Key, mediaType);
    }
}
```

리포지토리 3개 — 삭제는 전부 bulk JPQL(`@Modifying`)이다. 파생 delete는 행을 로드한 뒤
하나씩 지우고, 같은 트랜잭션에서 재적재 insert와 flush 순서가 얽혀 unique 제약이 깨질 수 있다.
bulk는 호출 즉시 실행돼 순서가 결정적이다.

`PaperContentRepository.java`:

```java
package com.ymc.paper.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PaperContentRepository extends JpaRepository<PaperContent, UUID> {

    @Modifying
    @Query("delete from PaperContent c where c.paperId = :paperId")
    void deleteByPaperId(UUID paperId);
}
```

`PaperContentBlockRepository.java`:

```java
package com.ymc.paper.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PaperContentBlockRepository extends JpaRepository<PaperContentBlock, Long> {

    /** 계약: blocks는 globalOrder 오름차순. 인덱스 (paper_id, global_order)를 탄다. */
    List<PaperContentBlock> findAllByPaperIdOrderByGlobalOrderAsc(UUID paperId);

    @Modifying
    @Query("delete from PaperContentBlock b where b.paperId = :paperId")
    void deleteByPaperId(UUID paperId);
}
```

`PaperContentAssetRepository.java`:

```java
package com.ymc.paper.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PaperContentAssetRepository extends JpaRepository<PaperContentAsset, Long> {

    List<PaperContentAsset> findAllByPaperId(UUID paperId);

    @Modifying
    @Query("delete from PaperContentAsset a where a.paperId = :paperId")
    void deleteByPaperId(UUID paperId);
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd be && ./gradlew test --tests 'PaperContentTest'`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add be/src/main/java/com/ymc/paper/domain/PaperContent*.java be/src/test/java/com/ymc/paper/domain/PaperContentTest.java
git commit -m "[YMC-298] feat(be): 본문 적재용 엔티티 3종·리포지토리 추가"
```

---

### Task 3: PaperPackageReader 포트 + S3 구현 (파서 패키지 → 계약형 변환)

**Files:**
- Create: `be/src/main/java/com/ymc/paper/service/port/PaperPackageReader.java`
- Create: `be/src/main/java/com/ymc/paper/service/port/ParsedPaperPackage.java`
- Create: `be/src/main/java/com/ymc/paper/infra/parsing/S3PaperPackageReader.java`
- Create: `be/src/test/resources/fixtures/paper-package/manifest.json`
- Create: `be/src/test/resources/fixtures/paper-package/frontend/document.json`
- Create: `be/src/test/resources/fixtures/paper-package/structure/document.json`
- Create: `be/src/test/resources/fixtures/paper-package/assets/formulas/formula_0.tex`
- Create: `be/src/test/resources/fixtures/paper-package/assets/tables/table_0.html`
- Test: `be/src/test/java/com/ymc/paper/infra/parsing/S3PaperPackageReaderTest.java`

**Interfaces:**
- Consumes: `FileStorage.readUtf8(String fileKey)` (Task 1)
- Produces:
  - `ParsedPaperPackage read(String manifestKey)` — 완전히 해석된 패키지 (tex·html 인라인 완료)
  - `record ParsedPaperPackage(String title, int schemaVersion, List<Block> blocks, List<Asset> assets)`
  - `record Block(String blockId, int globalOrder, String label, Integer headingLevel, List<String> sectionPath, JsonNode content)` — content는 계약 응답 형태
  - `record Asset(String assetKey, String s3Key, String mediaType)`

- [ ] **Step 1: 테스트 픽스처 작성** (파서 0.0v3 샘플의 축소판 — label 전 종류 커버)

`fixtures/paper-package/manifest.json`:

```json
{
  "manifest_version": 1,
  "document_id": "fixture",
  "page_count": 2,
  "parser": {"provider": "paddleocr", "model": "PaddleOCR-VL-1.6"},
  "artifacts": {
    "source_pdf": {"path": "original.pdf", "media_type": "application/pdf"},
    "structure_document": {"path": "structure/document.json", "media_type": "application/json", "schema_version": 2},
    "frontend_document": {"path": "frontend/document.json", "media_type": "application/json", "schema_version": 1},
    "ai_document": {"path": "ai/document.md", "media_type": "text/markdown"}
  },
  "assets": {"path": "assets", "registry": "structure_document"}
}
```

`fixtures/paper-package/frontend/document.json` (실물처럼 `page_index`·`block_bbox` 등 여분 필드 포함 — lenient 파싱 검증):

```json
{
  "schema_version": 1,
  "document_id": "fixture",
  "page_count": 2,
  "pages": [],
  "assets": {
    "image_0": {"kind": "image", "media_type": "image/jpeg"},
    "image_1": {"kind": "image", "media_type": "image/png"},
    "formula_0": {"kind": "formula", "media_type": "application/x-tex"},
    "table_0": {"kind": "table", "media_type": "text/html"}
  },
  "blocks": [
    {"block_id": "p0000-b0000", "page_index": 0, "global_block_order": 0, "block_label": "doc_title", "heading_level": 1, "section_path": ["p0000-b0000"], "block_bbox": [1, 2, 3, 4], "block_content": {"format": "text", "text": "Fixture Paper Title"}},
    {"block_id": "p0000-b0001", "global_block_order": 1, "block_label": "paragraph_title", "heading_level": 2, "section_path": ["p0000-b0000", "p0000-b0001"], "block_content": {"format": "text", "text": "Abstract"}},
    {"block_id": "p0000-b0002", "global_block_order": 2, "block_label": "abstract", "heading_level": null, "section_path": ["p0000-b0000", "p0000-b0001"], "block_content": {"format": "text", "text": "요약 텍스트"}},
    {"block_id": "p0000-b0003", "global_block_order": 3, "block_label": "text", "heading_level": null, "section_path": ["p0000-b0000", "p0000-b0001"], "block_content": {"format": "text", "text": "인라인 수식 $ x^{2} $ 포함 본문"}},
    {"block_id": "p0001-b0000", "global_block_order": 4, "block_label": "display_formula", "heading_level": null, "section_path": ["p0000-b0000"], "block_content": {"format": "formula", "asset_key": "formula_0"}},
    {"block_id": "p0001-b0001", "global_block_order": 5, "block_label": "table", "heading_level": null, "section_path": ["p0000-b0000"], "block_content": {"format": "table", "asset_key": "table_0"}},
    {"block_id": "p0001-b0002", "global_block_order": 6, "block_label": "image", "heading_level": null, "section_path": ["p0000-b0000"], "block_content": {"format": "image", "asset_key": "image_0"}},
    {"block_id": "p0001-b0003", "global_block_order": 7, "block_label": "figure_title", "heading_level": null, "section_path": ["p0000-b0000"], "block_content": {"format": "text", "text": "Figure 1: fixture"}},
    {"block_id": "p0001-b0004", "global_block_order": 8, "block_label": "chart", "heading_level": null, "section_path": ["p0000-b0000"], "block_content": {"format": "image", "asset_key": "image_1"}},
    {"block_id": "p0001-b0005", "global_block_order": 9, "block_label": "reference_content", "heading_level": null, "section_path": ["p0000-b0000"], "block_content": {"format": "text", "text": "[1] Fixture reference."}}
  ]
}
```

`fixtures/paper-package/structure/document.json` (assets 레지스트리만 쓴다 — 여분 키 포함):

```json
{
  "schema_version": 2,
  "document_id": "fixture",
  "page_count": 2,
  "pages": [],
  "blocks": [],
  "assets": {
    "image_0": {"kind": "image", "media_type": "image/jpeg", "path": "assets/images/image_0.jpg", "source_block_id": "p0001-b0002"},
    "image_1": {"kind": "image", "media_type": "image/png", "path": "assets/images/image_1.png", "source_block_id": "p0001-b0004"},
    "formula_0": {"kind": "formula", "media_type": "application/x-tex", "path": "assets/formulas/formula_0.tex", "source_block_id": "p0001-b0000"},
    "table_0": {"kind": "table", "media_type": "text/html", "path": "assets/tables/table_0.html", "source_block_id": "p0001-b0001"}
  }
}
```

`fixtures/paper-package/assets/formulas/formula_0.tex`:

```text
\mathrm{Attention}(Q, K, V) = \mathrm{softmax}\left(\frac{QK^T}{\sqrt{d_k}}\right)V
```

`fixtures/paper-package/assets/tables/table_0.html`:

```html
<table><tr><th>Model</th><th>BLEU</th></tr><tr><td>Transformer</td><td>28.4</td></tr></table>
```

- [ ] **Step 2: 실패하는 단위 테스트 작성** (FileStorage를 클래스패스 픽스처로 흉내)

```java
package com.ymc.paper.infra.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.paper.service.port.FileStorage;
import com.ymc.paper.service.port.ParsedPaperPackage;
import com.ymc.paper.service.port.PresignedDownload;
import com.ymc.paper.service.port.PresignedUpload;

class S3PaperPackageReaderTest {

    /** S3 대신 클래스패스 fixtures/paper-package/를 읽는 가짜 저장소. */
    private static final FileStorage FAKE_STORAGE = new FileStorage() {
        @Override
        public String readUtf8(String fileKey) {
            String resource = "/fixtures/paper-package/"
                    + fileKey.replaceFirst("^papers/p1/", "");
            try (InputStream in = S3PaperPackageReaderTest.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("픽스처 없음: " + resource);
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public PresignedUpload presignUpload(String fileKey, String contentType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PresignedDownload presignDownload(String fileKey, String filename) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PresignedDownload presignAssetGet(String fileKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(String fileKey) {
            throw new UnsupportedOperationException();
        }
    };

    private final S3PaperPackageReader reader =
            new S3PaperPackageReader(FAKE_STORAGE, new ObjectMapper());

    @Test
    void 패키지를_읽어_계약형으로_변환한다() {
        ParsedPaperPackage pkg = reader.read("papers/p1/manifest.json");

        assertThat(pkg.title()).isEqualTo("Fixture Paper Title");
        assertThat(pkg.schemaVersion()).isEqualTo(1);
        assertThat(pkg.blocks()).hasSize(10);
        assertThat(pkg.blocks()).extracting(ParsedPaperPackage.Block::globalOrder)
                .isSorted();
    }

    @Test
    void 수식과_표는_asset_파일_내용이_인라인된다() {
        ParsedPaperPackage pkg = reader.read("papers/p1/manifest.json");

        ParsedPaperPackage.Block formula = blockById(pkg, "p0001-b0000");
        assertThat(formula.content().get("format").asText()).isEqualTo("formula");
        assertThat(formula.content().get("tex").asText()).contains("softmax");
        assertThat(formula.content().has("assetKey")).isFalse();

        ParsedPaperPackage.Block table = blockById(pkg, "p0001-b0001");
        assertThat(table.content().get("format").asText()).isEqualTo("table");
        assertThat(table.content().get("html").asText()).contains("<table>");
    }

    @Test
    void 이미지와_차트는_assetKey_참조로_남고_asset_목록에_s3Key가_잡힌다() {
        ParsedPaperPackage pkg = reader.read("papers/p1/manifest.json");

        ParsedPaperPackage.Block image = blockById(pkg, "p0001-b0002");
        assertThat(image.content().get("format").asText()).isEqualTo("image");
        assertThat(image.content().get("assetKey").asText()).isEqualTo("image_0");

        assertThat(pkg.assets()).containsExactlyInAnyOrder(
                new ParsedPaperPackage.Asset("image_0", "papers/p1/assets/images/image_0.jpg", "image/jpeg"),
                new ParsedPaperPackage.Asset("image_1", "papers/p1/assets/images/image_1.png", "image/png"));
    }

    @Test
    void 제목_계열_블록은_headingLevel과_sectionPath를_보존한다() {
        ParsedPaperPackage pkg = reader.read("papers/p1/manifest.json");

        ParsedPaperPackage.Block title = blockById(pkg, "p0000-b0000");
        assertThat(title.label()).isEqualTo("doc_title");
        assertThat(title.headingLevel()).isEqualTo(1);

        ParsedPaperPackage.Block section = blockById(pkg, "p0000-b0001");
        assertThat(section.headingLevel()).isEqualTo(2);
        assertThat(section.sectionPath()).containsExactly("p0000-b0000", "p0000-b0001");
    }

    @Test
    void 레지스트리에_없는_asset_참조는_예외다() {
        assertThatThrownBy(() -> reader.read("papers/broken/manifest.json"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ParsedPaperPackage.Block blockById(ParsedPaperPackage pkg, String blockId) {
        return pkg.blocks().stream().filter(b -> b.blockId().equals(blockId)).findFirst().orElseThrow();
    }
}
```

`레지스트리에_없는_asset_참조는_예외다`용으로 픽스처를 하나 더 만든다 —
`fixtures/paper-package-broken/` 아래에 `manifest.json`(위와 동일 내용),
`frontend/document.json`(위에서 blocks를 `p0001-b0000` display_formula 하나만 남긴 버전),
`structure/document.json`(assets를 빈 객체 `{}`로 바꾼 버전)을 두고,
FAKE_STORAGE의 replaceFirst를 `"^papers/(p1|broken)/"` 패턴이 각각
`paper-package/`·`paper-package-broken/`으로 가게 분기한다:

```java
String resource = fileKey.startsWith("papers/broken/")
        ? "/fixtures/paper-package-broken/" + fileKey.substring("papers/broken/".length())
        : "/fixtures/paper-package/" + fileKey.substring("papers/p1/".length());
```

- [ ] **Step 3: 실패 확인**

Run: `cd be && ./gradlew test --tests 'S3PaperPackageReaderTest'`
Expected: 컴파일 실패 — 포트·구현 미정의

- [ ] **Step 4: 포트·모델·구현 작성**

`ParsedPaperPackage.java`:

```java
package com.ymc.paper.service.port;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 완전히 해석된 파서 패키지 — 수식 tex·표 html은 이미 인라인돼 있고, content는 계약
 * (openapi.yaml PaperContentBlock.content) 형태다. 적재 서비스는 이걸 그대로 저장만 한다.
 */
public record ParsedPaperPackage(
        String title,
        int schemaVersion,
        List<Block> blocks,
        List<Asset> assets) {

    public record Block(
            String blockId,
            int globalOrder,
            String label,
            Integer headingLevel,
            List<String> sectionPath,
            JsonNode content) {
    }

    /** 이미지·차트만 — tex·html은 블록에 인라인되므로 여기 없다 (계약 assets 주석). */
    public record Asset(String assetKey, String s3Key, String mediaType) {
    }
}
```

`PaperPackageReader.java`:

```java
package com.ymc.paper.service.port;

/**
 * S3의 파서 산출물 패키지를 읽어 계약형으로 변환한다 (YMC-298 spec §3.3).
 * 구현: {@code infra/parsing/S3PaperPackageReader}.
 */
public interface PaperPackageReader {

    /**
     * @param manifestKey 패키지 manifest.json의 S3 key. 마지막 '/'까지가 패키지 prefix다.
     * @throws IllegalStateException 패키지가 계약과 어긋남(파일 누락·asset 참조 불일치 등).
     *         재시도해도 같은 결과인 비복구 오류지만, 부분 적재를 남기지 않는 게 우선이라 예외로 올린다
     *         — SQS 재전달 5회 후 DLQ로 빠진다 (ADR-002).
     */
    ParsedPaperPackage read(String manifestKey);
}
```

`S3PaperPackageReader.java`:

```java
package com.ymc.paper.infra.parsing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ymc.paper.service.port.FileStorage;
import com.ymc.paper.service.port.PaperPackageReader;
import com.ymc.paper.service.port.ParsedPaperPackage;

import lombok.RequiredArgsConstructor;

/**
 * 파서 산출물(snake_case, ai repo S3_BUCKET_STRUCTURE) → 계약형(camelCase) 변환.
 *
 * <p>파서 JSON은 여분 필드(bbox·page_index 등)가 많고 스키마가 진화하므로 record마다
 * {@code @JsonIgnoreProperties}로 관대하게 받는다 — 전역 fail-on-unknown-properties를 우회해야 한다.
 *
 * <p>asset 경로의 SSOT는 structure/document.json의 assets 레지스트리다
 * (manifest.assets.registry = "structure_document"). frontend 문서의 assets 맵에는 경로가 없다.
 */
@Component
@RequiredArgsConstructor
public class S3PaperPackageReader implements PaperPackageReader {

    private final FileStorage fileStorage;
    private final ObjectMapper objectMapper;

    @Override
    public ParsedPaperPackage read(String manifestKey) {
        String prefix = packagePrefix(manifestKey);

        Manifest manifest = parse(fileStorage.readUtf8(manifestKey), Manifest.class, manifestKey);
        String frontendKey = prefix + required(manifest.artifacts().frontendDocument(), "frontend_document").path();
        String structureKey = prefix + required(manifest.artifacts().structureDocument(), "structure_document").path();

        FrontendDocument frontend = parse(fileStorage.readUtf8(frontendKey), FrontendDocument.class, frontendKey);
        StructureDocument structure = parse(fileStorage.readUtf8(structureKey), StructureDocument.class, structureKey);

        List<ParsedPaperPackage.Block> blocks = new ArrayList<>();
        List<ParsedPaperPackage.Asset> assets = new ArrayList<>();
        String title = null;

        for (FrontendBlock block : frontend.blocks()) {
            JsonNode content = resolveContent(block, structure.assets(), prefix, assets);
            if (title == null && "doc_title".equals(block.blockLabel())) {
                title = block.blockContent().path("text").asText(null);
            }
            blocks.add(new ParsedPaperPackage.Block(
                    block.blockId(),
                    block.globalBlockOrder(),
                    block.blockLabel(),
                    block.headingLevel(),
                    block.sectionPath() == null ? List.of() : block.sectionPath(),
                    content));
        }
        return new ParsedPaperPackage(title, frontend.schemaVersion(), List.copyOf(blocks), List.copyOf(assets));
    }

    /** block_content.format 기준으로 계약 content를 만든다. label이 아니라 format이다 — chart도 format은 image. */
    private JsonNode resolveContent(FrontendBlock block, Map<String, RegisteredAsset> registry,
            String prefix, List<ParsedPaperPackage.Asset> assets) {
        String format = block.blockContent().path("format").asText();
        return switch (format) {
            case "text" -> textContent(block);
            case "formula" -> inlined(block, registry, prefix, "formula", "tex");
            case "table" -> inlined(block, registry, prefix, "table", "html");
            case "image" -> imageContent(block, registry, prefix, assets);
            default -> throw new IllegalStateException(
                    "알 수 없는 block_content.format: %s (blockId=%s)".formatted(format, block.blockId()));
        };
    }

    private JsonNode textContent(FrontendBlock block) {
        String text = block.blockContent().path("text").asText(null);
        if (text == null) {
            throw new IllegalStateException("text 블록에 text가 없습니다: blockId=" + block.blockId());
        }
        return objectMapper.createObjectNode().put("format", "text").put("text", text);
    }

    private JsonNode inlined(FrontendBlock block, Map<String, RegisteredAsset> registry,
            String prefix, String format, String field) {
        RegisteredAsset asset = registeredAsset(block, registry);
        String body = fileStorage.readUtf8(prefix + asset.path());
        return objectMapper.createObjectNode().put("format", format).put(field, body);
    }

    private JsonNode imageContent(FrontendBlock block, Map<String, RegisteredAsset> registry,
            String prefix, List<ParsedPaperPackage.Asset> assets) {
        String assetKey = block.blockContent().path("asset_key").asText();
        RegisteredAsset asset = registeredAsset(block, registry);
        assets.add(new ParsedPaperPackage.Asset(assetKey, prefix + asset.path(), asset.mediaType()));
        ObjectNode content = objectMapper.createObjectNode();
        return content.put("format", "image").put("assetKey", assetKey);
    }

    private RegisteredAsset registeredAsset(FrontendBlock block, Map<String, RegisteredAsset> registry) {
        String assetKey = block.blockContent().path("asset_key").asText(null);
        if (assetKey == null) {
            throw new IllegalStateException("asset_key가 없습니다: blockId=" + block.blockId());
        }
        RegisteredAsset asset = registry.get(assetKey);
        if (asset == null || asset.path() == null) {
            throw new IllegalStateException(
                    "레지스트리에 없는 asset 참조: %s (blockId=%s)".formatted(assetKey, block.blockId()));
        }
        return asset;
    }

    private static String packagePrefix(String manifestKey) {
        int lastSlash = manifestKey.lastIndexOf('/');
        if (lastSlash < 0) {
            throw new IllegalStateException("패키지 prefix를 만들 수 없는 manifestKey: " + manifestKey);
        }
        return manifestKey.substring(0, lastSlash + 1);
    }

    private <T> T parse(String json, Class<T> type, String sourceKey) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("파서 산출물 역직렬화 실패: " + sourceKey, e);
        }
    }

    private static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, () -> "manifest.artifacts." + name + "가 없습니다.");
    }

    // ---- 파서 JSON 대응 record (snake_case, lenient) ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Manifest(Artifacts artifacts) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Artifacts(
                @JsonProperty("frontend_document") Artifact frontendDocument,
                @JsonProperty("structure_document") Artifact structureDocument) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Artifact(String path) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FrontendDocument(
            @JsonProperty("schema_version") int schemaVersion,
            List<FrontendBlock> blocks) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FrontendBlock(
            @JsonProperty("block_id") String blockId,
            @JsonProperty("global_block_order") int globalBlockOrder,
            @JsonProperty("block_label") String blockLabel,
            @JsonProperty("heading_level") Integer headingLevel,
            @JsonProperty("section_path") List<String> sectionPath,
            @JsonProperty("block_content") JsonNode blockContent) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StructureDocument(Map<String, RegisteredAsset> assets) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegisteredAsset(String path, @JsonProperty("media_type") String mediaType) {
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: `cd be && ./gradlew test --tests 'S3PaperPackageReaderTest'`
Expected: PASS (5 tests)

- [ ] **Step 6: Commit**

```bash
git add be/src/main/java/com/ymc/paper/service/port/PaperPackageReader.java be/src/main/java/com/ymc/paper/service/port/ParsedPaperPackage.java be/src/main/java/com/ymc/paper/infra/parsing/ be/src/test/java/com/ymc/paper/infra/parsing/ be/src/test/resources/fixtures/
git commit -m "[YMC-298] feat(be): 파서 패키지 리더 — S3 산출물을 계약형으로 변환"
```

---

### Task 4: PaperContentIngestService — 적재·멱등

**Files:**
- Create: `be/src/main/java/com/ymc/paper/service/PaperContentIngestService.java`
- Test: `be/src/test/java/com/ymc/paper/service/PaperContentIngestIntegrationTest.java`
- Modify: `be/src/test/java/com/ymc/support/IntegrationTest.java` (픽스처 패키지 S3 업로드 헬퍼 추가)

**Interfaces:**
- Consumes: `PaperPackageReader.read(String)` (Task 3), 리포지토리 3종 (Task 2)
- Produces:
  - `void ingest(UUID paperId, String manifestKey)` — 삭제 후 삽입, 트랜잭션 1개, 멱등
  - `boolean isIngested(UUID paperId)` — `paper_content` 행 존재 여부

- [ ] **Step 1: IntegrationTest에 픽스처 업로드 헬퍼 추가**

`IntegrationTest.java`에 추가:

```java
/** 클래스패스의 축소판 파서 패키지를 LocalStack S3의 주어진 prefix로 올린다 (YMC-298). */
protected String givenPackageOnS3(UUID paperId) {
    String prefix = "papers/" + paperId + "/";
    for (String relative : List.of(
            "manifest.json",
            "frontend/document.json",
            "structure/document.json",
            "assets/formulas/formula_0.tex",
            "assets/tables/table_0.html")) {
        byte[] body = readFixture("/fixtures/paper-package/" + relative);
        s3.putObject(PutObjectRequest.builder()
                        .bucket(awsProperties.s3().bucket())
                        .key(prefix + relative)
                        .build(),
                RequestBody.fromBytes(body));
    }
    return prefix + "manifest.json";
}

private static byte[] readFixture(String resource) {
    try (java.io.InputStream in = IntegrationTest.class.getResourceAsStream(resource)) {
        if (in == null) {
            throw new IllegalStateException("픽스처 없음: " + resource);
        }
        return in.readAllBytes();
    } catch (java.io.IOException e) {
        throw new IllegalStateException(e);
    }
}
```

`resetState()`에 신규 리포지토리 정리 추가 (필드 `@Autowired protected PaperContentRepository paperContentRepository;` 등 3종도 함께):

```java
paperContentBlockRepository.deleteAll();
paperContentAssetRepository.deleteAll();
paperContentRepository.deleteAll();
```

(`paperRepository.deleteAll()` **앞**에 둔다 — FK는 없지만 논리적 소속 순서.)

- [ ] **Step 2: 실패하는 통합 테스트 작성**

```java
package com.ymc.paper.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperContentBlock;
import com.ymc.paper.domain.PaperContentBlockRepository;
import com.ymc.paper.domain.PaperContentRepository;
import com.ymc.paper.domain.PaperContentAssetRepository;
import com.ymc.support.IntegrationTest;

class PaperContentIngestIntegrationTest extends IntegrationTest {

    @Autowired
    PaperContentIngestService ingestService;

    @Autowired
    PaperContentRepository contentRepository;

    @Autowired
    PaperContentBlockRepository blockRepository;

    @Autowired
    PaperContentAssetRepository assetRepository;

    @Test
    void 패키지를_적재하면_헤더_블록_asset이_저장된다() {
        Paper paper = givenProcessingPaper("ingest.pdf");
        String manifestKey = givenPackageOnS3(paper.getId());

        ingestService.ingest(paper.getId(), manifestKey);

        assertThat(ingestService.isIngested(paper.getId())).isTrue();
        assertThat(contentRepository.findById(paper.getId()).orElseThrow().getTitle())
                .isEqualTo("Fixture Paper Title");

        List<PaperContentBlock> blocks = blockRepository.findAllByPaperIdOrderByGlobalOrderAsc(paper.getId());
        assertThat(blocks).hasSize(10);
        assertThat(blocks.get(4).getContent().get("tex").asText()).contains("softmax");
        assertThat(blocks.get(5).getContent().get("html").asText()).contains("<table>");

        assertThat(assetRepository.findAllByPaperId(paper.getId()))
                .extracting("assetKey").containsExactlyInAnyOrder("image_0", "image_1");
    }

    @Test
    void 재적재는_중복_없이_대체된다_멱등() {
        Paper paper = givenProcessingPaper("reingest.pdf");
        String manifestKey = givenPackageOnS3(paper.getId());

        ingestService.ingest(paper.getId(), manifestKey);
        ingestService.ingest(paper.getId(), manifestKey);

        assertThat(blockRepository.findAllByPaperIdOrderByGlobalOrderAsc(paper.getId())).hasSize(10);
        assertThat(assetRepository.findAllByPaperId(paper.getId())).hasSize(2);
        assertThat(contentRepository.count()).isEqualTo(1);
    }

    @Test
    void 적재_전에는_isIngested가_false다() {
        Paper paper = givenProcessingPaper("not-yet.pdf");
        assertThat(ingestService.isIngested(paper.getId())).isFalse();
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `cd be && ./gradlew test --tests 'PaperContentIngestIntegrationTest'`
Expected: 컴파일 실패 — `PaperContentIngestService` 미정의

- [ ] **Step 4: 서비스 작성**

```java
package com.ymc.paper.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.paper.domain.PaperContent;
import com.ymc.paper.domain.PaperContentAsset;
import com.ymc.paper.domain.PaperContentAssetRepository;
import com.ymc.paper.domain.PaperContentBlock;
import com.ymc.paper.domain.PaperContentBlockRepository;
import com.ymc.paper.domain.PaperContentRepository;
import com.ymc.paper.service.port.PaperPackageReader;
import com.ymc.paper.service.port.ParsedPaperPackage;

import lombok.RequiredArgsConstructor;

/**
 * 파서 패키지 → DB 적재 (YMC-298 spec §3.3). 리스너와 분리된 서비스라 SQS 외 경로
 * (로컬 검증 스크립트·추후 어드민)에서도 호출 가능하다.
 *
 * <p>멱등: 삭제 후 삽입. 삭제는 bulk JPQL이라 호출 즉시 실행되고, 전체가 한 트랜잭션이라
 * 실패 시 부분 적재가 남지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PaperContentIngestService {

    private final PaperPackageReader packageReader;
    private final PaperContentRepository contentRepository;
    private final PaperContentBlockRepository blockRepository;
    private final PaperContentAssetRepository assetRepository;

    @Transactional
    public void ingest(UUID paperId, String manifestKey) {
        ParsedPaperPackage pkg = packageReader.read(manifestKey);

        blockRepository.deleteByPaperId(paperId);
        assetRepository.deleteByPaperId(paperId);
        contentRepository.deleteByPaperId(paperId);

        contentRepository.save(PaperContent.of(paperId, pkg.title(), pkg.schemaVersion(), Instant.now()));
        blockRepository.saveAll(pkg.blocks().stream()
                .map(b -> PaperContentBlock.of(paperId, b.blockId(), b.globalOrder(), b.label(),
                        b.headingLevel(), b.sectionPath(), b.content()))
                .toList());
        assetRepository.saveAll(pkg.assets().stream()
                .map(a -> PaperContentAsset.of(paperId, a.assetKey(), a.s3Key(), a.mediaType()))
                .toList());
    }

    @Transactional(readOnly = true)
    public boolean isIngested(UUID paperId) {
        return contentRepository.existsById(paperId);
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: `cd be && ./gradlew test --tests 'PaperContentIngestIntegrationTest'`
Expected: PASS (3 tests)

- [ ] **Step 6: Commit**

```bash
git add be/src/main/java/com/ymc/paper/service/PaperContentIngestService.java be/src/test/java/com/ymc/paper/service/PaperContentIngestIntegrationTest.java be/src/test/java/com/ymc/support/IntegrationTest.java
git commit -m "[YMC-298] feat(be): 본문 적재 서비스 — 삭제 후 삽입 멱등 적재"
```

---

### Task 5: 메시지 계약 0.2.0 동기화 + 리스너 → 적재 연결

> 배경: `contracts/backend-ai/sqs/messaging.yml` 0.2.0이 2026-08-05 main에 머지됐다 (YMC-229,
> breaking). wire는 snake_case(`paper_id`·`file_key`·`manifest_key`), status는 소문자
> `completed`/`failed`, completed에는 `message`·`manifest_key` 필수, 자유형식 `result` 삭제,
> 실패 코드는 enum 4종. 현행 BE 메시지 코드는 구계약(camelCase)이므로 여기서 함께 동기화한다.
> 실제 발행 주체가 아직 없어(검증 스크립트뿐) 클린 컷오버 — 구형 수신 호환은 두지 않는다.

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/infra/messaging/message/ParseResultMessage.java` (전면 재작성)
- Modify: `be/src/main/java/com/ymc/paper/infra/messaging/message/ParseRequestMessage.java` (snake_case 직렬화)
- Modify: `be/src/main/java/com/ymc/paper/service/ParseResultService.java`
- Modify: `be/src/main/java/com/ymc/paper/infra/messaging/ParseResultListener.java`
- Modify: `be/src/test/java/com/ymc/paper/infra/messaging/ParseResultConsumptionIntegrationTest.java` (기존 payload를 0.2.0 형식으로 이관)
- Modify: `be/src/test/java/com/ymc/paper/api/PaperUploadCompletionIntegrationTest.java` (발행 payload 검증이 있으면 `paper_id`/`file_key`로 이관)
- Test: `be/src/test/java/com/ymc/paper/infra/messaging/ParseResultContentIngestIntegrationTest.java`

**Interfaces:**
- Consumes: `PaperContentIngestService.ingest / isIngested` (Task 4), `PaperTransitions.markParsed`, `PaperRepository`
- Produces: `ParseResultService.apply(UUID paperId, PaperStatus terminal, String errorCode, String manifestKey)` — 시그니처 변경 (기존 3-인자 호출부는 리스너뿐). completed면 manifestKey는 항상 non-null(계약 필수).
- `ParseResultMessage` 0.2.0: `paperId()`(← `paper_id`), `terminalStatus()`(`completed`→COMPLETED · `failed`→FAILED), `errorCode()`, `manifestKey()`(← `manifest_key`), `contractViolation()`(completed인데 manifest_key 없음 / failed인데 error.code 없음 → 위반)

- [ ] **Step 1: 실패하는 통합 테스트 작성**

```java
package com.ymc.paper.infra.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.paper.service.PaperContentIngestService;
import com.ymc.support.IntegrationTest;

/** messaging.yml 0.2.0 wire 형식 기준 (YMC-229·YMC-298). */
class ParseResultContentIngestIntegrationTest extends IntegrationTest {

    @Autowired
    PaperContentIngestService ingestService;

    @Test
    void completed_메시지는_전이와_적재까지_수행한다() {
        Paper paper = givenProcessingPaper("with-manifest.pdf");
        String manifestKey = givenPackageOnS3(paper.getId());

        publishParseResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKey));
        awaitConsumed(parseResultQueueUrl());

        await().atMost(CONSUME_TIMEOUT).untilAsserted(() -> {
            assertThat(reload(paper.getId()).getStatus()).isEqualTo(PaperStatus.COMPLETED);
            assertThat(ingestService.isIngested(paper.getId())).isTrue();
        });
    }

    @Test
    void manifest_key_없는_completed는_계약_위반으로_폐기된다() {
        Paper paper = givenProcessingPaper("no-manifest.pdf");

        publishParseResult("""
                {"paper_id":"%s","status":"completed","message":"ok"}
                """.formatted(paper.getId()));
        awaitConsumed(parseResultQueueUrl());

        // 폐기 = 정상 소비(ack)하되 아무것도 반영하지 않는다
        assertThat(reload(paper.getId()).getStatus()).isEqualTo(PaperStatus.PROCESSING);
        assertThat(ingestService.isIngested(paper.getId())).isFalse();
    }

    @Test
    void 이미_COMPLETED인_논문에_재전달돼도_미적재면_적재한다() {
        // 시나리오: 전이는 커밋됐는데 적재가 실패해 메시지가 재전달된 경우 (spec §3.3 리스너 연결)
        Paper paper = givenProcessingPaper("redelivery.pdf");
        String manifestKey = givenPackageOnS3(paper.getId());
        paperTransitions.markParsed(paper.getId(), PaperStatus.COMPLETED, null);

        publishParseResult("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"%s"}
                """.formatted(paper.getId(), manifestKey));
        awaitConsumed(parseResultQueueUrl());

        await().atMost(CONSUME_TIMEOUT).untilAsserted(
                () -> assertThat(ingestService.isIngested(paper.getId())).isTrue());
    }

    @Test
    void failed_메시지는_전이만_하고_적재하지_않는다() {
        Paper paper = givenProcessingPaper("failed.pdf");

        publishParseResult("""
                {"paper_id":"%s","status":"failed","error":{"code":"PARSE_RETRIES_EXHAUSTED","message":"재시도 소진"}}
                """.formatted(paper.getId()));
        awaitConsumed(parseResultQueueUrl());

        assertThat(reload(paper.getId()).getStatus()).isEqualTo(PaperStatus.FAILED);
        assertThat(reload(paper.getId()).getErrorCode()).isEqualTo("PARSE_RETRIES_EXHAUSTED");
        assertThat(ingestService.isIngested(paper.getId())).isFalse();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd be && ./gradlew test --tests 'ParseResultContentIngestIntegrationTest'`
Expected: FAIL — 현행 `ParseResultMessage`는 `paperId`·`COMPLETED`(대문자)를 기대하므로
`paper_id`·소문자 status 메시지가 계약 위반으로 폐기되어 전이·적재 모두 일어나지 않는다

- [ ] **Step 3: 메시지·서비스·리스너 수정**

`ParseRequestMessage.java` — wire를 snake_case로 (serialization은 Jackson `@JsonProperty`):

```java
package com.ymc.paper.infra.messaging.message;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * project-docs/contracts/backend-ai/sqs/messaging.yml 0.2.0 `ParseRequest` 대응.
 * wire 필드는 snake_case다 (YMC-229).
 *
 * <p>파일 바이트가 아니라 file_key 참조만 보낸다 — 파싱 서버가 자체 권한으로 S3에서 읽는다 (ADR-001).
 * 스키마가 {@code additionalProperties: false}이므로 필드를 임의로 늘리지 않는다.
 */
public record ParseRequestMessage(
        @JsonProperty("paper_id") UUID paperId,
        @JsonProperty("file_key") String fileKey) {
}
```

`ParseResultMessage.java` 전면 재작성 (0.2.0 — `result` 삭제, `message`·`manifest_key` 추가):

```java
package com.ymc.paper.infra.messaging.message;

import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ymc.paper.domain.PaperStatus;

/**
 * project-docs/contracts/backend-ai/sqs/messaging.yml 0.2.0 `ParseResult` 대응 (YMC-229).
 * wire는 snake_case·status 소문자다. completed는 {@code manifest_key}·{@code message}가 필수,
 * failed는 {@code error.code}가 필수다.
 *
 * <p>계약은 {@code additionalProperties: false}지만 수신 측은 관대하게 둔다 — 모르는 필드가 하나
 * 늘었다고 결과를 잃는 편이 더 나쁘다. spec이 비복구로 규정한 위반만 {@link #contractViolation()}이 잡는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParseResultMessage(
        @JsonProperty("paper_id") UUID paperId,
        String status,
        String message,
        @JsonProperty("manifest_key") String manifestKey,
        ErrorDetail error) {

    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorDetail(String code, String message) {
    }

    /**
     * 재시도해도 달라지지 않는 계약 위반을 찾는다.
     *
     * @return 위반 사유. 유효하면 {@link Optional#empty()}
     */
    public Optional<String> contractViolation() {
        if (paperId == null) {
            return Optional.of("paper_id가 없습니다.");
        }
        if (status == null) {
            return Optional.of("status가 없습니다.");
        }
        if (terminalStatus() == null) {
            return Optional.of("파싱 서버가 낼 수 없는 status입니다: " + status);
        }
        if (terminalStatus() == PaperStatus.COMPLETED
                && (manifestKey == null || manifestKey.isBlank())) {
            return Optional.of("status=completed인데 manifest_key가 없습니다.");
        }
        if (terminalStatus() == PaperStatus.FAILED && errorCode() == null) {
            return Optional.of("status=failed인데 error.code가 없습니다.");
        }
        return Optional.empty();
    }

    /** 계약의 소문자 status를 BE {@link PaperStatus}로 매핑한다. 계약에 없는 값이면 null. */
    public PaperStatus terminalStatus() {
        if (STATUS_COMPLETED.equals(status)) {
            return PaperStatus.COMPLETED;
        }
        if (STATUS_FAILED.equals(status)) {
            return PaperStatus.FAILED;
        }
        return null;
    }

    /** 실패 코드 (계약 enum 4종). 해석하지 않고 저장만 한다. */
    public String errorCode() {
        if (error == null || error.code() == null || error.code().isBlank()) {
            return null;
        }
        return error.code();
    }
}
```

`ParseResultService.java` 전체 교체:

```java
package com.ymc.paper.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ymc.paper.domain.PaperRepository;
import com.ymc.paper.domain.PaperStatus;

import lombok.RequiredArgsConstructor;

/**
 * 파싱 결과 반영 — 상태 전이(UPLOADED|PROCESSING → COMPLETED|FAILED) + 본문 적재 (spec §3).
 *
 * <p>상태의 단일 writer는 BE다. 전이는 조건부 UPDATE라 중복 수신·이미 terminal인 레코드는 0 row가 되고,
 * 그때는 경고만 남기고 정상 소비한다.
 *
 * <p>적재는 전이 성공 여부와 독립적으로 판정한다(YMC-298): "COMPLETED 결과 + manifestKey 있음 +
 * 아직 미적재"면 적재한다. 전이가 커밋된 뒤 적재만 실패해 재전달된 메시지도 이 조건으로 복구된다.
 * 전이가 안 됐고 논문이 COMPLETED도 아니면(알 수 없는 paperId 등) 적재하지 않는다.
 *
 * <p>DB 연결 실패·적재 실패 등은 여기서 삼키지 않는다. 예외가 리스너까지 올라가야 SQS가 재전달한다.
 * 비복구 패키지(파일 누락 등)는 재전달 5회 후 DLQ로 빠진다 (ADR-002).
 */
@Service
@RequiredArgsConstructor
public class ParseResultService {

    private static final Logger log = LoggerFactory.getLogger(ParseResultService.class);

    private final PaperTransitions transitions;
    private final PaperRepository paperRepository;
    private final PaperContentIngestService ingestService;

    /**
     * @param terminal    {@code COMPLETED} 또는 {@code FAILED}
     * @param errorCode   실패 코드. {@code COMPLETED}면 null
     * @param manifestKey 파서 패키지 manifest S3 key. completed면 필수(계약 0.2.0), failed면 null
     */
    public void apply(UUID paperId, PaperStatus terminal, String errorCode, String manifestKey) {
        boolean transitioned = transitions.markParsed(paperId, terminal, errorCode);
        if (transitioned) {
            log.info("파싱 결과 반영: paperId={}, status={}", paperId, terminal);
        } else {
            log.warn("파싱 결과 미반영, 이미 terminal이거나 진행 전: paperId={}, status={}", paperId, terminal);
        }

        if (terminal != PaperStatus.COMPLETED || manifestKey == null) {
            return;
        }
        boolean completed = transitioned || paperRepository.findById(paperId)
                .map(p -> p.getStatus() == PaperStatus.COMPLETED)
                .orElse(false);
        if (completed && !ingestService.isIngested(paperId)) {
            ingestService.ingest(paperId, manifestKey);
            log.info("본문 적재 완료: paperId={}, manifestKey={}", paperId, manifestKey);
        }
    }
}
```

`ParseResultListener.java`의 호출부 한 줄 교체:

```java
parseResultService.apply(message.paperId(), message.terminalStatus(), message.errorCode(),
        message.manifestKey());
```

- [ ] **Step 4: 기존 소비·발행 테스트를 0.2.0 wire 형식으로 이관**

`ParseResultConsumptionIntegrationTest`의 `publishParseResult(...)` payload를 전부 신형식으로 바꾼다:
- `{"paperId":..,"status":"COMPLETED"}` → `{"paper_id":..,"status":"completed","message":"ok","manifest_key":"papers/<id>/manifest.json"}`
  (manifest_key는 실존하지 않아도 된다 — 이 테스트들은 전이 검증이 목적이므로, S3에 패키지가 없어
  적재가 예외를 내면 안 되는 케이스는 `givenPackageOnS3` 헬퍼로 패키지를 깔아 준다. 전이 실패·중복
  수신처럼 적재 단계에 도달하지 않는 케이스는 그대로 둬도 된다.)
- `{"paperId":..,"status":"FAILED","error":{"code":"X"}}` → `{"paper_id":..,"status":"failed","error":{"code":"PARSE_RETRIES_EXHAUSTED","message":"..."}}`
- "허용되지 않는 status" 케이스가 `"PROCESSING"`을 쓰고 있으면 그대로 유효하다(소문자가 아니므로 위반).
- `PaperUploadCompletionIntegrationTest`에 parse-requests 발행 payload를 JSON으로 검증하는 단언이
  있으면 `paperId`/`fileKey` → `paper_id`/`file_key`로 바꾼다 (없으면 건너뛴다).

- [ ] **Step 5: 통과 확인 + 회귀 확인**

Run: `cd be && ./gradlew test --tests 'ParseResultContentIngestIntegrationTest' --tests 'ParseResultConsumptionIntegrationTest' --tests 'PaperUploadCompletionIntegrationTest'`
Expected: PASS (신규 4 + 이관된 기존 전부)

- [ ] **Step 6: Commit** (계약 동기화와 적재 연결을 커밋 2개로 분리)

```bash
git add be/src/main/java/com/ymc/paper/infra/messaging/message/ be/src/test/java/com/ymc/paper/infra/messaging/ParseResultConsumptionIntegrationTest.java be/src/test/java/com/ymc/paper/api/PaperUploadCompletionIntegrationTest.java
git commit -m "[YMC-298] feat(be)!: SQS 메시지를 messaging.yml 0.2.0으로 동기화 — snake_case·소문자 status"
git add be/src/main/java/com/ymc/paper/service/ParseResultService.java be/src/main/java/com/ymc/paper/infra/messaging/ParseResultListener.java be/src/test/java/com/ymc/paper/infra/messaging/ParseResultContentIngestIntegrationTest.java
git commit -m "[YMC-298] feat(be): completed 수신 시 본문 적재 연결 — 재전달 복구 포함"
```

---

### Task 6: GET /api/papers/{paperId}/content — 조회 서비스·컨트롤러

**Files:**
- Create: `be/src/main/java/com/ymc/paper/service/PaperContentQueryService.java`
- Create: `be/src/main/java/com/ymc/paper/service/PaperContentView.java`
- Create: `be/src/main/java/com/ymc/paper/service/AssetUrlCache.java`
- Create: `be/src/main/java/com/ymc/paper/api/dto/PaperContentResponse.java`
- Modify: `be/src/main/java/com/ymc/paper/api/PaperController.java`
- Test: `be/src/test/java/com/ymc/paper/api/PaperContentIntegrationTest.java`

**Interfaces:**
- Consumes: 리포지토리 3종, `FileStorage.presignAssetGet`, `PresignedDownload(url, expiresAt)`
- Produces:
  - `PaperContentView getContent(UUID paperId, UUID ownerId)` — 트랜잭션 안에서 완성된 뷰 (OSIV off)
  - `record PaperContentView(UUID paperId, String title, int schemaVersion, List<Block> blocks, Map<String, Asset> assets)` / `Block(String blockId, int globalOrder, String label, Integer headingLevel, List<String> sectionPath, JsonNode content)` / `Asset(String url, String mediaType, Instant expiresAt)`
  - `PresignedDownload AssetUrlCache.issue(String s3Key)` — 만료 1분 전까지 동일 URL 재사용

- [ ] **Step 1: 실패하는 통합 테스트 작성**

```java
package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.paper.service.PaperContentIngestService;
import com.ymc.support.IntegrationTest;

class PaperContentIntegrationTest extends IntegrationTest {

    @Autowired
    PaperContentIngestService ingestService;

    /** COMPLETED + 적재까지 끝난 논문. */
    private Paper givenIngestedPaper() {
        Paper paper = givenProcessingPaper("content.pdf");
        paperTransitions.markParsed(paper.getId(), PaperStatus.COMPLETED, null);
        ingestService.ingest(paper.getId(), givenPackageOnS3(paper.getId()));
        return reload(paper.getId());
    }

    @Test
    void 적재된_논문의_본문을_계약형으로_돌려준다() throws Exception {
        Paper paper = givenIngestedPaper();

        mockMvc.perform(get("/api/papers/{id}/content", paper.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paperId").value(paper.getId().toString()))
                .andExpect(jsonPath("$.title").value("Fixture Paper Title"))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.blocks.length()").value(10))
                .andExpect(jsonPath("$.blocks[0].blockId").value("p0000-b0000"))
                .andExpect(jsonPath("$.blocks[0].label").value("doc_title"))
                .andExpect(jsonPath("$.blocks[0].headingLevel").value(1))
                .andExpect(jsonPath("$.blocks[4].content.format").value("formula"))
                .andExpect(jsonPath("$.blocks[5].content.html").exists())
                .andExpect(jsonPath("$.blocks[6].content.assetKey").value("image_0"))
                .andExpect(jsonPath("$.assets.image_0.url").exists())
                .andExpect(jsonPath("$.assets.image_0.mediaType").value("image/jpeg"))
                .andExpect(jsonPath("$.assets.image_0.expiresAt").exists())
                .andExpect(jsonPath("$.assets.formula_0").doesNotExist());
    }

    @Test
    void 없는_논문은_404_PAPER_NOT_FOUND() throws Exception {
        mockMvc.perform(get("/api/papers/{id}/content", UUID.randomUUID()).with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
    }

    @Test
    void 남의_논문은_403_FORBIDDEN() throws Exception {
        Paper paper = givenIngestedPaper();
        var otherJwt = SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString()));

        mockMvc.perform(get("/api/papers/{id}/content", paper.getId()).with(otherJwt))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 파싱_미완료면_409_PAPER_NOT_READY() throws Exception {
        Paper paper = givenProcessingPaper("processing.pdf");

        mockMvc.perform(get("/api/papers/{id}/content", paper.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_READY"));
    }

    @Test
    void 완료됐지만_미적재면_409_PAPER_NOT_READY() throws Exception {
        Paper paper = givenProcessingPaper("not-ingested.pdf");
        paperTransitions.markParsed(paper.getId(), PaperStatus.COMPLETED, null);

        mockMvc.perform(get("/api/papers/{id}/content", paper.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_READY"));
    }

    @Test
    void 만료_창_안에서는_같은_asset에_같은_URL을_재사용한다() throws Exception {
        Paper paper = givenIngestedPaper();

        String first = mockMvc.perform(get("/api/papers/{id}/content", paper.getId()).with(userJwt()))
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(get("/api/papers/{id}/content", paper.getId()).with(userJwt()))
                .andReturn().getResponse().getContentAsString();

        String firstUrl = objectMapper.readTree(first).at("/assets/image_0/url").asText();
        String secondUrl = objectMapper.readTree(second).at("/assets/image_0/url").asText();
        assertThat(firstUrl).isEqualTo(secondUrl);
    }

    @Test
    void 인증_없으면_401() throws Exception {
        mockMvc.perform(get("/api/papers/{id}/content", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd be && ./gradlew test --tests 'PaperContentIntegrationTest'`
Expected: FAIL — 404 (엔드포인트 없음)

- [ ] **Step 3: 서비스·뷰·캐시·DTO·컨트롤러 작성**

`PaperContentView.java`:

```java
package com.ymc.paper.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/** 계약 PaperContentResponse에 대응하는 서비스 뷰. 트랜잭션 안에서 완성한다 (OSIV off). */
public record PaperContentView(
        UUID paperId,
        String title,
        int schemaVersion,
        List<Block> blocks,
        Map<String, Asset> assets) {

    public record Block(
            String blockId,
            int globalOrder,
            String label,
            Integer headingLevel,
            List<String> sectionPath,
            JsonNode content) {
    }

    public record Asset(String url, String mediaType, Instant expiresAt) {
    }
}
```

`AssetUrlCache.java`:

```java
package com.ymc.paper.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.ymc.paper.service.port.FileStorage;
import com.ymc.paper.service.port.PresignedDownload;

import lombok.RequiredArgsConstructor;

/**
 * asset presigned GET URL 발급 + 만료 창 내 재사용 (계약 PaperContentAsset.url 주석 —
 * 같은 asset에 같은 URL을 돌려줘야 브라우저·중간 캐시가 산다).
 *
 * <p>인메모리 무한 캐시지만 키 수는 (논문 수 × 이미지 수)로 유계이고 값은 URL 문자열이라
 * MVP에서는 축출을 두지 않는다. 서버 재시작 시 비워지는 것도 무해하다 — 새 URL이 발급될 뿐이다.
 */
@Component
@RequiredArgsConstructor
public class AssetUrlCache {

    /** 만료 임박 URL을 돌려주지 않기 위한 여유. 이 이하로 남으면 재발급한다. */
    private static final Duration REUSE_MARGIN = Duration.ofMinutes(1);

    private final FileStorage fileStorage;
    private final ConcurrentHashMap<String, PresignedDownload> cache = new ConcurrentHashMap<>();

    public PresignedDownload issue(String s3Key) {
        PresignedDownload cached = cache.get(s3Key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now().plus(REUSE_MARGIN))) {
            return cached;
        }
        PresignedDownload fresh = fileStorage.presignAssetGet(s3Key);
        cache.put(s3Key, fresh);
        return fresh;
    }
}
```

`PaperContentQueryService.java`:

```java
package com.ymc.paper.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperContent;
import com.ymc.paper.domain.PaperContentAssetRepository;
import com.ymc.paper.domain.PaperContentBlockRepository;
import com.ymc.paper.domain.PaperContentRepository;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.paper.service.port.PresignedDownload;

import lombok.RequiredArgsConstructor;

/**
 * 파싱 본문 조회 (계약 getPaperContent). 호출 1회 = SELECT 4개 고정 — 블록 수와 무관하다 (spec §3.4).
 */
@Service
@RequiredArgsConstructor
public class PaperContentQueryService {

    private final PaperRepository paperRepository;
    private final PaperContentRepository contentRepository;
    private final PaperContentBlockRepository blockRepository;
    private final PaperContentAssetRepository assetRepository;
    private final AssetUrlCache assetUrlCache;

    /**
     * @throws ApiException PAPER_NOT_FOUND(404) — 논문 없음
     * @throws ApiException FORBIDDEN(403) — 소유자가 아님
     * @throws ApiException PAPER_NOT_READY(409) — COMPLETED가 아니거나 아직 미적재
     */
    @Transactional(readOnly = true)
    public PaperContentView getContent(UUID paperId, UUID ownerId) {
        Paper paper = paperRepository.findById(paperId).orElseThrow(
                () -> new ApiException(ErrorCode.PAPER_NOT_FOUND, "존재하지 않는 논문입니다."));
        if (!paper.getOwnerId().equals(ownerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "이 논문에 접근할 권한이 없습니다.");
        }
        if (paper.getStatus() != PaperStatus.COMPLETED) {
            throw new ApiException(ErrorCode.PAPER_NOT_READY,
                    "논문이 아직 완료 상태가 아닙니다: " + paper.getStatus());
        }
        PaperContent content = contentRepository.findById(paperId).orElseThrow(
                () -> new ApiException(ErrorCode.PAPER_NOT_READY, "본문이 아직 적재되지 않았습니다."));

        List<PaperContentView.Block> blocks = blockRepository
                .findAllByPaperIdOrderByGlobalOrderAsc(paperId).stream()
                .map(b -> new PaperContentView.Block(b.getBlockId(), b.getGlobalOrder(), b.getLabel(),
                        b.getHeadingLevel(), b.getSectionPath(), b.getContent()))
                .toList();

        Map<String, PaperContentView.Asset> assets = new LinkedHashMap<>();
        assetRepository.findAllByPaperId(paperId).forEach(a -> {
            PresignedDownload presigned = assetUrlCache.issue(a.getS3Key());
            assets.put(a.getAssetKey(),
                    new PaperContentView.Asset(presigned.url(), a.getMediaType(), presigned.expiresAt()));
        });

        return new PaperContentView(paperId, content.getTitle(), content.getSchemaVersion(), blocks, assets);
    }
}
```

`PaperContentResponse.java`:

```java
package com.ymc.paper.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.ymc.paper.service.PaperContentView;

/** 계약 `PaperContentResponse`. */
public record PaperContentResponse(
        UUID paperId,
        String title,
        int schemaVersion,
        List<Block> blocks,
        Map<String, Asset> assets) {

    public static PaperContentResponse from(PaperContentView view) {
        return new PaperContentResponse(
                view.paperId(),
                view.title(),
                view.schemaVersion(),
                view.blocks().stream()
                        .map(b -> new Block(b.blockId(), b.globalOrder(), b.label(),
                                b.headingLevel(), b.sectionPath(), b.content()))
                        .toList(),
                view.assets().entrySet().stream().collect(
                        java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                e -> new Asset(e.getValue().url(), e.getValue().mediaType(),
                                        e.getValue().expiresAt()),
                                (a, b) -> a,
                                java.util.LinkedHashMap::new)));
    }

    /** 계약 `PaperContentBlock`. content는 적재 시 계약형으로 저장돼 있어 그대로 내보낸다. */
    public record Block(
            String blockId,
            int globalOrder,
            String label,
            Integer headingLevel,
            List<String> sectionPath,
            JsonNode content) {
    }

    /** 계약 `PaperContentAsset`. */
    public record Asset(String url, String mediaType, Instant expiresAt) {
    }
}
```

`PaperController.java`에 추가 (필드 `private final PaperContentQueryService contentQueryService;`와 import 포함):

```java
/** 파싱된 논문 본문 조회 (계약 getPaperContent, YMC-298). */
@GetMapping("/{paperId}/content")
public PaperContentResponse content(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paperId) {
    UUID ownerId = UUID.fromString(jwt.getSubject());
    return PaperContentResponse.from(contentQueryService.getContent(paperId, ownerId));
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd be && ./gradlew test --tests 'PaperContentIntegrationTest'`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add be/src/main/java/com/ymc/paper/service/PaperContentQueryService.java be/src/main/java/com/ymc/paper/service/PaperContentView.java be/src/main/java/com/ymc/paper/service/AssetUrlCache.java be/src/main/java/com/ymc/paper/api/ be/src/test/java/com/ymc/paper/api/PaperContentIntegrationTest.java
git commit -m "[YMC-298] feat(be): GET /api/papers/{paperId}/content — 본문 조회·asset presign"
```

---

### Task 7: 로컬 검증 스크립트 (infra repo) + 전체 검증

**Files:** (⚠ `infra/`는 **별도 git repo** — app이 아니라 infra에서 커밋한다)
- Create: `infra/local/seed-paper-package.sh`
- Modify: `infra/local/publish-parse-result.sh`

**Interfaces:**
- Consumes: LocalStack S3/SQS (`ymc-documents`, `parse-results`), ai repo `docs/0.0v3/` 샘플 패키지
- Produces: `seed-paper-package.sh <paperId> [packageDir]` — 패키지 시딩 후 manifest_key 출력. `publish-parse-result.sh` — **messaging.yml 0.2.0 형식** 발행: `<paperId> COMPLETED <manifestKey>` / `<paperId> FAILED [errorCode]`

- [ ] **Step 1: 시딩 스크립트 작성**

`infra/local/seed-paper-package.sh`:

```bash
#!/usr/bin/env bash
# 파서 샘플 패키지를 LocalStack S3의 papers/{paperId}/로 시딩한다 (YMC-298 로컬 검증).
# AI 워커가 실제 발행할 때까지의 검증용 — publish-parse-result.sh와 짝으로 쓴다.
#
# 사용법:
#   ./seed-paper-package.sh <paperId> [packageDir]
#   packageDir 기본값: ../../ai/docs/0.0v3 (워크스페이스 clone 구조 전제)
set -euo pipefail

PAPER_ID="${1:?usage: seed-paper-package.sh <paperId> [packageDir]}"
PKG_DIR="${2:-"$(dirname "$0")/../../ai/docs/0.0v3"}"

export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-ap-northeast-2}"
ENDPOINT="${AWS_ENDPOINT_URL:-http://localhost:4566}"

aws --endpoint-url "$ENDPOINT" s3 sync "$PKG_DIR" "s3://ymc-documents/papers/$PAPER_ID/" \
  --exclude "original.pdf" --exclude "page_images/*" --exclude "page_layout_det_results/*"

echo "[seed-paper-package] done. manifestKey: papers/$PAPER_ID/manifest.json"
```

```bash
chmod +x infra/local/seed-paper-package.sh
```

- [ ] **Step 2: publish 스크립트를 messaging.yml 0.2.0 형식으로 갱신**

`infra/local/publish-parse-result.sh`의 기본값·BODY 조립부를 교체 — wire는 snake_case·소문자
status고 completed에는 `message`·`manifest_key`가 필수다. 기본 errorCode도 계약 enum에 있는
값으로 바꾼다 (`PDF_UNREADABLE`은 0.2.0 enum에 없다):

```bash
ERROR_CODE="${3:-PARSE_RETRIES_EXHAUSTED}"
```

```bash
if [ "$STATUS" = "FAILED" ]; then
  BODY=$(printf '{"paper_id":"%s","status":"failed","error":{"code":"%s","message":"manual publish"}}' \
    "$PAPER_ID" "$ERROR_CODE")
else
  MANIFEST_KEY="${3:?COMPLETED에는 manifestKey가 필수입니다 (계약 0.2.0): publish-parse-result.sh <paperId> COMPLETED <manifestKey>}"
  BODY=$(printf '{"paper_id":"%s","status":"completed","message":"manual publish","manifest_key":"%s"}' \
    "$PAPER_ID" "$MANIFEST_KEY")
fi
```

머리말 사용법 주석도 갱신 (계약 참조도 `contracts/backend-ai/sqs/messaging.yml`로):

```bash
#   ./publish-parse-result.sh <paperId> COMPLETED <manifestKey>
#   ./publish-parse-result.sh <paperId> FAILED [errorCode]
```

- [ ] **Step 3: infra repo 커밋**

```bash
cd infra && git status --short && git add local/seed-paper-package.sh local/publish-parse-result.sh
git commit -m "[YMC-298] feat(local): 파서 패키지 시딩·manifestKey 발행 스크립트"
```

- [ ] **Step 4: BE 전체 테스트 회귀 확인**

Run: `cd app/be && ./gradlew test`
Expected: BUILD SUCCESSFUL — 전체 테스트 통과 (기존 + 신규)

- [ ] **Step 5: 수동 E2E 런북 실행** (Stage 1 완료 기준, spec §5)

```bash
# 1. 로컬 인프라 + BE 기동
cd infra/local && ./up.sh
cd app/be && ./gradlew bootRun
# 2. FE(별도 터미널)로 로그인 → PDF 업로드 → PROCESSING 확인, paperId를 개발자도구/DB에서 확보
# 3. 패키지 시딩 + 완료 발행
cd infra/local
./seed-paper-package.sh <paperId>
./publish-parse-result.sh <paperId> COMPLETED papers/<paperId>/manifest.json
# 4. 본문 확인 (액세스 토큰은 FE localStorage에서 복사)
curl -s http://localhost:8080/api/papers/<paperId>/content -H "Authorization: Bearer <token>" | python3 -m json.tool | head -40
```

Expected: `status` COMPLETED 전이 후 content 200 — `title: "Attention Is All You Need"`, blocks 163개, `assets`에 presigned URL. 만료 창 내 재호출 시 같은 URL.

- [ ] **Step 6: 스펙 리스크 항목 기록 확인 후 마무리 커밋**

남은 후속(스펙 §6 그대로 — 이번 브랜치에서 하지 않음): openapi 확정 PR, AI 워커 실발행.
계획 문서 체크박스 갱신분이 있으면 함께 커밋:

```bash
cd app && git add docs/superpowers/plans/2026-08-05-be-paper-content.md
git commit -m "[YMC-298] docs(be): 구현 계획 체크박스 갱신"
```
