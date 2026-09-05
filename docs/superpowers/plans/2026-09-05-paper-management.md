# 서재 논문 관리(삭제·이름 변경·파일명 중복 허용) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 서재 행 케밥 메뉴에서 원본 다운로드·이름 변경·삭제를 제공하고, 소유자별 파일명 유니크 제약을 없앤다.

**Architecture:** BE는 `paper.deleted_at` 논리 삭제 + 사용자 경로 6곳만 `findActiveById`로 교체(내부 정산·정리 경로는 삭제 행을 계속 본다). `PATCH/DELETE /api/papers/{paperId}`를 새 `PaperManagementService`가 담당한다. FE는 `PaperRowMenu`(케밥)·`PaperTitleEditor`(인라인 편집)·`ConfirmDialog`(삭제 확인) 세 컴포넌트를 목록 행·격자 카드가 공유한다. 계약(openapi 0.4.0)·Feature·WF·design/v2는 project-docs PR로 선행한다.

**Tech Stack:** Spring Boot 3 / Spring Data JPA / PostgreSQL / Testcontainers+LocalStack(통합 테스트), React 18 + TanStack Query + Vitest + Testing Library, Phosphor Icons.

**Spec:** `docs/superpowers/specs/2026-09-05-paper-management-design.md` (YMC-369)

## Global Constraints

- 커밋 형식 `[YMC-369] type(scope): subject`, 단일 subject 줄. 커밋 메시지·PR 본문에 Co-Authored-By나 "Generated with Claude Code"를 넣지 않는다.
- 코드 주석은 핵심 1~2줄. 주석에 `(YMC-369)`·`(spec §)` 같은 출처 괄호를 쓰지 않는다.
- 계약 SSOT는 `project-docs/contracts/frontend-backend/openapi.yaml`. 코드 저장소 안에 스펙 복사본을 만들지 않는다.
- 파일명 검증: trim 후 1~255자. `.pdf` 접미는 FE가 붙이고 BE는 강제하지 않는다.
- 삭제된 논문은 사용자 경로에서 `404 PAPER_NOT_FOUND`. 삭제 시 사용량은 복구하지 않는다.
- `Content-Disposition` 파일명은 `"`와 제어 문자만 제거한다. 그 외(한글 포함)는 그대로.
- 이름 변경은 `updatedAt`을 갱신하지 않는다(상태 변경 시각).
- 이름 변경 진입은 케밥 메뉴 "이름 변경"뿐. 제목에 클릭·더블클릭 핸들러를 두지 않는다.
- 낙관적 업데이트 금지. 응답 후 `['papers']` 캐시(`{ papers: Paper[] }`)만 갱신.
- BE 테스트는 `./gradlew test`(Docker 필요), FE는 `npm run typecheck && npm test` (`app/fe`).
- PR 순서: project-docs → app. app PR 본문 `## 의존`에 project-docs PR 링크.

---

## 파일 구조

**project-docs (Part A)**

| 파일 | 변경 |
|---|---|
| `contracts/frontend-backend/openapi.yaml` | 0.4.0: `PATCH`·`DELETE /api/papers/{paperId}` 추가, `RenamePaperRequest` 스키마, POST 409·`DUPLICATE_FILENAME` 제거 |
| `features/FT-002-서재.md` | Out of Scope 정정, Story 5 진입점, Story 6(삭제)·7(이름 변경) 추가, Story 3 비고 |
| `features/FT-003-논문-등록-분석.md` | In Scope 문구, Story 2 AC 정정 + Resolved 노트 |
| `decisions/ADR-003-*.md` | Option C에 폐지 노트 |
| `wireframes/frames/WF-016-*.md` | C17 행 메뉴 컴포넌트, 스케치 `⋯` |
| `design/v2/Paper Bookshelf Page - Row Actions.dc.html` (신규) | 케밥 메뉴 열림·인라인 편집 상태 아트보드 |
| `design/v2/README.md` | 상태 표에 행 추가 |

**app/be (Part B)**

| 파일 | 책임 |
|---|---|
| `paper/domain/Paper.java` | 유니크 제약 제거, `filename` updatable, `deletedAt`, `rename`, `markDeleted` |
| `paper/domain/PaperRepository.java` | `findActiveById`, `markDeleted`, 목록 필터, 중복 판정 메서드 2개 삭제 |
| `chat/domain/ChatSessionRepository.java` | `markDeletedByPaperId` |
| `paper/service/PaperManagementService.java` (신규) | 삭제·이름 변경 |
| `paper/service/PaperDocumentViews.java` | `listView(Paper)` 편의 메서드 |
| `paper/service/PaperRegistrationService.java` | 중복 판정 제거 |
| `paper/service/{PaperStatusService, PaperContentQueryService, PaperDownloadService, PaperChatAccessValidator, PaperAccessRecorder, PaperUploadCompletionService}.java` | `findById` → `findActiveById` |
| `paper/api/PaperController.java`, `paper/api/dto/RenamePaperRequest.java` (신규) | PATCH·DELETE 엔드포인트 |
| `paper/infra/storage/S3FileStorage.java` | Content-Disposition 파일명 정리 |
| `common/error/ErrorCode.java` | `DUPLICATE_FILENAME` 삭제 |
| `be/docs/db/paper.sql` | `deleted_at` 추가, 유니크 제약 제거 |

**app/fe (Part C)**

| 파일 | 책임 |
|---|---|
| `src/api/papers.ts` | `renamePaper`, `deletePaper` |
| `src/design/components/icons.ts` | `dots-three`·`download-simple`·`pencil-simple`·`trash` 등록 |
| `src/routes/bookshelf/pdfName.ts` (신규) | `splitPdfName`·`joinPdfName` 순수 함수 |
| `src/routes/bookshelf/PaperRowMenu.tsx` (신규) | 케밥 + 드롭다운 |
| `src/routes/bookshelf/PaperTitleEditor.tsx` (신규) | 인라인 이름 편집 |
| `src/routes/bookshelf/ConfirmDialog.tsx` (신규) | 삭제 확인 모달 |
| `src/routes/BookshelfPage.tsx` | 행·카드에 메뉴·에디터 배선, 캐시 갱신, 토스트 |
| `src/routes/StudyPage.tsx` | 404 문구 |

---

# Part A — project-docs (선행 PR)

### Task A1: openapi 0.4.0

**Files:**
- Modify: `project-docs/contracts/frontend-backend/openapi.yaml`

**Interfaces:**
- Produces: `PATCH /api/papers/{paperId}` (`renamePaper`, 요청 `RenamePaperRequest`, 응답 `PaperListItem`), `DELETE /api/papers/{paperId}` (`deletePaper`, 204). Part B·C가 이 형태를 그대로 구현한다.

- [ ] **Step 1: 브랜치 생성**

```bash
cd /Users/geunhh/Desktop/team-ymc/project-docs
git fetch -q origin && git status --short && git switch -c YMC-369-paper-management origin/main
```

- [ ] **Step 2: version 올리기**

`version: 0.3.0` → `version: 0.4.0`.

- [ ] **Step 3: POST /api/papers 409 응답과 설명 정정**

`/api/papers` → `post` → `responses`에서 `"409":` 블록 전체(description·content 포함)를 삭제한다.

description 안의 다음 문단을 교체한다.

```yaml
        레코드 생성이 업로드보다 먼저다. 이 시점에 BE가 가진 것은 메타데이터뿐이라 내용 기반 유효성 판정은
        하지 않고 파일명 중복만 판정한다. (FT-003 Story 2)
```
→
```yaml
        레코드 생성이 업로드보다 먼저다. 이 시점에 BE가 가진 것은 메타데이터뿐이라 내용 기반 유효성 판정은
        하지 않는다. 파일명 중복은 허용한다 — 같은 이름의 논문이 여러 개 있을 수 있다. (FT-003 Story 2)
```

- [ ] **Step 4: PATCH·DELETE 경로 추가**

`/api/papers/{paperId}/complete:` 바로 위에 삽입한다.

```yaml
  /api/papers/{paperId}:
    patch:
      operationId: renamePaper
      summary: 논문 이름 변경
      description: |
        서재 제목이자 다운로드 저장 파일명인 `filename`을 바꾼다. 상태와 무관하게 허용한다.
        `updatedAt`은 상태 변경 시각이므로 갱신하지 않는다. 같은 이름의 다른 논문이 있어도 거절하지 않는다.
        `.pdf` 접미는 FE가 붙인다 — BE는 공백 여부와 길이만 검증한다.
      tags: [papers]
      parameters:
        - name: paperId
          in: path
          required: true
          schema:
            type: string
            format: uuid
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/RenamePaperRequest"
      responses:
        "200":
          description: 변경된 행. FE는 목록 캐시의 해당 항목만 이 값으로 교체한다.
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/PaperListItem"
        "400":
          description: "trim 후 비었거나 255자를 넘음. code: VALIDATION_ERROR"
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Error" }
        "401":
          description: "인증 필요. code: UNAUTHORIZED"
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Error" }
        "403":
          description: "소유자가 아님. code: FORBIDDEN"
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Error" }
        "404":
          description: "없거나 삭제된 논문. code: PAPER_NOT_FOUND"
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Error" }
    delete:
      operationId: deletePaper
      summary: 논문 논리 삭제
      description: |
        `deletedAt`을 기록해 논리 삭제한다. 이후 목록·status·content·download·chat 경로 전부에서
        PAPER_NOT_FOUND다. 소속 채팅 세션도 함께 논리 삭제한다.
        원본 객체와 파싱 결과는 같은 파일을 올린 다른 사용자와 공유하므로 유지한다.
        진행 중(PROCESSING) 논문도 삭제할 수 있다 — 파싱은 끝까지 진행되고 등록 사용량은 복구하지 않는다(FT-011).
      tags: [papers]
      parameters:
        - name: paperId
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        "204":
          description: 논리 삭제 완료
        "401":
          description: "인증 필요. code: UNAUTHORIZED"
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Error" }
        "403":
          description: "소유자가 아님. code: FORBIDDEN"
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Error" }
        "404":
          description: "없거나 이미 삭제된 논문. code: PAPER_NOT_FOUND"
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Error" }
```

- [ ] **Step 5: 스키마 추가·정정**

`PaperListItem` 정의 바로 뒤에 추가:

```yaml
    RenamePaperRequest:
      type: object
      required: [filename]
      properties:
        filename:
          type: string
          minLength: 1
          maxLength: 255
          description: 새 파일명. BE가 trim한다. FE는 확장자 `.pdf`를 붙여 보낸다.
          example: attention-is-all-you-need-v2.pdf
```

`PaperListItem.filename`의 description을 다음으로 교체:

```yaml
          description: 원본 파일명. MVP는 이것을 행의 제목으로 쓴다 (파싱이 추출한 논문 제목이 아니다). 사용자가 PATCH로 바꿀 수 있다.
```

`Error.code` description 목록에서 `- DUPLICATE_FILENAME    : 같은 파일명의 논문이 이미 있음 (409, create)` 줄을 삭제하고, `enum` 배열에서 `DUPLICATE_FILENAME,` 줄을 삭제한다.

- [ ] **Step 6: 검증**

```bash
grep -n "DUPLICATE_FILENAME" contracts/frontend-backend/openapi.yaml; echo "exit=$? (1이어야 함)"
grep -n "operationId: renamePaper\|operationId: deletePaper\|RenamePaperRequest:\|version: 0.4.0" contracts/frontend-backend/openapi.yaml
```
Expected: 첫 grep 결과 없음, 두 번째 grep 4줄.

- [ ] **Step 7: 커밋**

```bash
git add contracts/frontend-backend/openapi.yaml
git commit -m "[YMC-369] docs(contracts): openapi 0.4.0 — 논문 이름 변경·삭제 추가, 파일명 중복 409 제거"
```

---

### Task A2: Feature·ADR·WF 문서

**Files:**
- Modify: `project-docs/features/FT-002-서재.md`
- Modify: `project-docs/features/FT-003-논문-등록-분석.md`
- Modify: `project-docs/decisions/ADR-003-paper-id-and-duplicate-check.md`
- Modify: `project-docs/wireframes/frames/WF-016-서재-페이지-논문-목록-기본-상태.md`

- [ ] **Step 1: FT-002 Out of Scope·Story 5**

Out of Scope에서 `- 논문 삭제 / 이름 변경 / 메타 편집 → 후속(post-MVP)` 줄을 `- 논문 메타 편집(저자·연도 등) → 후속(post-MVP)`로 바꾼다.

Story 5 Acceptance Criteria 첫 줄을 다음으로 교체:

```markdown
  - `COMPLETED` 상태 논문 행의 행 메뉴(⋯)에서 원본 PDF 다운로드를 제공한다. 다른 상태의 행에서는 메뉴 항목이 비활성이다.
```

- [ ] **Step 2: FT-002 Story 3 비고**

Story 3 AC의 `EXPIRED` 표 아래(표가 끝난 다음 줄)에 추가:

```markdown
  - `EXPIRED`·`FAILED` 행은 자동으로 정리되지 않는다. 사용자가 Story 6의 삭제로 정리한다.
```

- [ ] **Step 3: FT-002 Story 6·7 추가**

Story 5 블록 끝(`- Depends on: Story 3`) 뒤에 추가:

```markdown
### Story 6. 사용자는 서재에서 논문을 삭제할 수 있다

- type: USER
- Source Userflows: UF-002 Step 5
- Tracking: YMC-369 / `DELETE /api/papers/{paperId}`
- Acceptance Criteria:
  - 모든 상태의 논문 행 메뉴(⋯)에 삭제가 있다. 선택하면 파일명과 "채팅 기록도 함께 사라지며 되돌릴 수 없다, 등록 횟수는 복구되지 않는다"를 알리는 확인 다이얼로그가 뜬다.
  - 확인하면 행이 목록에서 사라지고 토스트로 알린다. 이후 그 논문의 학습 페이지·채팅은 열리지 않는다.
  - 삭제는 논리 삭제다. 같은 파일을 올린 다른 사용자의 논문·원본·파싱 결과에 영향이 없다.
  - 진행 중(분석 중) 논문도 삭제할 수 있다. 문서 등록 사용량은 복구하지 않는다(FT-011).
- Depends on: Story 2

### Story 7. 사용자는 논문 이름을 바꿀 수 있다

- type: USER
- Source Userflows: UF-002 Step 5
- Tracking: YMC-369 / `PATCH /api/papers/{paperId}`
- Acceptance Criteria:
  - 행 메뉴(⋯)의 "이름 변경"을 선택하면 제목이 그 자리에서 입력창으로 바뀐다. 확장자 `.pdf`는 고정 표시되고 편집할 수 없다.
  - Enter 또는 포커스 아웃으로 저장, Esc로 취소한다. 비었거나 원래와 같으면 저장하지 않는다.
  - 바뀐 이름은 서재 제목과 다운로드 저장 파일명 양쪽에 반영된다.
  - 같은 이름의 다른 논문이 있어도 허용한다(FT-003 Story 2).
- Depends on: Story 2
```

- [ ] **Step 4: FT-003 In Scope·Story 2**

In Scope의 `- **파일명/논문 중복 판정** 후 논문 항목 생성 (내용 기반 유효성 판정은 MVP 범위 밖 — 아래 Resolved)` 를
`- 등록 요청 검증(형식·크기) 후 논문 항목 생성 (파일명 중복 허용, 내용 기반 유효성 판정은 MVP 범위 밖 — 아래 Resolved)` 로 바꾼다.

Story 1 AC의 `- 파일명이 중복이 아니면 논문이 저장되어 서재에 항목으로 추가된다(WF-006).` 를 `- 검증을 통과하면 논문이 저장되어 서재에 항목으로 추가된다(WF-006). 같은 파일명이 이미 있어도 등록된다.` 로 바꾼다.

Story 2 블록을 다음으로 교체:

```markdown
### Story 2. 시스템은 등록 요청을 검증하고 레코드를 생성한다 `MVP`

- type: SYSTEM
- Source Userflows: UF-002 Step 3-4
- Acceptance Criteria:
  - `POST /api/papers`는 파일명 중복을 판정하지 않는다. 같은 소유자에게 같은 파일명의 논문이 여러 개 있을 수 있다.
  - 레코드를 `UPLOAD_PENDING`으로 생성하고 presigned PUT URL을 발급한다.
  - 신고된 크기가 최대 용량을 넘으면 레코드를 만들지 않고 `413 FILE_TOO_LARGE`를 반환한다.
  - 신고된 크기를 서명에 넣어, 다른 바이트 수의 업로드는 S3가 거절하게 한다.
- Depends on: -
```

§6 Open Questions 끝에 추가:

```markdown
> **Resolved (2026-09-05, YMC-369)** — 파일명 중복 판정 폐지: MVP의 소유자별 파일명 1:1 규칙(`409 DUPLICATE_FILENAME`)은 같은 이름의 다른 파일을 올릴 수 없게 만들었고, 동일 바이트 중복은 Story 7(ADR-003)이 이미 잡는다. 유니크 제약과 409를 제거했다. 만료 잔재를 같은 파일명 재등록이 대체하던 동작도 사라지며, 정리는 FT-002 Story 6의 삭제가 맡는다.
```

- [ ] **Step 5: ADR-003 노트**

Option C 문단 끝(`... UX 규칙에만 사용한다.`) 뒤에 한 줄 추가:

```markdown

> 2026-09-05: 위 UX 규칙(`409 DUPLICATE_FILENAME`)은 YMC-369로 폐지했다. 파일명 중복은 허용하고 동일 파일 판정은 checksum(Option A)만 쓴다.
```

- [ ] **Step 6: WF-016 C17**

Component Inventory 표 C13 줄 뒤에 추가:

```markdown
| C17 | 행 메뉴 버튼 | R4 | Icon Button | `⋯`를 눌러 원본 PDF 다운로드 / 이름 변경 / 삭제 메뉴를 연다. 이름 변경은 C11 제목을 그 자리에서 입력창으로 바꾼다 |
```

스케치의 논문 행 첫 줄을 `│ [문서]  Attention Is All You Need                     [⋯] │` 로 바꾼다.

- [ ] **Step 7: 커밋**

```bash
git add features/FT-002-서재.md features/FT-003-논문-등록-분석.md decisions/ADR-003-*.md wireframes/frames/WF-016-*.md
git commit -m "[YMC-369] docs(features): 서재 삭제·이름 변경 Story 추가, 파일명 중복 판정 폐지 반영"
```

---

### Task A3: design/v2 행 액션 아트보드

**Files:**
- Create: `project-docs/design/v2/Paper Bookshelf Page - Row Actions.dc.html`
- Modify: `project-docs/design/v2/README.md`

- [ ] **Step 1: 원본 복사**

```bash
cd "/Users/geunhh/Desktop/team-ymc/project-docs/design/v2"
cp "Paper Bookshelf Page.dc.html" "Paper Bookshelf Page - Row Actions.dc.html"
```

- [ ] **Step 2: 목록 행에 케밥·메뉴·인라인 편집 추가**

복사본에서 `<!-- R4 Paper list -->` 아래 목록 뷰의 `<a href="{{ paper.href }}" ...>` 행을 다음으로 교체한다(`<sc-for ...>`와 `</sc-for>`는 그대로 둔다).

```html
              <div style="position:relative;display:flex;align-items:center;gap:16px;padding:14px 16px;background:var(--color-bg-surface);border:1px solid var(--color-border);border-radius:var(--radius-structural);cursor:pointer;transition:border-color 150ms ease" style-hover="border-color:var(--color-primary)">
                <div style="width:52px;height:68px;flex-shrink:0;background:var(--color-bg-paper);border:1px solid var(--color-border);border-radius:2px;display:flex;align-items:center;justify-content:center">
                  <i class="ph ph-file-text" style="font-size:22px;color:var(--color-text-muted)"></i>
                </div>
                <sc-if value="{{ renaming }}">
                  <div style="flex:1;min-width:0;display:flex;align-items:center;gap:8px">
                    <input value="{{ paper.stem }}" aria-label="새 이름" style="flex:1;min-width:0;font-family:var(--font-serif);font-size:16px;font-weight:600;color:var(--color-text-heading);background:var(--color-bg-paper);border:1px solid var(--color-primary);border-radius:var(--radius-structural);padding:5px 10px;outline:none"/>
                    <span style="font-family:var(--font-serif);font-size:16px;font-weight:600;color:var(--color-text-muted)">.pdf</span>
                    <span style="font-family:var(--font-sans);font-size:11px;color:var(--color-text-muted);white-space:nowrap">Enter 저장 · Esc 취소</span>
                  </div>
                </sc-if>
                <sc-if value="{{ notRenaming }}">
                  <div style="flex:1;min-width:0">
                    <div style="font-family:var(--font-serif);font-size:16px;font-weight:600;color:var(--color-text-heading);white-space:nowrap;overflow:hidden;text-overflow:ellipsis">{{ paper.title }}</div>
                  </div>
                  <div style="font-family:var(--font-sans);font-size:12px;color:var(--color-text-muted);flex-shrink:0;white-space:nowrap">{{ paper.registered }}</div>
                </sc-if>
                <x-import component-from-global-scope="PaperTeacherDesignSystem_1a53a7.IconButton" icon="dots-three" label="더 보기" size="32" onClick="{{ toggleRowMenu }}" hint-size="32px,32px"></x-import>
                <sc-if value="{{ rowMenuOpen }}">
                  <div style="position:absolute;right:12px;top:calc(100% - 6px);z-index:20;min-width:180px;background:var(--color-bg-paper);border:1px solid var(--color-border);border-radius:8px;box-shadow:var(--shadow-menu);padding:6px;display:flex;flex-direction:column;gap:2px">
                    <button style="display:flex;align-items:center;gap:10px;text-align:left;padding:9px 10px;border:none;background:transparent;border-radius:6px;font-family:var(--font-sans);font-size:13px;color:var(--color-text-body);cursor:pointer;white-space:nowrap" style-hover="background:var(--color-primary-subtle)"><i class="ph ph-download-simple" style="font-size:16px"></i>원본 PDF 다운로드</button>
                    <button onClick="{{ startRename }}" style="display:flex;align-items:center;gap:10px;text-align:left;padding:9px 10px;border:none;background:transparent;border-radius:6px;font-family:var(--font-sans);font-size:13px;color:var(--color-text-body);cursor:pointer;white-space:nowrap" style-hover="background:var(--color-primary-subtle)"><i class="ph ph-pencil-simple" style="font-size:16px"></i>이름 변경</button>
                    <div style="height:1px;background:var(--color-border);margin:2px 6px"></div>
                    <button onClick="{{ openDeleteConfirm }}" style="display:flex;align-items:center;gap:10px;text-align:left;padding:9px 10px;border:none;background:transparent;border-radius:6px;font-family:var(--font-sans);font-size:13px;color:var(--color-danger);cursor:pointer;white-space:nowrap" style-hover="background:var(--color-primary-subtle)"><i class="ph ph-trash" style="font-size:16px"></i>삭제</button>
                  </div>
                </sc-if>
              </div>
```

- [ ] **Step 3: 삭제 확인 다이얼로그 추가**

`<!-- Upload dialog -->` 바로 위에 삽입:

```html
  <!-- Delete confirm dialog -->
  <sc-if value="{{ deleteConfirmOpen }}">
    <div onClick="{{ closeDeleteConfirm }}" style="position:fixed;inset:0;background:rgba(31,53,82,0.35);z-index:60;display:flex;align-items:center;justify-content:center">
      <div onClick="{{ stopPropagation }}" role="dialog" style="width:360px;max-width:90vw;background:var(--color-bg-paper);border:1px solid var(--color-border);border-radius:var(--radius-control);box-shadow:var(--shadow-menu);padding:22px 22px 18px;box-sizing:border-box">
        <div style="font-family:var(--font-serif);font-size:16px;font-weight:600;color:var(--color-text-heading);margin-bottom:8px">논문을 삭제할까요?</div>
        <div style="font-family:var(--font-sans);font-size:13px;color:var(--color-text-body);line-height:1.6;margin-bottom:18px"><span style="font-family:var(--font-serif);font-weight:600;color:var(--color-text-heading)">{{ deleteTargetName }}</span>를 서재에서 지웁니다. 이 논문의 채팅 기록도 함께 사라지며 되돌릴 수 없습니다. 등록 횟수는 복구되지 않습니다.</div>
        <div style="display:flex;justify-content:flex-end;gap:8px">
          <button onClick="{{ closeDeleteConfirm }}" style="font-family:var(--font-sans);font-weight:600;font-size:12px;border-radius:var(--radius-control);padding:7px 12px;cursor:pointer;border:1px solid var(--color-border);background:var(--color-bg-paper);color:var(--color-text-body)">취소</button>
          <button onClick="{{ closeDeleteConfirm }}" style="font-family:var(--font-sans);font-weight:600;font-size:12px;border-radius:var(--radius-control);padding:7px 12px;cursor:pointer;border:1px solid var(--color-danger);background:var(--color-danger);color:#FFFDF7">삭제</button>
        </div>
      </div>
    </div>
  </sc-if>
```

- [ ] **Step 4: 상태·핸들러 추가**

`<script>` 안 컴포넌트 클래스에서 초기 `state` 객체(`uploadOpen`·`profileMenuOpen`이 선언된 곳)에 `rowMenuOpen: true, renaming: false, deleteConfirmOpen: false`를 추가한다. `mockUpload = () => {...}` 아래에 메서드를 추가:

```js
  toggleRowMenu = () => this.setState({ rowMenuOpen: !this.state.rowMenuOpen });
  startRename = () => this.setState({ rowMenuOpen: false, renaming: true });
  openDeleteConfirm = () => this.setState({ rowMenuOpen: false, deleteConfirmOpen: true });
  closeDeleteConfirm = () => this.setState({ deleteConfirmOpen: false });
```

`renderVals()`의 반환 객체에 추가:

```js
      rowMenuOpen: this.state.rowMenuOpen, toggleRowMenu: this.toggleRowMenu,
      renaming: this.state.renaming, notRenaming: !this.state.renaming, startRename: this.startRename,
      deleteConfirmOpen: this.state.deleteConfirmOpen, openDeleteConfirm: this.openDeleteConfirm,
      closeDeleteConfirm: this.closeDeleteConfirm, deleteTargetName: PAPER.title + '.pdf',
```

`PAPER` 상수(제목·저자가 있는 객체)에 `stem: PAPER.title`을 쓸 수 있도록, `papers` 계산 줄을 `const papers = matches ? [{ ...PAPER, stem: PAPER.title }] : [];` 로 바꾼다.

- [ ] **Step 5: 브라우저로 확인**

파일을 브라우저에서 열어 케밥 메뉴가 열린 상태, "이름 변경" 클릭 시 입력창 전환, "삭제" 클릭 시 다이얼로그가 보이는지 본다. IconButton의 `icon="dots-three"`가 렌더되지 않으면 `<i class="ph ph-dots-three">`를 감싼 `<button>`으로 대체한다.

- [ ] **Step 6: README 표 추가**

`design/v2/README.md`의 Screen States 표 마지막 행 뒤에 추가:

```markdown
| 서재 · 행 메뉴(다운로드 / 이름 변경 / 삭제) | [Paper Bookshelf Page - Row Actions](Paper%20Bookshelf%20Page%20-%20Row%20Actions.dc.html) | 목록 행 오른쪽 끝에 `⋯` 버튼을 상시 두고, 누르면 원본 PDF 다운로드 / 이름 변경 / 삭제 드롭다운이 열린다(분석 중·실패 행은 다운로드 비활성). 이름 변경은 제목을 그 자리에서 입력창(확장자 `.pdf` 고정)으로 바꾸고 Enter 저장·Esc 취소. 삭제는 확인 다이얼로그를 거친다. 격자 카드는 같은 메뉴를 썸네일 우상단에 둔다(YMC-369). |
```

- [ ] **Step 7: 커밋 + PR**

```bash
cd /Users/geunhh/Desktop/team-ymc/project-docs
git add "design/v2/Paper Bookshelf Page - Row Actions.dc.html" design/v2/README.md
git commit -m "[YMC-369] docs(design): 서재 행 메뉴·인라인 이름 변경·삭제 확인 아트보드"
git push -u origin YMC-369-paper-management
gh pr create --title "[YMC-369] 서재 논문 관리 — 계약 0.4.0·Feature·WF·design 반영" --body "$(cat <<'EOF'
## 배경

서재에서 논문을 삭제·이름 변경할 수단이 없고, 파일명 중복 금지(409) 때문에 같은 이름의 다른 논문을 올릴 수 없다. YMC-369로 셋을 한 번에 푼다. 설계는 app `docs/superpowers/specs/2026-09-05-paper-management-design.md`.

## 변경

- openapi 0.4.0: `PATCH`·`DELETE /api/papers/{paperId}` 추가, `RenamePaperRequest`, POST 409·`DUPLICATE_FILENAME` 제거
- FT-002: Story 6(삭제)·7(이름 변경) 추가, Out of Scope 정정, Story 3 비고(만료 행 자동 정리 없음)
- FT-003 Story 2: 파일명 중복 판정 폐지 (Resolved 노트), ADR-003 Option C 폐지 노트
- WF-016: 행 메뉴 C17
- design/v2: 행 메뉴·인라인 편집·삭제 확인 아트보드

## 의존

- 구현 PR: app (이 PR 머지 후 생성)
EOF
)"
```

---

# Part B — BE

모든 BE 명령은 `/Users/geunhh/Desktop/team-ymc/app/be`에서 실행한다. 브랜치는 `YMC-369-paper-management`(이미 스펙 커밋이 있음).

### Task B1: Paper 엔티티·DDL

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/domain/Paper.java`
- Modify: `be/docs/db/paper.sql`
- Test: `be/src/test/java/com/ymc/paper/domain/PaperTest.java`

**Interfaces:**
- Produces: `Paper.rename(String)`, `Paper.markDeleted(Instant)`, `Paper.getDeletedAt()`, `Paper.isDeleted()`.

- [ ] **Step 1: 실패하는 단위 테스트**

`PaperTest.java`의 마지막 `}` 앞에 추가:

```java
    @Nested
    @DisplayName("rename")
    class Rename {

        @Test
        @DisplayName("파일명이 바뀌고 updatedAt은 그대로다")
        void changesFilenameOnly() {
            Paper paper = Paper.register(OWNER_ID, FILENAME, NOW);

            paper.rename("renamed.pdf");

            assertThat(paper.getFilename()).isEqualTo("renamed.pdf");
            assertThat(paper.getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("빈 파일명은 거부한다")
        void rejectsBlank() {
            Paper paper = Paper.register(OWNER_ID, FILENAME, NOW);

            assertThatIllegalArgumentException().isThrownBy(() -> paper.rename("  "));
        }
    }

    @Nested
    @DisplayName("markDeleted")
    class MarkDeleted {

        @Test
        @DisplayName("deletedAt이 채워지고 isDeleted가 참이다")
        void marksDeleted() {
            Paper paper = Paper.register(OWNER_ID, FILENAME, NOW);
            Instant later = NOW.plusSeconds(60);

            paper.markDeleted(later);

            assertThat(paper.getDeletedAt()).isEqualTo(later);
            assertThat(paper.isDeleted()).isTrue();
        }
    }
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests 'com.ymc.paper.domain.PaperTest' -q 2>&1 | tail -5
```
Expected: 컴파일 오류 (`rename`, `markDeleted` 없음).

- [ ] **Step 3: 엔티티 수정**

`Paper.java`:

1. `@Table(...)` 블록을 `@Table(name = "paper")`로 바꾸고 `import jakarta.persistence.UniqueConstraint;` 삭제.
2. `filename` 컬럼: `@Column(name = "filename", nullable = false)` (updatable=false 제거). 주석을 `/** 원본 파일명. 서재 목록의 제목이자 다운로드 저장 파일명. 소유자별 중복을 허용한다. */`로.
3. `expiredAt` 필드 뒤에 추가:

```java
    /** 논리 삭제 시각. 값이 있으면 사용자 경로에서 보이지 않는다. 내부 정산·정리 경로는 그대로 본다. */
    @Column(name = "deleted_at")
    private Instant deletedAt;
```

4. `markAccessed` 뒤에 추가:

```java
    public void rename(String filename) {
        Objects.requireNonNull(filename, "filename");
        if (filename.isBlank()) {
            throw new IllegalArgumentException("filename은 비어 있을 수 없습니다.");
        }
        this.filename = filename;
    }

    public void markDeleted(Instant now) {
        this.deletedAt = Objects.requireNonNull(now, "now");
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew test --tests 'com.ymc.paper.domain.PaperTest' -q 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: paper.sql 갱신**

`be/docs/db/paper.sql`에서:
- `expired_at` 줄 뒤에 추가:
```sql
    -- 논리 삭제 시각 (null = 살아 있음). 값이 있으면 목록·조회·채팅에서 보이지 않는다
    deleted_at       timestamp(6) with time zone,
```
- `-- 파일명 중복 판정(409 DUPLICATE_FILENAME)의 최종 방어선. ...` 주석 3줄과 `constraint uk_paper_owner_filename unique (owner_id, filename),` 줄을 삭제한다.
- 파일 끝에 추가:
```sql

-- 기존 환경(local·dev)은 ddl-auto가 제약을 지우지 않으므로 배포 전에 수동으로 실행한다.
-- alter table paper drop constraint if exists uk_paper_owner_filename;
```

- [ ] **Step 6: 로컬 DB 제약 제거** (로컬 PostgreSQL이 떠 있을 때)

```bash
cd /Users/geunhh/Desktop/team-ymc/infra/local && docker compose exec -T postgres psql -U ymc -d ymc -c "alter table paper drop constraint if exists uk_paper_owner_filename;"
```
컨테이너·DB 이름이 다르면 `docker compose ps`로 확인한다. 안 떠 있으면 건너뛰고 PR 검증란에 적는다.

- [ ] **Step 7: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add be/src/main/java/com/ymc/paper/domain/Paper.java be/docs/db/paper.sql be/src/test/java/com/ymc/paper/domain/PaperTest.java
git commit -m "[YMC-369] feat(be): Paper 논리 삭제·이름 변경 필드, 파일명 유니크 제약 제거"
```

---

### Task B2: 등록에서 파일명 중복 판정 제거

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/domain/PaperRepository.java`
- Modify: `be/src/main/java/com/ymc/paper/service/PaperRegistrationService.java`
- Modify: `be/src/main/java/com/ymc/common/error/ErrorCode.java`
- Test: `be/src/test/java/com/ymc/paper/api/PaperRegistrationIntegrationTest.java`
- Test: `be/src/test/java/com/ymc/paper/api/PaperExpiryIntegrationTest.java`

- [ ] **Step 1: 테스트 교체**

`PaperRegistrationIntegrationTest.java`에서 `rejectsDuplicateFilename`·`pendingRecordAlsoCountsAsDuplicate` 두 테스트 메서드(각 `@Test`부터 닫는 `}`까지)를 삭제하고 그 자리에:

```java
    @Test
    @DisplayName("같은 파일명을 두 번 등록해도 각각 레코드가 생긴다")
    void allowsDuplicateFilename() throws Exception {
        mockMvc.perform(createRequest(FILENAME, "application/pdf").with(userJwt())).andExpect(status().isCreated());
        mockMvc.perform(createRequest(FILENAME, "application/pdf").with(userJwt())).andExpect(status().isCreated());

        assertThat(paperRepository.count()).isEqualTo(2);
    }
```

`PaperExpiryIntegrationTest.java`에서 `nonExpiredDuplicateStillRejected` 테스트를 삭제하고 `reregisterReplacesExpiredRow`를 다음으로 교체:

```java
    @Test
    @DisplayName("같은 파일명 재등록은 만료 row를 지우지 않고 나란히 추가된다")
    void reregisterKeepsExpiredRow() throws Exception {
        Paper expired = givenExpiredPaper("retry.pdf");

        mockMvc.perform(post("/api/papers")
                        .contentType("application/json")
                        .content(createPaperJson("retry.pdf"))
                        .with(userJwt()))
                .andExpect(status().isCreated());

        assertThat(paperRepository.findById(expired.getId())).isPresent();
        assertThat(paperRepository.count()).isEqualTo(2);
    }
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests 'com.ymc.paper.api.PaperRegistrationIntegrationTest' --tests 'com.ymc.paper.api.PaperExpiryIntegrationTest' -q 2>&1 | grep -E "FAILED|tests completed|BUILD" | head
```
Expected: `allowsDuplicateFilename`(409 반환)·`reregisterKeepsExpiredRow`(row 삭제됨) FAILED.

- [ ] **Step 3: 등록 서비스 정리**

`PaperRegistrationService.register`에서 `// 3. 사전 조회 — ...` 주석부터 `throw duplicateFilename(filename); }` 까지 4줄을 삭제한다. `saveAndFlush` try/catch 블록을 `paperRepository.save(paper);`로 바꾼다. `duplicateFilename` 메서드와 `import org.springframework.dao.DataIntegrityViolationException;`을 삭제한다. Javadoc의 `@throws ApiException {@code DUPLICATE_FILENAME} ...` 줄과 "중복 판정은 사전 조회 + DB 유니크 제약 이중 방어다 ..." 문단을 삭제하고, 클래스 Javadoc 첫 줄을 `논문 등록 — 형식·크기 검증 → {@code UPLOAD_PENDING} 레코드 생성 → presigned PUT URL 발급.` 로 바꾼다.

- [ ] **Step 4: Repository·ErrorCode 정리**

`PaperRepository.java`에서 `existsByOwnerIdAndFilename`(Javadoc 포함)과 `deleteExpiredByOwnerAndFilename`(Javadoc·`@Modifying`·`@Query` 포함)을 삭제한다.

`ErrorCode.java`에서 `/** 같은 파일명의 논문이 이미 있음 (create) */ DUPLICATE_FILENAME(HttpStatus.CONFLICT),` 두 줄을 삭제한다.

- [ ] **Step 5: 잔재 확인**

```bash
grep -rn "DUPLICATE_FILENAME\|existsByOwnerIdAndFilename\|deleteExpiredByOwnerAndFilename\|uk_paper_owner_filename" src
```
Expected: 결과 없음. 남아 있으면(주석·다른 테스트) 그 줄을 지운다.

- [ ] **Step 6: 통과 확인**

```bash
./gradlew test --tests 'com.ymc.paper.api.PaperRegistrationIntegrationTest' --tests 'com.ymc.paper.api.PaperExpiryIntegrationTest' --tests 'com.ymc.paper.api.PaperUsageIntegrationTest' -q 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add be/src
git commit -m "[YMC-369] feat(be): 등록 시 파일명 중복 판정·409 DUPLICATE_FILENAME 제거"
```

---

### Task B3: 삭제 행 가시성 — 사용자 경로 6곳

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/domain/PaperRepository.java`
- Modify: `be/src/main/java/com/ymc/paper/service/PaperStatusService.java:29`
- Modify: `be/src/main/java/com/ymc/paper/service/PaperContentQueryService.java:47`
- Modify: `be/src/main/java/com/ymc/paper/service/PaperDownloadService.java:38`
- Modify: `be/src/main/java/com/ymc/paper/service/PaperChatAccessValidator.java:56`
- Modify: `be/src/main/java/com/ymc/paper/service/PaperAccessRecorder.java:25`
- Modify: `be/src/main/java/com/ymc/paper/service/PaperUploadCompletionService.java:106`
- Create: `be/src/test/java/com/ymc/paper/api/DeletedPaperVisibilityIntegrationTest.java`
- Test: `be/src/test/java/com/ymc/paper/api/PaperUsageIntegrationTest.java`
- Test: `be/src/test/java/com/ymc/paper/service/StalePaperCleanupTest.java`

**Interfaces:**
- Produces: `PaperRepository.findActiveById(UUID): Optional<Paper>`, `PaperRepository.markDeleted(UUID, Instant): int`. Task B4가 사용.

- [ ] **Step 1: 실패하는 통합 테스트 (신규 파일)**

```java
package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.domain.Paper;
import com.ymc.paper.service.PaperAccessRecorder;
import com.ymc.support.IntegrationTest;

/** 논리 삭제된 논문은 사용자 경로 어디서도 보이지 않는다. */
class DeletedPaperVisibilityIntegrationTest extends IntegrationTest {

    @Autowired
    PaperAccessRecorder accessRecorder;

    private Paper givenDeletedProcessingPaper(String filename) {
        Paper paper = givenProcessingPaper(filename);
        tx.executeWithoutResult(s -> paperRepository.markDeleted(paper.getId(), Instant.now()));
        return reload(paper.getId());
    }

    @Test
    @DisplayName("목록에서 빠진다")
    void excludedFromList() throws Exception {
        givenDeletedProcessingPaper("gone.pdf");
        givenProcessingPaper("alive.pdf");

        mockMvc.perform(get("/api/papers").with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.papers.length()").value(1))
                .andExpect(jsonPath("$.papers[0].filename").value("alive.pdf"));
    }

    @Test
    @DisplayName("status·content·download·chat sessions·complete 전부 404 PAPER_NOT_FOUND")
    void userPathsReturnNotFound() throws Exception {
        Paper paper = givenDeletedProcessingPaper("gone.pdf");

        for (String path : new String[] {"/status", "/content", "/download", "/chat/sessions"}) {
            mockMvc.perform(get("/api/papers/{id}" + path, paper.getId()).with(userJwt()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
        }
        mockMvc.perform(post("/api/papers/{id}/complete", paper.getId()).with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
    }

    @Test
    @DisplayName("접근 기록은 삭제 행을 갱신하지 않는다")
    void accessRecorderSkipsDeleted() {
        Paper paper = givenDeletedProcessingPaper("gone.pdf");

        accessRecorder.recordAccess(paper.getId(), Instant.now());

        assertThat(reload(paper.getId()).getLastAccessedAt()).isNull();
    }
}
```

`PaperUsageIntegrationTest.java` 마지막 `}` 앞에 추가(기존 `givenReservedPendingPaper`·`recordStatusOf` 헬퍼를 쓴다):

```java
    @Test
    @DisplayName("삭제된 PROCESSING paper도 document 종결 시 정산된다")
    void settlesDeletedPaper() {
        Paper paper = givenReservedPendingPaper("deleted.pdf");
        Document document = givenLinkedDocument(paper);
        documentTransitions.markProcessing(document.getId());
        tx.executeWithoutResult(s -> paperRepository.markDeleted(paper.getId(), Instant.now()));

        tx.executeWithoutResult(s -> documentTransitions.markParsedAndSettle(
                document.getId(), DocumentStatus.COMPLETED, null));

        assertThat(recordStatusOf(paper.getId())).isEqualTo(UsageRecordStatus.CONFIRMED);
    }
```

`StalePaperCleanupTest.java` 마지막 `}` 앞에 추가:

```java
    @Test
    @DisplayName("삭제된 UPLOAD_PENDING도 정체 정리가 만료·해제한다")
    void expiresDeletedStalePending() {
        Paper stale = givenReservedPaperAt("deleted-stale.pdf", Instant.now().minus(2, ChronoUnit.HOURS));
        tx.executeWithoutResult(s -> paperRepository.markDeleted(stale.getId(), Instant.now()));

        cleanup.run();

        assertThat(reload(stale.getId()).getExpiredAt()).isNotNull();
        assertThat(recordStatusOf(stale.getId())).isEqualTo(UsageRecordStatus.RELEASED);
    }
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests 'com.ymc.paper.api.DeletedPaperVisibilityIntegrationTest' -q 2>&1 | tail -5
```
Expected: 컴파일 오류 (`markDeleted` 없음).

- [ ] **Step 3: Repository 메서드**

`PaperRepository.java`의 `findAllByOwnerIdOrderByRecentAccess` 쿼리 `where p.ownerId = :ownerId` 줄 뒤에 `               and p.deletedAt is null` 을 넣는다. 그 메서드 아래에 추가:

```java
    /** 사용자 경로용 조회 — 논리 삭제된 행은 없는 것으로 본다. 내부 정산·정리 경로는 findById를 그대로 쓴다. */
    @Query("select p from Paper p where p.id = :id and p.deletedAt is null")
    Optional<Paper> findActiveById(@Param("id") UUID id);

    /** 논리 삭제 CAS — 아직 살아 있을 때만 1 row. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Paper p
               set p.deletedAt = :now
             where p.id = :paperId
               and p.deletedAt is null
            """)
    int markDeleted(@Param("paperId") UUID paperId, @Param("now") Instant now);
```

`import java.util.Optional;` 추가.

- [ ] **Step 4: 사용자 경로 6곳 교체**

각 파일에서 `paperRepository.findById(paperId)` → `paperRepository.findActiveById(paperId)` (딱 그 호출만):

- `PaperStatusService.getStatus`
- `PaperContentQueryService.getContent`
- `PaperDownloadService.download`
- `PaperChatAccessValidator.getOwned`
- `PaperAccessRecorder.recordAccess` (`findActiveById(paperId).ifPresent(...)`)
- `PaperUploadCompletionService.find`

`PaperDocumentLinkService`·`DocumentTransitions`·`StalePaperCleanup`은 손대지 않는다.

- [ ] **Step 5: 통과 확인**

```bash
./gradlew test --tests 'com.ymc.paper.api.DeletedPaperVisibilityIntegrationTest' --tests 'com.ymc.paper.api.PaperUsageIntegrationTest' --tests 'com.ymc.paper.service.StalePaperCleanupTest' -q 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add be/src
git commit -m "[YMC-369] feat(be): 논리 삭제된 논문을 사용자 경로에서 제외 (findActiveById)"
```

---

### Task B4: PaperManagementService + PATCH/DELETE 엔드포인트

**Files:**
- Create: `be/src/main/java/com/ymc/paper/service/PaperManagementService.java`
- Create: `be/src/main/java/com/ymc/paper/api/dto/RenamePaperRequest.java`
- Modify: `be/src/main/java/com/ymc/paper/api/PaperController.java`
- Modify: `be/src/main/java/com/ymc/paper/service/PaperDocumentViews.java`
- Modify: `be/src/main/java/com/ymc/chat/domain/ChatSessionRepository.java`
- Create: `be/src/test/java/com/ymc/paper/api/PaperManagementIntegrationTest.java`

**Interfaces:**
- Consumes: `PaperRepository.findActiveById`, `PaperRepository.markDeleted` (B3), `Paper.rename` (B1).
- Produces: `PaperManagementService.delete(UUID paperId, UUID ownerId)`, `PaperManagementService.rename(UUID paperId, UUID ownerId, String filename): PaperListView`, `ChatSessionRepository.markDeletedByPaperId(UUID, Instant): int`.

- [ ] **Step 1: 실패하는 통합 테스트 (신규 파일)**

```java
package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.ymc.chat.domain.ChatSession;
import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

class PaperManagementIntegrationTest extends IntegrationTest {

    private static String renameJson(String filename) {
        return "{\"filename\":\"" + filename + "\"}";
    }

    // ---- DELETE ----

    @Test
    @DisplayName("삭제: 204, 목록에서 빠지고 소속 채팅 세션도 논리 삭제된다")
    void deletesPaperAndSessions() throws Exception {
        Paper paper = givenProcessingPaper("bye.pdf");
        ChatSession session = chatSessionRepository.save(
                ChatSession.open(TEST_USER_ID, paper.getId(), "질문", Instant.now()));

        mockMvc.perform(delete("/api/papers/{id}", paper.getId()).with(userJwt()))
                .andExpect(status().isNoContent());

        assertThat(reload(paper.getId()).getDeletedAt()).isNotNull();
        assertThat(chatSessionRepository.findById(session.getId()).orElseThrow().isDeleted()).isTrue();
        mockMvc.perform(get("/api/papers").with(userJwt()))
                .andExpect(jsonPath("$.papers").isEmpty());
    }

    @Test
    @DisplayName("삭제: 타인 소유는 403")
    void deleteForbiddenForOtherUser() throws Exception {
        Paper paper = givenProcessingPaper("mine.pdf");

        mockMvc.perform(delete("/api/papers/{id}", paper.getId()).with(otherUserJwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(reload(paper.getId()).getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("삭제: 없는 논문과 이미 삭제된 논문은 404")
    void deleteNotFound() throws Exception {
        Paper paper = givenProcessingPaper("twice.pdf");
        mockMvc.perform(delete("/api/papers/{id}", paper.getId()).with(userJwt()))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/papers/{id}", paper.getId()).with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
        mockMvc.perform(delete("/api/papers/{id}", UUID.randomUUID()).with(userJwt()))
                .andExpect(status().isNotFound());
    }

    // ---- PATCH ----

    @Test
    @DisplayName("이름 변경: 200으로 바뀐 행을 돌려주고 updatedAt은 그대로다")
    void renamesPaper() throws Exception {
        Paper paper = givenProcessingPaper("old.pdf");
        Instant updatedBefore = paper.getUpdatedAt();

        mockMvc.perform(patch("/api/papers/{id}", paper.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(renameJson("  new name.pdf  "))
                        .with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paperId").value(paper.getId().toString()))
                .andExpect(jsonPath("$.filename").value("new name.pdf"))
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        Paper reloaded = reload(paper.getId());
        assertThat(reloaded.getFilename()).isEqualTo("new name.pdf");
        assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedBefore);
    }

    @Test
    @DisplayName("이름 변경: 공백·256자는 400 VALIDATION_ERROR")
    void renameValidation() throws Exception {
        Paper paper = givenProcessingPaper("old.pdf");

        mockMvc.perform(patch("/api/papers/{id}", paper.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(renameJson("   "))
                        .with(userJwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(patch("/api/papers/{id}", paper.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(renameJson("a".repeat(256)))
                        .with(userJwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(reload(paper.getId()).getFilename()).isEqualTo("old.pdf");
    }

    @Test
    @DisplayName("이름 변경: 같은 이름의 다른 논문이 있어도 허용한다")
    void renameAllowsDuplicate() throws Exception {
        givenProcessingPaper("same.pdf");
        Paper other = givenProcessingPaper("other.pdf");

        mockMvc.perform(patch("/api/papers/{id}", other.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(renameJson("same.pdf"))
                        .with(userJwt()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("이름 변경: 타인 403, 삭제됨·없음 404")
    void renameForbiddenAndNotFound() throws Exception {
        Paper paper = givenProcessingPaper("x.pdf");
        mockMvc.perform(patch("/api/papers/{id}", paper.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(renameJson("y.pdf"))
                        .with(otherUserJwt()))
                .andExpect(status().isForbidden());

        tx.executeWithoutResult(s -> paperRepository.markDeleted(paper.getId(), Instant.now()));
        mockMvc.perform(patch("/api/papers/{id}", paper.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(renameJson("y.pdf"))
                        .with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests 'com.ymc.paper.api.PaperManagementIntegrationTest' -q 2>&1 | grep -E "FAILED|BUILD" | head
```
Expected: 전부 FAILED (405 Method Not Allowed).

- [ ] **Step 3: ChatSessionRepository 일괄 삭제**

`ChatSessionRepository.java`에 추가(`import java.time.Instant;`, `org.springframework.data.jpa.repository.Modifying`, `org.springframework.data.repository.query.Param` 임포트):

```java
    /** 논문 삭제에 딸려 소속 세션을 논리 삭제한다. 살아 있는 세션만 건드린다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ChatSession s
               set s.deletedAt = :now
             where s.paperId = :paperId
               and s.deletedAt is null
            """)
    int markDeletedByPaperId(@Param("paperId") UUID paperId, @Param("now") Instant now);
```

- [ ] **Step 4: PaperDocumentViews 편의 메서드**

`listViews` 메서드 뒤에 추가:

```java
    public PaperListView listView(Paper paper) {
        return listViews(List.of(paper)).get(0);
    }
```

- [ ] **Step 5: 서비스**

`be/src/main/java/com/ymc/paper/service/PaperManagementService.java`:

```java
package com.ymc.paper.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.chat.domain.ChatSessionRepository;
import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperRepository;

import lombok.RequiredArgsConstructor;

/** 서재의 논문 정리 — 논리 삭제와 이름 변경. */
@Service
@RequiredArgsConstructor
public class PaperManagementService {

    private static final int FILENAME_MAX_LENGTH = 255;

    private final PaperRepository paperRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final PaperDocumentViews views;

    /**
     * 논리 삭제. 소속 채팅 세션도 함께 논리 삭제한다. 원본·Document는 공유 자원이라 건드리지 않는다.
     *
     * @throws ApiException {@code PAPER_NOT_FOUND} — 없거나 이미 삭제됨
     * @throws ApiException {@code FORBIDDEN} — 소유자가 아님
     */
    @Transactional
    public void delete(UUID paperId, UUID ownerId) {
        Paper paper = findOwned(paperId, ownerId);
        Instant now = Instant.now();
        if (paperRepository.markDeleted(paper.getId(), now) == 0) {
            throw notFound(paperId);
        }
        chatSessionRepository.markDeletedByPaperId(paper.getId(), now);
    }

    /**
     * 파일명 변경. trim 후 1~255자만 받는다. 상태 변경이 아니므로 updatedAt은 건드리지 않는다.
     *
     * @throws ApiException {@code VALIDATION_ERROR} — 비었거나 255자 초과
     * @throws ApiException {@code PAPER_NOT_FOUND} / {@code FORBIDDEN}
     */
    @Transactional
    public PaperListView rename(UUID paperId, UUID ownerId, String filename) {
        String normalized = filename == null ? "" : filename.strip();
        if (normalized.isEmpty() || normalized.length() > FILENAME_MAX_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "파일명은 1~255자여야 합니다.");
        }
        Paper paper = findOwned(paperId, ownerId);
        paper.rename(normalized);
        return views.listView(paper);
    }

    private Paper findOwned(UUID paperId, UUID ownerId) {
        Paper paper = paperRepository.findActiveById(paperId).orElseThrow(() -> notFound(paperId));
        if (!paper.getOwnerId().equals(ownerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "이 논문에 접근할 권한이 없습니다.");
        }
        return paper;
    }

    private static ApiException notFound(UUID paperId) {
        return new ApiException(ErrorCode.PAPER_NOT_FOUND, "존재하지 않는 논문입니다: " + paperId);
    }
}
```

- [ ] **Step 6: DTO·컨트롤러**

`be/src/main/java/com/ymc/paper/api/dto/RenamePaperRequest.java`:

```java
package com.ymc.paper.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 계약 `RenamePaperRequest`. trim은 서비스가 한다. */
public record RenamePaperRequest(
        @NotBlank(message = "필수 항목입니다.")
        @Size(max = 255, message = "255자 이하여야 합니다.")
        String filename) {
}
```

`PaperController.java`:
- 필드 `private final PaperManagementService managementService;` 추가, import `com.ymc.paper.service.PaperManagementService`, `com.ymc.paper.api.dto.RenamePaperRequest`, `org.springframework.web.bind.annotation.PatchMapping`, `org.springframework.web.bind.annotation.DeleteMapping`.
- `content` 메서드 뒤에 추가:

```java
    /** 논문 이름 변경. 바뀐 행을 목록 항목 형태로 돌려준다. */
    @PatchMapping("/{paperId}")
    public PaperListResponse.Item rename(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paperId,
            @Valid @RequestBody RenamePaperRequest request) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        return PaperListResponse.Item.from(managementService.rename(paperId, ownerId, request.filename()));
    }

    /** 논문 논리 삭제. */
    @DeleteMapping("/{paperId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paperId) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        managementService.delete(paperId, ownerId);
        return ResponseEntity.noContent().build();
    }
```

`PaperListResponse.Item.from`은 package-private(`static Item from`)이다. `public static Item from(...)`으로 바꾼다.

- [ ] **Step 7: 통과 확인**

```bash
./gradlew test --tests 'com.ymc.paper.api.PaperManagementIntegrationTest' -q 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL. `renameValidation`의 공백 케이스가 서비스 대신 `@NotBlank`에서 잡혀도 코드는 같은 `VALIDATION_ERROR`다.

- [ ] **Step 8: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add be/src
git commit -m "[YMC-369] feat(be): 논문 논리 삭제·이름 변경 API (DELETE/PATCH /api/papers/{paperId})"
```

---

### Task B5: Content-Disposition 파일명 정리

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/infra/storage/S3FileStorage.java:59-71`
- Test: `be/src/test/java/com/ymc/paper/infra/storage/S3FileStorageDownloadIntegrationTest.java`

- [ ] **Step 1: 실패하는 테스트**

`S3FileStorageDownloadIntegrationTest.java`에 추가:

```java
    @Test
    @DisplayName("presignDownload: 따옴표·제어 문자는 빠지고 한글은 남는다")
    void stripsHeaderBreakingCharacters() {
        PresignedDownload d = fileStorage.presignDownload(
                "papers/550e8400-e29b-41d4-a716-446655440000/original.pdf",
                "논문 \"v2\"\r\n final.pdf");

        String decoded = java.net.URLDecoder.decode(d.url(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(decoded).contains("filename=\"논문 v2 final.pdf\"");
    }
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests 'com.ymc.paper.infra.storage.S3FileStorageDownloadIntegrationTest' -q 2>&1 | grep -E "FAILED|BUILD" | head
```
Expected: `stripsHeaderBreakingCharacters` FAILED.

- [ ] **Step 3: 정리 함수**

`S3FileStorage.presignDownload`에서 `.responseContentDisposition("attachment; filename=\"" + filename + "\"")` 를 `.responseContentDisposition("attachment; filename=\"" + headerSafe(filename) + "\"")` 로 바꾸고, 클래스 안에 추가:

```java
    /** 헤더를 깨는 큰따옴표와 제어 문자만 뺀다. 한글 등 비ASCII는 브라우저가 처리하므로 그대로 둔다. */
    static String headerSafe(String filename) {
        return filename.replaceAll("[\"\\p{Cntrl}]", "");
    }
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew test --tests 'com.ymc.paper.infra.storage.S3FileStorageDownloadIntegrationTest' -q 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: BE 전체 테스트**

```bash
./gradlew test -q 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL. 실패가 있으면 이 계획 밖의 회귀이므로 원인을 고친 뒤 진행한다.

- [ ] **Step 6: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add be/src
git commit -m "[YMC-369] fix(be): 다운로드 Content-Disposition 파일명의 따옴표·제어 문자 제거"
```

---

# Part C — FE

모든 FE 명령은 `/Users/geunhh/Desktop/team-ymc/app/fe`에서 실행한다.

### Task C1: API 클라이언트·아이콘

**Files:**
- Modify: `fe/src/api/papers.ts`
- Modify: `fe/src/api/papers.test.ts`
- Modify: `fe/src/design/components/icons.ts`

**Interfaces:**
- Produces: `renamePaper(paperId: string, filename: string): Promise<Paper>`, `deletePaper(paperId: string): Promise<void>`; 아이콘 키 `dots-three`, `download-simple`, `pencil-simple`, `trash`.

- [ ] **Step 1: 실패하는 테스트**

`papers.test.ts` import에 `renamePaper, deletePaper` 추가. `'fetchPaperContent: 409는 ...'` 테스트 뒤에 추가:

```ts
  it('renamePaper: PATCH /api/papers/{id}에 filename을 JSON으로 보내고 행을 돌려준다', async () => {
    mockFetch({ body: { paperId: 'p1', filename: 'b.pdf', status: 'COMPLETED' } });
    const res = await renamePaper('p1', 'b.pdf');
    expect(globalThis.fetch).toHaveBeenCalledWith('/api/papers/p1', expect.objectContaining({
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ filename: 'b.pdf' }),
    }));
    expect(res.filename).toBe('b.pdf');
  });

  it('deletePaper: DELETE /api/papers/{id}, 204면 본문을 읽지 않는다', async () => {
    mockFetch({ status: 204, body: undefined });
    await expect(deletePaper('p1')).resolves.toBeUndefined();
    expect(globalThis.fetch).toHaveBeenCalledWith('/api/papers/p1', expect.objectContaining({ method: 'DELETE' }));
  });

  it('deletePaper: 404는 ApiError(code)로 던진다', async () => {
    mockFetch({ ok: false, status: 404, body: { code: 'PAPER_NOT_FOUND', message: '' } });
    await expect(deletePaper('p1')).rejects.toMatchObject({ code: 'PAPER_NOT_FOUND', httpStatus: 404 });
  });
```

기존 `'실패 응답: code·httpStatus를 실은 Error를 던진다'` 테스트의 `DUPLICATE_FILENAME`/409를 `PAPER_USAGE_LIMIT_EXCEEDED`/429로 바꾼다(`status: 429`, `code: 'PAPER_USAGE_LIMIT_EXCEEDED'`, `httpStatus: 429`).

- [ ] **Step 2: 실패 확인**

```bash
npm test -- src/api/papers.test.ts 2>&1 | tail -8
```
Expected: `renamePaper`/`deletePaper` 없음으로 실패.

- [ ] **Step 3: 구현**

`papers.ts`의 `listPapers` 뒤에 추가:

```ts
// 이름 변경 (계약 0.4.0). 바뀐 행을 돌려주므로 호출 측이 캐시의 해당 항목만 교체한다.
export async function renamePaper(paperId: string, filename: string): Promise<Paper> {
  const res = await authFetch(`/api/papers/${paperId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ filename }),
  });
  if (!res.ok) throw await apiError(res);
  return res.json();
}

// 논리 삭제 (계약 0.4.0). 204라 본문이 없다.
export async function deletePaper(paperId: string): Promise<void> {
  const res = await authFetch(`/api/papers/${paperId}`, { method: 'DELETE' });
  if (!res.ok) throw await apiError(res);
}
```

`icons.ts` import에 `DotsThree, DownloadSimple, PencilSimple, Trash` 추가, `ICONS`에:

```ts
  'dots-three': DotsThree,
  'download-simple': DownloadSimple,
  'pencil-simple': PencilSimple,
  trash: Trash,
```

- [ ] **Step 4: 통과 확인**

```bash
npm test -- src/api/papers.test.ts 2>&1 | tail -5 && npm run typecheck
```
Expected: 테스트 통과, 타입 오류 없음.

- [ ] **Step 5: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add fe/src/api/papers.ts fe/src/api/papers.test.ts fe/src/design/components/icons.ts
git commit -m "[YMC-369] feat(fe): renamePaper·deletePaper API 클라이언트, 행 메뉴 아이콘 등록"
```

---

### Task C2: pdfName 순수 함수

**Files:**
- Create: `fe/src/routes/bookshelf/pdfName.ts`
- Create: `fe/src/routes/bookshelf/pdfName.test.ts`

**Interfaces:**
- Produces: `splitPdfName(filename: string): { stem: string; ext: string }`, `joinPdfName(stem: string, ext: string): string`. C4가 사용.

- [ ] **Step 1: 실패하는 테스트**

```ts
import { describe, it, expect } from 'vitest';
import { splitPdfName, joinPdfName } from './pdfName';

describe('splitPdfName', () => {
  it('.pdf를 떼어 stem·ext로 나눈다', () => {
    expect(splitPdfName('attention.pdf')).toEqual({ stem: 'attention', ext: '.pdf' });
  });
  it('대소문자 무관, 원래 표기를 ext에 보존한다', () => {
    expect(splitPdfName('paper.PDF')).toEqual({ stem: 'paper', ext: '.PDF' });
  });
  it('점이 여러 개면 마지막 .pdf만 뗀다', () => {
    expect(splitPdfName('v1.2.final.pdf')).toEqual({ stem: 'v1.2.final', ext: '.pdf' });
  });
  it('.pdf로 끝나지 않으면 ext가 빈 문자열이다', () => {
    expect(splitPdfName('notes.txt')).toEqual({ stem: 'notes.txt', ext: '' });
  });
});

describe('joinPdfName', () => {
  it('stem을 trim하고 ext를 붙인다', () => {
    expect(joinPdfName('  new name ', '.pdf')).toBe('new name.pdf');
  });
  it('ext가 비면 stem만 남는다', () => {
    expect(joinPdfName('notes.txt', '')).toBe('notes.txt');
  });
});
```

- [ ] **Step 2: 실패 확인**

```bash
npm test -- src/routes/bookshelf/pdfName.test.ts 2>&1 | tail -5
```
Expected: 모듈 없음으로 실패.

- [ ] **Step 3: 구현**

```ts
// 인라인 이름 변경은 확장자를 고정 표시하고 이름만 편집한다. .pdf가 아닌 이름은 억지로 바꾸지 않는다.
export function splitPdfName(filename: string): { stem: string; ext: string } {
  const m = /^(.*)(\.pdf)$/i.exec(filename);
  return m ? { stem: m[1], ext: m[2] } : { stem: filename, ext: '' };
}

export function joinPdfName(stem: string, ext: string): string {
  return `${stem.trim()}${ext}`;
}
```

- [ ] **Step 4: 통과 확인**

```bash
npm test -- src/routes/bookshelf/pdfName.test.ts 2>&1 | tail -5
```

- [ ] **Step 5: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add fe/src/routes/bookshelf/pdfName.ts fe/src/routes/bookshelf/pdfName.test.ts
git commit -m "[YMC-369] feat(fe): pdf 파일명 stem/ext 분리 함수"
```

---

### Task C3: PaperRowMenu

**Files:**
- Create: `fe/src/routes/bookshelf/PaperRowMenu.tsx`
- Create: `fe/src/routes/bookshelf/PaperRowMenu.test.tsx`

**Interfaces:**
- Consumes: 아이콘 키(C1).
- Produces: `PaperRowMenu({ paper, onDownload, onRename, onDelete, size? })` — 케밥 버튼 `aria-label="더 보기"`, 메뉴 `role="menu"`, 항목 텍스트 `원본 PDF 다운로드` / `이름 변경` / `삭제`.

- [ ] **Step 1: 실패하는 테스트**

```tsx
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import PaperRowMenu from './PaperRowMenu';
import type { Paper } from '../../api/types';

afterEach(cleanup);

const completed: Paper = {
  paperId: 'p1', filename: 'a.pdf', status: 'COMPLETED',
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', lastAccessedAt: null,
};

function renderInRow(paper: Paper, rowClick = vi.fn()) {
  const handlers = { onDownload: vi.fn(), onRename: vi.fn(), onDelete: vi.fn() };
  render(
    <div role="button" onClick={rowClick} data-testid="row">
      <PaperRowMenu paper={paper} {...handlers} />
    </div>,
  );
  return { rowClick, ...handlers };
}

describe('PaperRowMenu', () => {
  it('케밥을 누르면 메뉴가 열리고 행 클릭은 전파되지 않는다', () => {
    const { rowClick } = renderInRow(completed);
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    expect(screen.getByRole('menu')).toBeTruthy();
    expect(rowClick).not.toHaveBeenCalled();
  });

  it('항목을 누르면 핸들러가 불리고 메뉴가 닫힌다', () => {
    const { onRename, onDelete, rowClick } = renderInRow(completed);
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '이름 변경' }));
    expect(onRename).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('menu')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '삭제' }));
    expect(onDelete).toHaveBeenCalledTimes(1);
    expect(rowClick).not.toHaveBeenCalled();
  });

  it('COMPLETED가 아니면 다운로드가 비활성이다', () => {
    const { onDownload } = renderInRow({ ...completed, status: 'PROCESSING' });
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    const item = screen.getByRole('menuitem', { name: '원본 PDF 다운로드' }) as HTMLButtonElement;
    expect(item.disabled).toBe(true);
    fireEvent.click(item);
    expect(onDownload).not.toHaveBeenCalled();
  });

  it('바깥 mousedown과 Esc로 닫힌다', () => {
    renderInRow(completed);
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    fireEvent.mouseDown(document.body);
    expect(screen.queryByRole('menu')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('menu')).toBeNull();
  });
});
```

- [ ] **Step 2: 실패 확인**

```bash
npm test -- src/routes/bookshelf/PaperRowMenu.test.tsx 2>&1 | tail -5
```

- [ ] **Step 3: 구현**

```tsx
// 서재 행·격자 카드 공용 케밥 메뉴. 행 자체가 클릭으로 학습 페이지를 여는 버튼이라, 이 안의 이벤트는 전부 행으로 번지지 않게 막는다.
import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { DotsThree, DownloadSimple, PencilSimple, Trash } from '@phosphor-icons/react';
import type { Paper } from '../../api/types';

export interface PaperRowMenuProps {
  paper: Paper;
  onDownload: () => void;
  onRename: () => void;
  onDelete: () => void;
  size?: number;
}

const itemBase: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: '10px',
  width: '100%',
  textAlign: 'left',
  padding: '9px 10px',
  border: 'none',
  background: 'transparent',
  borderRadius: '6px',
  fontFamily: 'var(--font-sans)',
  fontSize: '13px',
  color: 'var(--color-text-body)',
  cursor: 'pointer',
  whiteSpace: 'nowrap',
};

function MenuItem({ icon, label, danger, disabled, title, onSelect }: {
  icon: ReactNode; label: string; danger?: boolean; disabled?: boolean; title?: string; onSelect: () => void;
}) {
  const [hover, setHover] = useState(false);
  return (
    <button
      role="menuitem"
      disabled={disabled}
      title={title}
      onClick={onSelect}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={{
        ...itemBase,
        color: danger ? 'var(--color-danger)' : itemBase.color,
        background: hover && !disabled ? 'var(--color-primary-subtle)' : 'transparent',
        opacity: disabled ? 0.4 : 1,
        cursor: disabled ? 'default' : 'pointer',
      }}
    >
      {icon}
      {label}
    </button>
  );
}

export default function PaperRowMenu({ paper, onDownload, onRename, onDelete, size = 32 }: PaperRowMenuProps) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const canDownload = paper.status === 'COMPLETED';

  useEffect(() => {
    if (!open) return;
    function onDocMouseDown(e: MouseEvent) {
      if (rootRef.current && rootRef.current.contains(e.target as Node)) return;
      setOpen(false);
    }
    function onDocKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false);
    }
    document.addEventListener('mousedown', onDocMouseDown, true);
    document.addEventListener('keydown', onDocKeyDown);
    return () => {
      document.removeEventListener('mousedown', onDocMouseDown, true);
      document.removeEventListener('keydown', onDocKeyDown);
    };
  }, [open]);

  function select(action: () => void) {
    setOpen(false);
    action();
  }

  return (
    <div
      ref={rootRef}
      style={{ position: 'relative', flexShrink: 0 }}
      onClick={(e) => e.stopPropagation()}
      onKeyDown={(e) => e.stopPropagation()}
    >
      <button
        aria-label="더 보기"
        title="더 보기"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        style={{
          width: `${size}px`,
          height: `${size}px`,
          border: 'none',
          borderRadius: 'var(--radius-control)',
          background: open ? 'var(--color-primary-subtle)' : 'transparent',
          color: open ? 'var(--color-text-heading)' : 'var(--color-text-muted)',
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          cursor: 'pointer',
        }}
      >
        <DotsThree size={Math.round(size * 0.56)} weight="bold" />
      </button>
      {open && (
        <div
          role="menu"
          style={{
            position: 'absolute',
            right: 0,
            top: 'calc(100% + 4px)',
            zIndex: 20,
            minWidth: '180px',
            background: 'var(--color-bg-paper)',
            border: '1px solid var(--color-border)',
            borderRadius: '8px',
            boxShadow: 'var(--shadow-menu)',
            padding: '6px',
            display: 'flex',
            flexDirection: 'column',
            gap: '2px',
          }}
        >
          <MenuItem
            icon={<DownloadSimple size={16} />}
            label="원본 PDF 다운로드"
            disabled={!canDownload}
            title={canDownload ? undefined : '분석 완료 후 다운로드할 수 있습니다'}
            onSelect={() => select(onDownload)}
          />
          <MenuItem icon={<PencilSimple size={16} />} label="이름 변경" onSelect={() => select(onRename)} />
          <div style={{ height: '1px', background: 'var(--color-border)', margin: '2px 6px' }} />
          <MenuItem icon={<Trash size={16} />} label="삭제" danger onSelect={() => select(onDelete)} />
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 4: 통과 확인**

```bash
npm test -- src/routes/bookshelf/PaperRowMenu.test.tsx 2>&1 | tail -5 && npm run typecheck
```

- [ ] **Step 5: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add fe/src/routes/bookshelf/PaperRowMenu.tsx fe/src/routes/bookshelf/PaperRowMenu.test.tsx
git commit -m "[YMC-369] feat(fe): 서재 행 케밥 메뉴 컴포넌트 (다운로드·이름 변경·삭제)"
```

---

### Task C4: PaperTitleEditor

**Files:**
- Create: `fe/src/routes/bookshelf/PaperTitleEditor.tsx`
- Create: `fe/src/routes/bookshelf/PaperTitleEditor.test.tsx`

**Interfaces:**
- Consumes: `splitPdfName`, `joinPdfName` (C2).
- Produces: `PaperTitleEditor({ filename, editing, titleStyle, onSave(filename), onCancel })`. `editing=false`면 `titleStyle`로 제목만 렌더.

- [ ] **Step 1: 실패하는 테스트**

```tsx
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import PaperTitleEditor from './PaperTitleEditor';

afterEach(cleanup);

function renderEditing(filename = 'attention.pdf') {
  const onSave = vi.fn();
  const onCancel = vi.fn();
  const rowKey = vi.fn();
  render(
    <div role="button" onKeyDown={rowKey}>
      <PaperTitleEditor filename={filename} editing titleStyle={{}} onSave={onSave} onCancel={onCancel} />
    </div>,
  );
  const input = screen.getByRole('textbox', { name: '새 이름' }) as HTMLInputElement;
  return { input, onSave, onCancel, rowKey };
}

describe('PaperTitleEditor', () => {
  it('editing이 아니면 제목만 보인다', () => {
    render(<PaperTitleEditor filename="attention.pdf" editing={false} titleStyle={{}} onSave={() => {}} onCancel={() => {}} />);
    expect(screen.getByText('attention.pdf')).toBeTruthy();
    expect(screen.queryByRole('textbox')).toBeNull();
  });

  it('편집 모드는 stem만 입력창에 두고 .pdf를 고정 표시하며 포커스·선택된다', () => {
    const { input } = renderEditing();
    expect(input.value).toBe('attention');
    expect(screen.getByText('.pdf')).toBeTruthy();
    expect(document.activeElement).toBe(input);
    expect(input.selectionStart).toBe(0);
    expect(input.selectionEnd).toBe('attention'.length);
  });

  it('Enter로 저장하면 .pdf를 붙인 이름으로 onSave가 불리고 행 키 핸들러는 안 불린다', () => {
    const { input, onSave, rowKey } = renderEditing();
    fireEvent.change(input, { target: { value: ' renamed ' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onSave).toHaveBeenCalledWith('renamed.pdf');
    expect(rowKey).not.toHaveBeenCalled();
  });

  it('Esc는 onCancel, 빈 값·동일 값은 onSave 없이 onCancel', () => {
    const a = renderEditing();
    fireEvent.keyDown(a.input, { key: 'Escape' });
    expect(a.onCancel).toHaveBeenCalledTimes(1);
    expect(a.onSave).not.toHaveBeenCalled();
    cleanup();

    const b = renderEditing();
    fireEvent.change(b.input, { target: { value: '   ' } });
    fireEvent.keyDown(b.input, { key: 'Enter' });
    expect(b.onSave).not.toHaveBeenCalled();
    expect(b.onCancel).toHaveBeenCalledTimes(1);
    cleanup();

    const c = renderEditing();
    fireEvent.blur(c.input);
    expect(c.onSave).not.toHaveBeenCalled();
    expect(c.onCancel).toHaveBeenCalledTimes(1);
  });

  it('blur는 저장으로 처리하되 Enter 직후의 blur는 두 번 저장하지 않는다', () => {
    const { input, onSave } = renderEditing();
    fireEvent.change(input, { target: { value: 'x' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    fireEvent.blur(input);
    expect(onSave).toHaveBeenCalledTimes(1);
  });
});
```

- [ ] **Step 2: 실패 확인**

```bash
npm test -- src/routes/bookshelf/PaperTitleEditor.test.tsx 2>&1 | tail -5
```

- [ ] **Step 3: 구현**

```tsx
// 제목 자리에서 이름만 편집한다(확장자 고정). 진입은 부모가 editing으로 켠다 — 제목에 클릭 핸들러를 두지 않는다.
import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { joinPdfName, splitPdfName } from './pdfName';

export interface PaperTitleEditorProps {
  filename: string;
  editing: boolean;
  titleStyle: CSSProperties;
  onSave: (filename: string) => void;
  onCancel: () => void;
}

export default function PaperTitleEditor({ filename, editing, titleStyle, onSave, onCancel }: PaperTitleEditorProps) {
  if (!editing) return <div style={titleStyle}>{filename}</div>;
  return <Editor filename={filename} titleStyle={titleStyle} onSave={onSave} onCancel={onCancel} />;
}

function Editor({ filename, titleStyle, onSave, onCancel }: Omit<PaperTitleEditorProps, 'editing'>) {
  const { stem, ext } = splitPdfName(filename);
  const [value, setValue] = useState(stem);
  const inputRef = useRef<HTMLInputElement>(null);
  const doneRef = useRef(false); // Enter 뒤 따라오는 blur가 두 번 저장하지 않게

  useEffect(() => {
    inputRef.current?.focus();
    inputRef.current?.select();
  }, []);

  function finish(save: boolean) {
    if (doneRef.current) return;
    doneRef.current = true;
    const next = joinPdfName(value, ext);
    if (!save || !value.trim() || next === filename) {
      onCancel();
      return;
    }
    onSave(next);
  }

  return (
    <div
      style={{ flex: 1, minWidth: 0, display: 'flex', alignItems: 'center', gap: '8px' }}
      onClick={(e) => e.stopPropagation()}
      onKeyDown={(e) => e.stopPropagation()}
    >
      <input
        ref={inputRef}
        aria-label="새 이름"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') finish(true);
          if (e.key === 'Escape') finish(false);
        }}
        onBlur={() => finish(true)}
        style={{
          ...titleStyle,
          flex: 1,
          minWidth: 0,
          background: 'var(--color-bg-paper)',
          border: '1px solid var(--color-primary)',
          borderRadius: 'var(--radius-structural)',
          padding: '4px 10px',
          outline: 'none',
        }}
      />
      {ext && <span style={{ ...titleStyle, flex: 'none', color: 'var(--color-text-muted)' }}>{ext}</span>}
    </div>
  );
}
```

- [ ] **Step 4: 통과 확인**

```bash
npm test -- src/routes/bookshelf/PaperTitleEditor.test.tsx 2>&1 | tail -5 && npm run typecheck
```

- [ ] **Step 5: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add fe/src/routes/bookshelf/PaperTitleEditor.tsx fe/src/routes/bookshelf/PaperTitleEditor.test.tsx
git commit -m "[YMC-369] feat(fe): 서재 제목 인라인 이름 변경 에디터"
```

---

### Task C5: ConfirmDialog

**Files:**
- Create: `fe/src/routes/bookshelf/ConfirmDialog.tsx`
- Create: `fe/src/routes/bookshelf/ConfirmDialog.test.tsx`

**Interfaces:**
- Produces: `ConfirmDialog({ open, title, message, confirmLabel, busy, onConfirm, onCancel })`.

- [ ] **Step 1: 실패하는 테스트**

```tsx
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import ConfirmDialog from './ConfirmDialog';

afterEach(cleanup);

describe('ConfirmDialog', () => {
  it('open이 아니면 아무것도 그리지 않는다', () => {
    render(<ConfirmDialog open={false} title="t" message="m" confirmLabel="삭제" busy={false} onConfirm={() => {}} onCancel={() => {}} />);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('확인·취소 버튼이 각 핸들러를 부르고 busy면 둘 다 비활성이다', () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    const { rerender } = render(
      <ConfirmDialog open title="논문을 삭제할까요?" message="본문" confirmLabel="삭제" busy={false} onConfirm={onConfirm} onCancel={onCancel} />,
    );
    expect(screen.getByRole('dialog', { name: '논문을 삭제할까요?' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '삭제' }));
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onCancel).toHaveBeenCalledTimes(1);

    rerender(<ConfirmDialog open title="논문을 삭제할까요?" message="본문" confirmLabel="삭제" busy onConfirm={onConfirm} onCancel={onCancel} />);
    expect((screen.getByRole('button', { name: '삭제' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: '취소' }) as HTMLButtonElement).disabled).toBe(true);
  });
});
```

- [ ] **Step 2: 실패 확인**

```bash
npm test -- src/routes/bookshelf/ConfirmDialog.test.tsx 2>&1 | tail -5
```

- [ ] **Step 3: 구현**

```tsx
// UploadDialog와 같은 오버레이 프레임의 소형 확인 모달. 오버레이 클릭은 취소.
import type { ReactNode } from 'react';

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  message: ReactNode;
  confirmLabel: string;
  busy: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export default function ConfirmDialog({ open, title, message, confirmLabel, busy, onConfirm, onCancel }: ConfirmDialogProps) {
  if (!open) return null;
  const btn = {
    fontFamily: 'var(--font-sans)',
    fontWeight: 600,
    fontSize: '12px',
    borderRadius: 'var(--radius-control)',
    padding: '7px 12px',
    cursor: busy ? 'not-allowed' : 'pointer',
    opacity: busy ? 0.6 : 1,
  } as const;
  return (
    <div
      onClick={busy ? undefined : onCancel}
      style={{ position: 'fixed', inset: 0, background: 'rgba(31,53,82,0.35)', zIndex: 60, display: 'flex', alignItems: 'center', justifyContent: 'center' }}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirm-dialog-title"
        onClick={(e) => e.stopPropagation()}
        style={{
          width: '360px',
          maxWidth: '90vw',
          background: 'var(--color-bg-paper)',
          border: '1px solid var(--color-border)',
          borderRadius: 'var(--radius-control)',
          boxShadow: 'var(--shadow-menu)',
          padding: '22px 22px 18px',
          boxSizing: 'border-box',
        }}
      >
        <div id="confirm-dialog-title" style={{ fontFamily: 'var(--font-serif)', fontSize: '16px', fontWeight: 600, color: 'var(--color-text-heading)', marginBottom: '8px' }}>
          {title}
        </div>
        <div style={{ fontFamily: 'var(--font-sans)', fontSize: '13px', color: 'var(--color-text-body)', lineHeight: 1.6, marginBottom: '18px' }}>
          {message}
        </div>
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px' }}>
          <button onClick={onCancel} disabled={busy} style={{ ...btn, border: '1px solid var(--color-border)', background: 'var(--color-bg-paper)', color: 'var(--color-text-body)' }}>
            취소
          </button>
          <button onClick={onConfirm} disabled={busy} style={{ ...btn, border: '1px solid var(--color-danger)', background: 'var(--color-danger)', color: '#FFFDF7' }}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: 통과 확인**

```bash
npm test -- src/routes/bookshelf/ConfirmDialog.test.tsx 2>&1 | tail -5 && npm run typecheck
```

- [ ] **Step 5: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add fe/src/routes/bookshelf/ConfirmDialog.tsx fe/src/routes/bookshelf/ConfirmDialog.test.tsx
git commit -m "[YMC-369] feat(fe): 삭제 확인 다이얼로그"
```

---

### Task C6: BookshelfPage 배선 + StudyPage 문구

**Files:**
- Modify: `fe/src/routes/BookshelfPage.tsx`
- Modify: `fe/src/routes/StudyPage.tsx:53-55`
- Modify: `fe/src/routes/bookshelf/UploadDialog.tsx:31`

**Interfaces:**
- Consumes: `renamePaper`, `deletePaper`, `getDownloadUrl` (C1), `PaperRowMenu` (C3), `PaperTitleEditor` (C4), `ConfirmDialog` (C5), `filterPapers`/`paginate`.

이 태스크는 컴포넌트 단위 테스트가 이미 있으므로 배선은 타입체크 + 전체 테스트 + 브라우저 수동 확인으로 검증한다.

- [ ] **Step 1: import·state 추가**

`BookshelfPage.tsx` import에 추가:

```tsx
import { getDownloadUrl, renamePaper, deletePaper } from '../api/papers';
import { ApiError } from '../api/types';
import PaperRowMenu from './bookshelf/PaperRowMenu';
import PaperTitleEditor from './bookshelf/PaperTitleEditor';
import ConfirmDialog from './bookshelf/ConfirmDialog';
```

`const [toast, setToast] = ...` 뒤에:

```tsx
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<Paper | null>(null);
  const [deleting, setDeleting] = useState(false);
```

- [ ] **Step 2: 핸들러 추가**

`handleSelectPaper` 함수 뒤에 추가:

```tsx
  // 캐시 형태는 { papers: Paper[] } — 응답으로 받은 행만 바꾼다(낙관적 업데이트 없음).
  function patchPapersCache(update: (papers: Paper[]) => Paper[]) {
    queryClient.setQueryData<{ papers: Paper[] }>(['papers'], (prev) =>
      prev ? { papers: update(prev.papers) } : prev,
    );
  }

  async function handleDownload(paper: Paper) {
    try {
      const { downloadUrl } = await getDownloadUrl(paper.paperId);
      window.location.assign(downloadUrl); // Content-Disposition: attachment가 서명돼 있어 페이지를 떠나지 않는다
    } catch (e) {
      showToast(e instanceof Error ? e.message : '다운로드 URL을 받지 못했습니다');
    }
  }

  async function handleRenameSave(paper: Paper, filename: string) {
    setRenamingId(null);
    try {
      const updated = await renamePaper(paper.paperId, filename);
      patchPapersCache((papers) => papers.map((p) => (p.paperId === updated.paperId ? updated : p)));
    } catch (e) {
      showToast(e instanceof Error ? e.message : '이름을 바꾸지 못했습니다');
    }
  }

  async function handleDeleteConfirm() {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await deletePaper(deleteTarget.paperId);
      patchPapersCache((papers) => papers.filter((p) => p.paperId !== deleteTarget.paperId));
      showToast('삭제했습니다');
    } catch (e) {
      if (e instanceof ApiError && e.httpStatus === 404) {
        queryClient.invalidateQueries({ queryKey: ['papers'] }); // 이미 지워진 논문 — 목록만 새로 맞춘다
      } else {
        showToast(e instanceof Error ? e.message : '삭제하지 못했습니다');
      }
    } finally {
      setDeleting(false);
      setDeleteTarget(null);
    }
  }
```

- [ ] **Step 3: 페이지 보정**

`const { items: pageItems, totalPages } = paginate(filtered, page, PAGE_SIZE);` 줄 뒤에:

```tsx
  // 삭제로 마지막 페이지가 비면 앞 페이지로 (검색 필터를 거친 결과 기준).
  useEffect(() => {
    if (page > totalPages) setPage(totalPages);
  }, [page, totalPages]);
```

- [ ] **Step 4: 행·카드에 props 전달**

R4 목록의 두 `map`을 다음으로 교체:

```tsx
                {pageItems.map((paper) => (
                  <PaperGridCard
                    key={paper.paperId}
                    paper={paper}
                    renaming={renamingId === paper.paperId}
                    onSelect={handleSelectPaper}
                    onDownload={() => handleDownload(paper)}
                    onRenameStart={() => setRenamingId(paper.paperId)}
                    onRenameSave={(name) => handleRenameSave(paper, name)}
                    onRenameCancel={() => setRenamingId(null)}
                    onDeleteRequest={() => setDeleteTarget(paper)}
                  />
                ))}
```

목록 뷰:

```tsx
                {pageItems.map((paper) => (
                  <PaperListRow
                    key={paper.paperId}
                    paper={paper}
                    renaming={renamingId === paper.paperId}
                    onSelect={handleSelectPaper}
                    onDownload={() => handleDownload(paper)}
                    onRenameStart={() => setRenamingId(paper.paperId)}
                    onRenameSave={(name) => handleRenameSave(paper, name)}
                    onRenameCancel={() => setRenamingId(null)}
                    onDeleteRequest={() => setDeleteTarget(paper)}
                  />
                ))}
```

`UploadDialog` 렌더 바로 뒤에 추가:

```tsx
      <ConfirmDialog
        open={deleteTarget !== null}
        title="논문을 삭제할까요?"
        message={
          <>
            <span style={{ fontFamily: 'var(--font-serif)', fontWeight: 600, color: 'var(--color-text-heading)' }}>
              {deleteTarget?.filename}
            </span>
            를 서재에서 지웁니다. 이 논문의 채팅 기록도 함께 사라지며 되돌릴 수 없습니다. 등록 횟수는 복구되지 않습니다.
          </>
        }
        confirmLabel="삭제"
        busy={deleting}
        onConfirm={handleDeleteConfirm}
        onCancel={() => { if (!deleting) setDeleteTarget(null); }}
      />
```

- [ ] **Step 5: PaperListRow 수정**

공용 props 타입을 파일 하단 컴포넌트들 위에 선언:

```tsx
interface PaperItemProps {
  paper: Paper;
  renaming: boolean;
  onSelect: (paper: Paper) => void;
  onDownload: () => void;
  onRenameStart: () => void;
  onRenameSave: (filename: string) => void;
  onRenameCancel: () => void;
  onDeleteRequest: () => void;
}
```

`PaperListRow`의 시그니처를 `function PaperListRow({ paper, renaming, onSelect, onDownload, onRenameStart, onRenameSave, onRenameCancel, onDeleteRequest }: PaperItemProps)`로 바꾸고, 제목 `<div style={{ flex: 1, minWidth: 0 }}><div style={{...}}>{paper.filename}</div></div>` 블록을 다음으로 교체:

```tsx
      <div style={{ flex: 1, minWidth: 0, display: 'flex' }}>
        <PaperTitleEditor
          filename={paper.filename}
          editing={renaming}
          titleStyle={{
            fontFamily: 'var(--font-serif)',
            fontSize: '16px',
            fontWeight: 600,
            color: 'var(--color-text-heading)',
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
          }}
          onSave={onRenameSave}
          onCancel={onRenameCancel}
        />
      </div>
```

`<StatusBadge paper={paper} />` 뒤에:

```tsx
      <PaperRowMenu paper={paper} onDownload={onDownload} onRename={onRenameStart} onDelete={onDeleteRequest} />
```

- [ ] **Step 6: PaperGridCard 수정**

시그니처를 `PaperItemProps`로 바꾸고, 루트 `<div role="button" ...>`의 style에 `position: 'relative'`를 추가한다. 썸네일 `<div>` 바로 뒤(제목 앞)에:

```tsx
      <div style={{ position: 'absolute', top: '24px', right: '24px', background: 'var(--color-bg-paper)', border: '1px solid var(--color-border)', borderRadius: 'var(--radius-control)' }}>
        <PaperRowMenu paper={paper} size={28} onDownload={onDownload} onRename={onRenameStart} onDelete={onDeleteRequest} />
      </div>
```

제목 `<div style={{ fontSize: 'var(--ui-strong-size)', ... }}>{paper.filename}</div>`를:

```tsx
      <div style={{ display: 'flex', marginBottom: '4px' }}>
        <PaperTitleEditor
          filename={paper.filename}
          editing={renaming}
          titleStyle={{
            fontSize: 'var(--ui-strong-size)',
            fontWeight: 'var(--ui-strong-weight)',
            color: 'var(--color-text-heading)',
            lineHeight: 1.3,
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            minWidth: 0,
            flex: 1,
          }}
          onSave={onRenameSave}
          onCancel={onRenameCancel}
        />
      </div>
```

- [ ] **Step 7: StudyPage·UploadDialog 문구**

`StudyPage.tsx`의 `if (statusQuery.isError)` 블록을:

```tsx
  if (statusQuery.isError) {
    const gone = statusQuery.error instanceof ApiError && statusQuery.error.httpStatus === 404;
    return <Navigate to="/library" replace state={{ toast: gone ? '삭제되었거나 없는 논문입니다' : '논문 상태를 불러오지 못했습니다' }} />;
  }
```

`ApiError`가 import돼 있지 않으면 `import { ApiError } from '../api/types';` 추가.

`UploadDialog.tsx` 31행 주석의 `409 DUPLICATE_FILENAME / ` 를 지운다.

- [ ] **Step 8: 타입체크·전체 테스트**

```bash
npm run typecheck && npm test 2>&1 | tail -8
```
Expected: 오류 없음, 전부 통과.

- [ ] **Step 9: 브라우저 확인**

로컬 BE(`infra/local` compose + `./gradlew bootRun`)가 떠 있으면 `.claude/launch.json`의 FE 서버로 preview를 열어 서재에서: 케밥 열기 → 이름 변경(Enter 저장, Esc 취소) → 다운로드(COMPLETED 행) → 삭제(다이얼로그 → 행 사라짐·토스트) → 삭제된 논문 URL 직접 진입 시 토스트. 격자 뷰에서도 같은 흐름. 스크린샷을 남긴다. BE를 못 띄우면 PR `## 검증`에 "브라우저 수동 확인 미실시"로 적는다.

- [ ] **Step 10: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add fe/src/routes/BookshelfPage.tsx fe/src/routes/StudyPage.tsx fe/src/routes/bookshelf/UploadDialog.tsx
git commit -m "[YMC-369] feat(fe): 서재 행 메뉴 배선 — 다운로드·인라인 이름 변경·삭제"
```

---

### Task C7: app PR

- [ ] **Step 1: push + PR**

project-docs PR 번호를 `<DOCS_PR>`에 넣는다.

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git push -u origin YMC-369-paper-management
gh pr create --title "[YMC-369] 서재 논문 관리 — 삭제·이름 변경·파일명 중복 허용" --body "$(cat <<'EOF'
## 배경

서재에서 논문을 정리할 수단이 없었고, 파일명 중복 금지 때문에 같은 이름의 다른 논문을 올릴 수 없었다. 설계: `docs/superpowers/specs/2026-09-05-paper-management-design.md`.

## 변경사항

- BE: `paper.deleted_at` 논리 삭제, `uk_paper_owner_filename`·`409 DUPLICATE_FILENAME` 제거, `PATCH`·`DELETE /api/papers/{paperId}`(`PaperManagementService`), 사용자 경로 6곳 `findActiveById`, 다운로드 Content-Disposition 파일명 정리
- FE: 행·카드 케밥 메뉴(다운로드 / 이름 변경 / 삭제), 인라인 이름 변경, 삭제 확인 다이얼로그, StudyPage 404 문구
- DDL: `be/docs/db/paper.sql`

**판단**
- 삭제 제외는 전역 필터 대신 사용자 경로만 `findActiveById` — 정산·정체 정리는 삭제 행을 계속 봐야 예약이 닫힌다.
- 삭제 직후 `complete` 경쟁은 수용(스펙 §7). 사용자에게 보이지 않고 사용량은 어차피 미복구.
- 이름 변경 진입은 메뉴만 — 행 단일 클릭이 열기라 더블클릭이 성립하지 않는다.

## 검증

- `./gradlew test` 통과 (신규: PaperManagementIntegrationTest, DeletedPaperVisibilityIntegrationTest, 정산·정체 정리 삭제 케이스, Content-Disposition)
- `npm run typecheck && npm test` 통과 (신규: PaperRowMenu, PaperTitleEditor, ConfirmDialog, pdfName, papers API)
- 브라우저 수동 확인: (결과 기재)
- 미검증: local·dev DB의 `alter table paper drop constraint if exists uk_paper_owner_filename` 수동 실행 필요 — ddl-auto가 제약을 지우지 않는다. prod는 `paper.sql`.

## 의존

- project-docs #<DOCS_PR> (계약 0.4.0)
EOF
)"
```

---

## 계획 자체 점검 결과

- 스펙 §2(계약) → A1. §3.1 → B1. §3.2 → B3. §3.3 → B4. §3.4 → B2. Content-Disposition → B5. §4.1 → C1. §4.2 → C2·C3·C4·C5. §4.3·4.4 → C6. §5 → A2·A3. §6 테스트 → 각 태스크 Step 1. §7 갭은 코드 변경 없음.
- 사용 이름 일치: `findActiveById`/`markDeleted`(B3 정의, B4 사용), `markDeletedByPaperId`(B4), `listView`(B4), `renamePaper`/`deletePaper`(C1 정의, C6 사용), `splitPdfName`/`joinPdfName`(C2 정의, C4 사용), `PaperRowMenu`/`PaperTitleEditor`/`ConfirmDialog` props(C3·C4·C5 정의, C6 사용), `PaperListResponse.Item.from` public 전환(B4).
