# 지식 그래프 viz.html 앱 서빙 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 컴파일 워커가 만든 `knowledge-bundle/viz.html`을 학습 화면 상단 바의 `지식 그래프` 버튼으로 여는 새 화면(`/papers/:paperId/graph`)에 presigned GET URL로 띄운다.

**Architecture:** BE는 컴파일 완료 시 manifest에서 viz.html 키를 `document.knowledge_graph_key`에 저장하고, `/status`에 `knowledgeGraphStatus`를 더하며, 새 엔드포인트가 소유권·준비 상태를 검증해 presigned URL을 준다. FE는 상단 바를 `StudyTopBar`로 뽑아 두 화면이 공유하고, 새 페이지가 URL을 받아 iframe에 넣는다. 계약(openapi 0.7.0)·FT-009·아트보드는 project-docs PR이 먼저다.

**Tech Stack:** Spring Boot 3 + Spring Data JPA + AWS SDK v2(S3 presigner) + LocalStack 통합 테스트 / React 19 + react-router 7 + TanStack Query 5 + vitest / OpenAPI 3.2

**Spec:** `docs/superpowers/specs/2026-09-17-knowledge-graph-serving-design.md`

## Global Constraints

- 저장소 3개가 얽힌다. project-docs 작업(Task 1~4)은 `../project-docs/`에서, app 작업(Task 5~15)은 이 저장소에서 한다. 한 저장소의 커밋에 다른 저장소 파일을 넣지 않는다.
- 커밋 메시지는 `[YMC-394] type(scope): subject` 한 줄. Co-Authored-By·Generated with 같은 attribution 줄을 넣지 않는다. 주석에 티켓·문서 출처 괄호를 넣지 않는다.
- 커밋·푸시·PR 생성은 각각 사용자 승인 뒤에만 한다. 각 Task의 "Commit" 단계는 사용자에게 파일 목록과 메시지를 보여주고 승인받은 뒤 실행한다.
- 새 에러 코드 `KNOWLEDGE_GRAPH_NOT_READY`, 새 필드 `knowledgeGraphStatus`, 새 경로 `GET /api/papers/{paperId}/knowledge-graph`는 계약(Task 1)이 먼저다. 이름을 바꾸지 않는다.
- `knowledgeGraphStatus` 값: `PENDING` / `READY` / `FAILED` / `null`(Document 연결 전).
- 사용자 문구(그대로 쓴다): PENDING `지식 그래프를 준비하고 있습니다`, FAILED `지식 그래프를 준비하지 못했습니다`, 발급 실패 `지식 그래프를 불러오지 못했습니다`, 링크 `본문으로`, 버튼 `다시 시도`.
- 상단 바 쌍의 라벨은 `본문`, `지식 그래프`. 현재 화면은 `aria-current="page"`.
- iframe: `title="지식 그래프"`, `sandbox="allow-scripts allow-same-origin"`.
- BE 테스트: `cd be && ./gradlew test --tests '<FQCN>'` (통합 테스트는 Docker의 LocalStack·PostgreSQL이 필요하다. `IntegrationTest`를 상속한 테스트가 그렇다). 전체는 `./gradlew test`.
- FE 테스트: `cd fe && npx vitest run <path>`. 전체 검증은 `npm test && npm run typecheck && npm run build`.
- viz 키는 manifest `artifacts.knowledge_bundle_viz.path`를 패키지 prefix(`manifestKey`에서 마지막 `/`까지)에 붙여 만든다. manifest의 `key` 필드는 쓰지 않는다.
- 종결된 compile_status(COMPLETED·FAILED)는 바꾸지 않는다. 키 보정 경로를 만들지 않는다.

---

## Part A — project-docs (계약·FT-009·아트보드)

### Task 1: openapi 0.7.0 — knowledgeGraphStatus·knowledge-graph 경로·에러 코드

**Files:**
- Modify: `../project-docs/contracts/frontend-backend/openapi.yaml`

**Interfaces:**
- Produces: 스키마 `KnowledgeGraphStatus`(enum PENDING/READY/FAILED), `KnowledgeGraphView { url, expiresAt }`, `PaperStatusResponse.knowledgeGraphStatus`(nullable, required), 경로 `GET /api/papers/{paperId}/knowledge-graph`(operationId `getKnowledgeGraphView`), 에러 코드 `KNOWLEDGE_GRAPH_NOT_READY`(409). Task 5~13이 이 이름을 그대로 쓴다.

- [ ] **Step 1: 브랜치 만들기 (origin/main 기준, HEAD 확인)**

```bash
cd ../project-docs
git status --short          # untracked 7개가 보인다. 이 Task에서는 건드리지 않는다
git fetch origin
git switch -c YMC-394-knowledge-graph-serving origin/main
git log --oneline -1        # #62 머지 커밋 이후여야 한다. openapi.yaml의 version이 0.6.0인지 확인:
grep -n "^  version:" contracts/frontend-backend/openapi.yaml
```

Expected: `version: 0.6.0`. 아니면 멈추고 사용자에게 알린다.

- [ ] **Step 2: 헤더 갱신**

`info.description`의 `- Source:` 목록 끝(`features/FT-011-플랜-사용량-제한.md` 뒤)에 `features/FT-009-구조-맵.md`를 추가하고 `version: 0.6.0` → `version: 0.7.0`.

- [ ] **Step 3: 새 경로 추가**

`/api/papers/{paperId}/download:` 블록이 끝나고 `/api/papers/{paperId}/content:`가 시작하기 직전에 삽입:

```yaml
  /api/papers/{paperId}/knowledge-graph:
    get:
      operationId: getKnowledgeGraphView
      summary: 지식 그래프 viz.html을 여는 presigned GET URL 발급
      description: |
        컴파일이 만든 지식 그래프 HTML(knowledge-bundle/viz.html)을 여는 presigned GET URL을 발급한다 (FT-009 Story 2).
        BE는 paperId의 소유권과 준비 상태만 검증하고 HTML 본문은 S3에서 직접 받는다 — 파일 바이트는 BE를 거치지 않는다
        (ADR-001, 다운로드와 대칭). Content-Disposition을 싣지 않아 브라우저가 인라인으로 렌더한다.
        FE는 이 URL을 iframe src로 쓴다. 만료 전에 로드가 끝나면 이후 만료는 표시에 영향이 없고, 화면에 다시 들어오면
        새로 호출한다.

        가용 조건은 `knowledgeGraphStatus`가 READY다. 그 외(PENDING·FAILED·Document 연결 전)는 409.
        FE는 학습 화면 상단 바의 `지식 그래프` 버튼을 READY일 때만 활성으로 두므로 이 409는 방어선이다.
      tags: [papers]
      parameters:
        - name: paperId
          in: path
          required: true
          schema: { type: string, format: uuid }
      responses:
        "200":
          description: presigned GET URL
          content:
            application/json:
              schema: { $ref: "#/components/schemas/KnowledgeGraphView" }
        "401":
          description: "인증 필요. code: UNAUTHORIZED"
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Error" }
        "403":
          description: "논문 접근 권한 없음. code: FORBIDDEN"
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Error" }
        "404":
          description: 존재하지 않는 paperId. code PAPER_NOT_FOUND
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Error" }
        "409":
          description: knowledgeGraphStatus가 READY가 아님(PENDING·FAILED·Document 연결 전). code KNOWLEDGE_GRAPH_NOT_READY
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Error" }

```

- [ ] **Step 4: 스키마 추가·수정**

`PaperDownload:` 스키마 블록 바로 뒤에 삽입:

```yaml
    KnowledgeGraphView:
      type: object
      required: [url, expiresAt]
      description: 지식 그래프 viz.html을 여는 presigned GET URL. FE는 iframe src로 쓴다.
      properties:
        url:
          type: string
          format: uri
          description: S3 presigned GET URL. Content-Disposition을 싣지 않아 인라인으로 렌더된다.
        expiresAt:
          type: string
          format: date-time
          description: presigned URL 만료 시각. 로드가 끝난 뒤의 만료는 표시에 영향이 없다.

```

`PaperStatusResponse:`를 다음으로 바꾼다(`required`에 `knowledgeGraphStatus` 추가, 속성 추가):

```yaml
    PaperStatusResponse:
      type: object
      required: [paperId, status, updatedAt, translationStatus, knowledgeGraphStatus]
      properties:
        paperId:
          type: string
          format: uuid
          description: 서재는 행마다 폴링하므로 응답이 뒤섞일 수 있다 — 응답만으로 판별하기 위해 싣는다.
        status:
          $ref: "#/components/schemas/PaperStatus"
        translationStatus:
          $ref: "#/components/schemas/TranslationStatus"
        knowledgeGraphStatus:
          oneOf:
            - $ref: "#/components/schemas/KnowledgeGraphStatus"
            - type: "null"
          description: |
            Document 연결 전(UPLOAD_PENDING·EXPIRED)에는 null. 컴파일 상태를 판단할 근거가 아직 없다는 뜻이며
            FE는 null이면 폴링하지 않는다.
        updatedAt:
          type: string
          format: date-time
          description: 파싱 상태가 마지막으로 바뀐 시각. 번역·지식 그래프 상태 변화는 반영하지 않는다.
```

`TranslationStatus:` 스키마 블록 바로 뒤에 삽입:

```yaml
    KnowledgeGraphStatus:
      type: string
      description: |
        지식 그래프(viz.html) 준비 상태. 파싱 `status`·`translationStatus`와 별개다 — 컴파일이 만드는 산출물 중
        번역과 지식 그래프는 같은 결과 메시지로 오지만 한국어 논문처럼 번역은 NOT_APPLICABLE이어도 지식 그래프는 있다.

        - PENDING: 컴파일 요청 전이거나 진행 중
        - READY: viz.html을 열 수 있다. GET /api/papers/{paperId}/knowledge-graph가 URL을 준다
        - FAILED: 컴파일 실패 또는 산출물 없음. 자동으로 다시 요청하지 않는다

        FE는 학습 화면에서 PENDING일 때만 status를 폴링한다. 서재에는 이 값을 표시하지 않는다.
      enum: [PENDING, READY, FAILED]

```

`Error.code` description 목록의 `- PAPER_USAGE_LIMIT_EXCEEDED` 줄 뒤에 추가:

```yaml
            - KNOWLEDGE_GRAPH_NOT_READY: 지식 그래프가 아직 준비되지 않음 (409, knowledge-graph)
```

같은 스키마의 `enum:` 배열 끝 `PAPER_USAGE_LIMIT_EXCEEDED,` 뒤에 `KNOWLEDGE_GRAPH_NOT_READY,` 추가.

- [ ] **Step 5: YAML 파싱 확인**

```bash
python3 -c "import yaml,sys; d=yaml.safe_load(open('contracts/frontend-backend/openapi.yaml')); print(d['info']['version']); print('/api/papers/{paperId}/knowledge-graph' in d['paths']); print('KnowledgeGraphStatus' in d['components']['schemas']); print('KNOWLEDGE_GRAPH_NOT_READY' in d['components']['schemas']['Error']['properties']['code']['enum'])"
```

Expected: `0.7.0`, `True`, `True`, `True`. (`yaml` 모듈이 없으면 `pip3 install pyyaml` 또는 `uv run --with pyyaml python3 -c ...`.)

- [ ] **Step 6: Commit (사용자 승인 후)**

```bash
git add contracts/frontend-backend/openapi.yaml
git commit -m "[YMC-394] docs(contracts): openapi 0.7.0 — knowledgeGraphStatus와 지식 그래프 URL 발급 경로 추가"
```

---

### Task 2: FT-009 문서와 Registry

**Files:**
- Modify: `../project-docs/features/FT-009-구조-맵.md` (전체 교체)
- Modify: `../project-docs/features/feature-spec.md` (FT-009 행)

- [ ] **Step 1: FT-009 문서 교체**

`features/FT-009-구조-맵.md` 전체를 다음으로 바꾼다:

```markdown
# Feature: FT-009 구조 맵

## 1. Overview

- Summary: 논문의 섹션 구조를 지식 그래프로 탐색하고 섹션 본문·번역을 그 자리에서 읽을 수 있다.
- Status: In Progress
- Tracking: YMC-394 · `contracts/frontend-backend/openapi.yaml` 0.7.0 (`getKnowledgeGraphView`, `PaperStatusResponse.knowledgeGraphStatus`)
- Depends on: FT-003 논문 등록·분석, FT-004 논문 학습 뷰어

## 2. Feature Boundary

### In Scope

- 학습 화면 상단 바에서 지식 그래프 화면으로 오가는 진입점과 준비 상태 표시
- AI 컴파일 워커가 만든 단독 HTML(`knowledge-bundle/viz.html`)을 앱 안 새 화면에 그대로 띄우는 서빙. 그래프·섹션 리더·번역 토글은 viz.html 자체 기능이다
- 컴파일 산출물 키 저장과 준비 상태 계산, 소유자 검증을 거친 presigned GET URL 발급

### Out of Scope

- FE가 구조 맵을 직접 그리는 것 → 하지 않는다. viz.html이 SSOT다
- 위키 Markdown 화면 → FT-008
- 선행지식 하이라이트 표시 → 후속(post-MVP)
- 컴파일 재요청·재파싱 UI → 후속(post-MVP)
- viz.html 야간 모드, viz.html의 CDN 스크립트 번들링 → AI 담당 영역

## 3. Screen References

| 화면 | 상태 · 트윅 | Relation |
|---|---|---|
| Paper Knowledge Graph Page | - | 이 Feature가 구현하는 화면 |
| Paper Study Page | 상단 바 `본문 \| 지식 그래프` 쌍 | 진입점 |

## 4. Stories

### Story 1. 사용자는 학습 화면 상단 바에서 지식 그래프 화면으로 오갈 수 있다 `MVP`

- type: USER
- Source: Paper Study Page, Paper Knowledge Graph Page
- Acceptance Criteria:
  - 두 화면의 상단 바에 `본문 | 지식 그래프` 쌍이 있고 현재 화면이 눌린 상태(`aria-current="page"`)로 보인다. 학습 화면에는 그 오른쪽에 `번역`·야간 모드 버튼이 그대로 있고, 지식 그래프 화면에는 없다.
  - `지식 그래프`는 `GET /api/papers/{paperId}/status`의 `knowledgeGraphStatus`가 READY일 때만 활성이다. PENDING이면 툴팁 `지식 그래프를 준비하고 있습니다`, FAILED이면 `지식 그래프를 준비하지 못했습니다`.
  - 학습 화면은 `translationStatus`나 `knowledgeGraphStatus`가 PENDING인 동안 status를 5초 간격으로 폴링하고, READY가 되면 새로고침 없이 버튼이 켜진다.
  - 지식 그래프 화면(`/papers/{paperId}/graph`)은 상단 바 아래 전체를 viz.html iframe으로 채운다. 준비되지 않은 상태로 URL 진입하면 같은 툴팁 문구와 `본문으로` 링크를 보여준다. URL 발급에 실패하면 `지식 그래프를 불러오지 못했습니다`와 `다시 시도`.
  - 파싱이 COMPLETED가 아닌 논문은 학습 화면과 같은 규칙으로 서재로 돌려보낸다.
- Depends on: FT-004 Story 1

### Story 2. 시스템은 컴파일이 만든 viz.html을 소유자에게만 제공한다 `MVP`

- type: SYSTEM
- Source: -
- Acceptance Criteria:
  - 컴파일 완료 결과를 반영할 때 manifest의 `knowledge_bundle_viz` artifact 경로를 Document에 저장한다. artifact가 없으면 저장하지 않고 경고만 남긴다. 컴파일 상태는 COMPLETED로 종결하며 계약 위반으로 다루지 않는다.
  - `knowledgeGraphStatus`는 compile_status가 없거나 REQUESTED면 PENDING, COMPLETED이고 키가 있으면 READY, 그 외(FAILED, COMPLETED인데 키 없음)는 FAILED다. Document 연결 전에는 null이다. `translationStatus` READY와 `knowledgeGraphStatus` FAILED가 함께 나올 수 있다.
  - 종결된 컴파일 상태는 바꾸지 않는다. 결과 메시지가 다시 와도 키를 채우지 않는다.
  - `GET /api/papers/{paperId}/knowledge-graph`는 소유자이고 READY일 때만 presigned GET URL을 발급한다. 그 외는 409 `KNOWLEDGE_GRAPH_NOT_READY`. URL은 Content-Disposition을 싣지 않는다.
- Depends on: FT-003 Story 4

## 5. Done Criteria

- Story 1·2의 Acceptance Criteria가 모두 충족되고 BE·FE 테스트가 통과한다.
- dev에서 새 논문을 올려 컴파일이 끝나면 학습 화면의 `지식 그래프` 버튼이 새로고침 없이 켜지고, 지식 그래프 화면에 viz.html이 렌더된다. 컴파일 이전에 SQL로 종결한 옛 문서는 FAILED 툴팁이다.

## 6. Open Questions

> **Resolved** — BE가 읽는 컴파일 산출물 manifest 필드는 ai 저장소 `docs/S3_BUCKET_STRUCTURE_KO.md`(`knowledge_bundle_viz`)가 정의한다. project-docs에 패키지 스키마 사본을 두지 않는다.
> **Resolved** — 서빙 방식은 presigned GET URL이다. BE 프록시와 CloudFront signed URL은 채택하지 않았다. 결정 근거는 app 저장소 `docs/superpowers/specs/2026-09-17-knowledge-graph-serving-design.md`.
```

- [ ] **Step 2: Registry 행 갱신**

`features/feature-spec.md`의 FT-009 행을 다음으로 바꾼다:

```markdown
| FT-009 | 구조 맵 | FT-003, FT-004 | - | In Progress | features/FT-009-구조-맵.md |
```

- [ ] **Step 3: 링크 확인**

```bash
grep -n "FT-009" features/feature-spec.md features/FT-009-구조-맵.md | head
test -f "design/v2/Paper Knowledge Graph Page.dc.html" && echo artboard-exists
```

- [ ] **Step 4: Commit (사용자 승인 후)**

```bash
git add features/FT-009-구조-맵.md features/feature-spec.md
git commit -m "[YMC-394] docs(features): FT-009 구조 맵 Story·Done Criteria 작성"
```

---

### Task 3: 아트보드 — 지식 그래프 화면 등록과 학습 화면 상단 바 쌍

**Files:**
- Create(커밋): `../project-docs/design/v2/Paper Knowledge Graph Page.dc.html` (untracked 초안 그대로)
- Create(커밋): `../project-docs/design/v2/knowledge-graph/ymc372/viz.html` (untracked, 546KB)
- Modify: `../project-docs/design/v2/Paper Study Page.dc.html` (상단 바)
- Modify: `../project-docs/design/v2/README.md`

- [ ] **Step 1: 학습 아트보드 상단 바 쌍으로 교체**

`design/v2/Paper Study Page.dc.html`에서 `<a href="./knowledge-graph/0.0v3/viz.html" aria-label="지식 그래프" ...>` 로 시작해 `<span>지식 그래프</span>` 다음 `</a>`로 끝나는 앵커 하나를 다음 두 앵커로 바꾼다(그 다음에 오는 번역 `<button>`은 그대로 둔다):

```html
        <a href="#" class="pt-topbar-btn" aria-current="page" aria-label="본문" title="본문">
          <i class="ph ph-book-open" style="font-size:17px"></i>
          <span>본문</span>
        </a>
        <a href="./Paper%20Knowledge%20Graph%20Page.dc.html" class="pt-topbar-btn" aria-label="지식 그래프" title="지식 그래프">
          <svg viewBox="0 0 24 24" aria-hidden="true" style="width:18px;height:18px;fill:none;stroke:currentColor;stroke-width:1.45;stroke-linecap:round;stroke-linejoin:round;flex-shrink:0">
            <path d="M5.2 6.6 10.7 4M13.2 4.4l5 3.1M5.5 8.7l2.2 6M10 15.8l6.1 1M18.4 9.5l-.9 5M9 14.6l2.5-8.4M12.9 6.2l3.9 1.9M9.7 16.8l2.3 2.1M16.1 17.8l-2.2 1.4"></path>
            <circle cx="4.5" cy="7.6" r="2"></circle>
            <circle cx="12" cy="3.8" r="1.8"></circle>
            <circle cx="19" cy="8.3" r="2"></circle>
            <circle cx="8.5" cy="16.2" r="2.1"></circle>
            <circle cx="17" cy="17" r="2"></circle>
            <circle cx="12.8" cy="20" r="1.4"></circle>
          </svg>
          <span>지식 그래프</span>
        </a>
```

같은 파일 helmet의 `<style>` 블록(24행 근처) 안 끝에 지식 그래프 아트보드와 같은 규칙을 추가:

```css
    .pt-topbar-btn{height:32px;display:flex;align-items:center;justify-content:center;gap:7px;flex-shrink:0;background:rgba(255,253,247,0.08);border:1px solid rgba(255,253,247,0.22);border-radius:8px;color:var(--color-on-dark);padding:0 10px 0 8px;font-family:var(--font-sans);font-size:13px;font-weight:600;cursor:pointer;text-decoration:none;white-space:nowrap}
    .pt-topbar-btn:hover{background:rgba(255,253,247,0.14)}
    .pt-topbar-btn[aria-current="page"]{background:var(--color-on-dark);color:var(--color-bg-walnut);border-color:var(--color-on-dark)}
```

- [ ] **Step 2: 지식 그래프 아트보드의 `본문` 링크 확인**

`design/v2/Paper Knowledge Graph Page.dc.html`의 `본문` 앵커가 `href="./Paper%20Study%20Page.dc.html"`인지 확인한다(초안 그대로면 이미 그렇다). 바꿀 것 없음.

- [ ] **Step 3: README 갱신**

`design/v2/README.md`의 `## Screens` 표에서 `| Study Page | ... |` 행 뒤에 추가:

```markdown
| Knowledge Graph Page | [Paper Knowledge Graph Page](Paper%20Knowledge%20Graph%20Page.dc.html) |
```

`## Screen States — 글로벌 상단 바 메뉴 (YMC-358)` 절 뒤, `## Design System` 앞에 추가:

```markdown
## Screen States — 지식 그래프 (FT-009 / YMC-394)

학습 화면 상단 바의 `본문 | 지식 그래프` 쌍으로 여는 별도 화면이다. 상단 바 아래 전체가 AI 컴파일 워커가 만든 단독 HTML(`knowledge-bundle/viz.html`)의 iframe이고, 그래프·섹션 리더·번역 토글은 viz.html 자체 기능이다. 아트보드의 iframe은 YMC-372 산출물 사본 `knowledge-graph/ymc372/viz.html`을 가리킨다. 옛 `knowledge-graph/0.0v3/`는 자체 상단 바가 있는 구 디자인이라 더 이상 참조하지 않는다.

| 상태 | 파일 | 요약 |
|---|---|---|
| 지식 그래프 화면 | [Paper Knowledge Graph Page](Paper%20Knowledge%20Graph%20Page.dc.html) | 상단 바는 학습 화면과 같되 `지식 그래프`가 눌린 상태(`aria-current="page"`, `--color-on-dark` 배경)이고 `번역`·야간 모드 버튼은 없다. |
| 학습 · 상단 바 쌍 | [Paper Study Page](Paper%20Study%20Page.dc.html) | 제목 오른쪽 `본문(눌림) | 지식 그래프` 쌍. `지식 그래프`는 컴파일 완료 전 비활성이며 툴팁은 준비 중 "지식 그래프를 준비하고 있습니다", 실패 "지식 그래프를 준비하지 못했습니다". |

데이터는 `GET /api/papers/{paperId}/status`의 `knowledgeGraphStatus`(PENDING/READY/FAILED/null)와 `GET /api/papers/{paperId}/knowledge-graph`(`contracts/frontend-backend/openapi.yaml` 0.7.0)를 따른다. READY일 때만 URL을 받아 iframe `src`로 넣는다. 준비되지 않은 상태로 URL 진입하면 캔버스 자리에 툴팁과 같은 문구와 `본문으로` 링크를 보여준다.

```

- [ ] **Step 4: 눈으로 확인**

```bash
python3 -m http.server 4321 --directory design/v2
```

브라우저에서 `http://localhost:4321/Paper%20Study%20Page.dc.html`과 `http://localhost:4321/Paper%20Knowledge%20Graph%20Page.dc.html`을 열어 상단 바 쌍이 두 화면에서 같은 위치·같은 눌림 표시로 보이는지, 지식 그래프 화면의 iframe에 그래프가 뜨는지(맞춤 버튼으로 가운데 정렬) 확인한다. 4321이 이미 쓰이면 다른 포트.

- [ ] **Step 5: Commit (사용자 승인 후, 디자인 파일만)**

```bash
git add "design/v2/Paper Knowledge Graph Page.dc.html" design/v2/knowledge-graph/ymc372/viz.html "design/v2/Paper Study Page.dc.html" design/v2/README.md
git status --short   # testing/, ADR-00X, backend-ai README 2개는 스테이지되지 않았는지 확인
git commit -m "[YMC-394] docs(design): 지식 그래프 화면 아트보드 등록과 학습 화면 상단 바 본문·지식 그래프 쌍"
```

---

### Task 4: project-docs PR

- [ ] **Step 1: 푸시·PR (각각 사용자 승인 후)**

```bash
git push -u origin YMC-394-knowledge-graph-serving
gh pr create --repo team-ymc/project-docs --title "[YMC-394] 지식 그래프 viz.html 앱 서빙 — 계약·FT-009·아트보드" --body-file - <<'EOF'
## 배경

컴파일 워커가 논문마다 `knowledge-bundle/viz.html`을 만들고 있지만 BE·FE가 소비하지 않는다. 학습 화면 아트보드의 `지식 그래프` 버튼(YMC-353)도 FE 미구현이다. 서빙 방식·상태 신호·라우트 결정은 app 저장소 `docs/superpowers/specs/2026-09-17-knowledge-graph-serving-design.md`.

## 변경

- `contracts/frontend-backend/openapi.yaml` 0.7.0: `PaperStatusResponse.knowledgeGraphStatus`(PENDING/READY/FAILED/null), `GET /api/papers/{paperId}/knowledge-graph`(presigned GET URL), 에러 코드 `KNOWLEDGE_GRAPH_NOT_READY`.
- `features/FT-009-구조-맵.md`: Story 2개·Done Criteria. Registry In Progress.
- `design/v2`: `Paper Knowledge Graph Page` 아트보드와 iframe 대상 viz.html 사본 등록, 학습 화면 상단 바를 `본문 | 지식 그래프` 쌍으로.

**판단** — BE가 읽는 manifest 필드는 ai 저장소 문서를 가리키기만 한다. `document-package.yml`은 두지 않는다.

## 의존

- app PR(BE+FE): 뒤따름
EOF
```

---

## Part B — BE (`app/be`)

### Task 5: KnowledgeGraphStatus enum과 Document 컬럼

**Files:**
- Create: `be/src/main/java/com/ymc/paper/domain/KnowledgeGraphStatus.java`
- Modify: `be/src/main/java/com/ymc/paper/domain/Document.java`
- Modify: `be/docs/db/document.sql`
- Test: `be/src/test/java/com/ymc/paper/domain/KnowledgeGraphStatusTest.java`

**Interfaces:**
- Produces: `KnowledgeGraphStatus { PENDING, READY, FAILED }` + `static KnowledgeGraphStatus of(CompileStatus, String knowledgeGraphKey)`; `Document.getKnowledgeGraphKey()`, `Document.knowledgeGraphStatus()`.

- [ ] **Step 1: 실패하는 테스트**

`be/src/test/java/com/ymc/paper/domain/KnowledgeGraphStatusTest.java`:

```java
package com.ymc.paper.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class KnowledgeGraphStatusTest {

    @ParameterizedTest(name = "compile={0}, key={1} → {2}")
    @CsvSource(nullValues = "null", value = {
            "null,      null,                            PENDING",
            "null,      papers/p/knowledge-bundle/viz.html, PENDING",
            "REQUESTED, null,                            PENDING",
            "REQUESTED, papers/p/knowledge-bundle/viz.html, PENDING",
            "COMPLETED, papers/p/knowledge-bundle/viz.html, READY",
            "COMPLETED, null,                            FAILED",
            "COMPLETED, '',                              FAILED",
            "COMPLETED, '   ',                           FAILED",
            "FAILED,    null,                            FAILED",
            "FAILED,    papers/p/knowledge-bundle/viz.html, FAILED",
    })
    void 컴파일_상태와_키_유무로_지식_그래프_상태를_계산한다(CompileStatus compile, String key, KnowledgeGraphStatus expected) {
        assertThat(KnowledgeGraphStatus.of(compile, key)).isEqualTo(expected);
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
cd be && ./gradlew test --tests 'com.ymc.paper.domain.KnowledgeGraphStatusTest'
```

Expected: 컴파일 실패 (`KnowledgeGraphStatus` 없음).

- [ ] **Step 3: enum 구현**

`be/src/main/java/com/ymc/paper/domain/KnowledgeGraphStatus.java`:

```java
package com.ymc.paper.domain;

/** API의 knowledgeGraphStatus. 컴파일 상태와 viz.html 키 유무에서 계산하며 DB에 따로 저장하지 않는다. */
public enum KnowledgeGraphStatus {
    PENDING,
    READY,
    FAILED;

    /**
     * 컴파일 상태가 없으면 아직 요청 전이라 PENDING이다. COMPLETED여도 키가 없으면 산출물이 없는 것이라 FAILED —
     * manifest에 artifact가 빠진 경우와 SQL로 종결한 옛 문서가 여기 해당한다.
     */
    public static KnowledgeGraphStatus of(CompileStatus compileStatus, String knowledgeGraphKey) {
        if (compileStatus == null) {
            return PENDING;
        }
        return switch (compileStatus) {
            case REQUESTED -> PENDING;
            case COMPLETED -> knowledgeGraphKey == null || knowledgeGraphKey.isBlank() ? FAILED : READY;
            case FAILED -> FAILED;
        };
    }
}
```

- [ ] **Step 4: Document 필드**

`Document.java`에서 `compileErrorCode` 필드 선언 뒤에 추가:

```java
    /** 컴파일이 만든 지식 그래프(viz.html)의 S3 키. COMPLETED인데 null이면 산출물이 없다. */
    @Column(name = "knowledge_graph_key")
    private String knowledgeGraphKey;
```

`translationStatus()` 메서드 뒤에 추가:

```java
    public KnowledgeGraphStatus knowledgeGraphStatus() {
        return KnowledgeGraphStatus.of(compileStatus, knowledgeGraphKey);
    }
```

- [ ] **Step 5: document.sql**

`compile_error_code varchar(255),` 줄 뒤에 추가:

```sql
    -- 컴파일이 만든 지식 그래프(viz.html)의 S3 키. COMPLETED인데 null이면 산출물 없음.
    knowledge_graph_key varchar(255),
```

"기존 DB 반영" 주석 블록의 `-- alter table document add column if not exists compile_error_code varchar(255);` 뒤에 추가:

```sql
-- alter table document add column if not exists knowledge_graph_key varchar(255);
```

- [ ] **Step 6: 통과 확인**

```bash
./gradlew test --tests 'com.ymc.paper.domain.KnowledgeGraphStatusTest' --tests 'com.ymc.paper.domain.TranslationStatusTest'
```

Expected: PASS.

- [ ] **Step 7: Commit (사용자 승인 후)**

```bash
git add be/src/main/java/com/ymc/paper/domain/KnowledgeGraphStatus.java be/src/main/java/com/ymc/paper/domain/Document.java be/docs/db/document.sql be/src/test/java/com/ymc/paper/domain/KnowledgeGraphStatusTest.java
git commit -m "[YMC-394] feat(paper): Document에 knowledge_graph_key와 지식 그래프 상태 계산 추가"
```

---

### Task 6: markCompiled에 키 저장

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/domain/DocumentRepository.java` (`markCompiled`)
- Modify: `be/src/main/java/com/ymc/paper/service/DocumentTransitions.java` (`markCompiled`)
- Modify: `be/src/main/java/com/ymc/paper/service/KnowledgeCompileResultService.java` (호출부 2곳, 임시로 null 전달)
- Modify: `be/src/test/java/com/ymc/paper/domain/DocumentPersistenceIntegrationTest.java`
- Modify: `be/src/test/java/com/ymc/paper/api/PaperStatusPollingIntegrationTest.java:117,128`
- Modify: `be/src/test/java/com/ymc/paper/api/PaperContentIntegrationTest.java:41`

**Interfaces:**
- Produces: `DocumentRepository.markCompiled(UUID id, CompileStatus status, String errorCode, String knowledgeGraphKey)`, `DocumentTransitions.markCompiled(UUID documentId, CompileStatus status, String errorCode, String knowledgeGraphKey)`.

- [ ] **Step 1: 실패하는 테스트**

`DocumentPersistenceIntegrationTest.java`의 기존 `markCompiled` 테스트(107·115행) 호출에 4번째 인자를 넣고, 다음 테스트를 클래스 끝에 추가:

```java
    @Test
    void 컴파일_완료는_지식_그래프_키를_함께_저장하고_종결_뒤에는_키를_바꾸지_않는다() {
        UUID id = insert(randomChecksum(), UUID.randomUUID());
        String key = "papers/" + id + "/knowledge-bundle/viz.html";

        assertThat(tx.<Integer>execute(s -> documentRepository.markCompiled(id, CompileStatus.COMPLETED, null, key)))
                .isEqualTo(1);
        Document completed = documentRepository.findById(id).orElseThrow();
        assertThat(completed.getKnowledgeGraphKey()).isEqualTo(key);
        assertThat(completed.knowledgeGraphStatus()).isEqualTo(KnowledgeGraphStatus.READY);

        // 종결 뒤 재전달: 키도 상태도 그대로
        assertThat(tx.<Integer>execute(s -> documentRepository.markCompiled(id, CompileStatus.COMPLETED, null, "other")))
                .isEqualTo(0);
        assertThat(documentRepository.findById(id).orElseThrow().getKnowledgeGraphKey()).isEqualTo(key);
    }

    @Test
    void 컴파일_완료인데_키가_없으면_READY가_아니라_FAILED다() {
        UUID id = insert(randomChecksum(), UUID.randomUUID());
        tx.execute(s -> documentRepository.markCompiled(id, CompileStatus.COMPLETED, null, null));
        Document document = documentRepository.findById(id).orElseThrow();
        assertThat(document.getCompileStatus()).isEqualTo(CompileStatus.COMPLETED);
        assertThat(document.knowledgeGraphStatus()).isEqualTo(KnowledgeGraphStatus.FAILED);
    }
```

기존 두 호출은 `documentRepository.markCompiled(id, CompileStatus.FAILED, "PARSED_DOCUMENT_INVALID", null)`와 `documentRepository.markCompiled(id, CompileStatus.COMPLETED, null, null)`로 바꾼다.

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests 'com.ymc.paper.domain.DocumentPersistenceIntegrationTest'
```

Expected: 컴파일 실패 (인자 수).

- [ ] **Step 3: 리포지토리·전이 수정**

`DocumentRepository.markCompiled`를 다음으로 바꾼다:

```java
    /** 컴파일 결과 수신 종결. null 포함 — 선점 커밋 전에 결과가 도착하는 경합을 흡수한다. 종결 뒤에는 키도 바꾸지 않는다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Document d
               set d.compileStatus = :status,
                   d.compileErrorCode = :errorCode,
                   d.knowledgeGraphKey = :knowledgeGraphKey
             where d.id = :id
               and (d.compileStatus is null
                    or d.compileStatus = com.ymc.paper.domain.CompileStatus.REQUESTED)
            """)
    int markCompiled(
            @Param("id") UUID id,
            @Param("status") CompileStatus status,
            @Param("errorCode") String errorCode,
            @Param("knowledgeGraphKey") String knowledgeGraphKey);
```

`DocumentTransitions.markCompiled`를 다음으로 바꾼다:

```java
    /** 컴파일 결과 종결. 호출자가 트랜잭션 안이면 거기에 참여한다. FAILED는 knowledgeGraphKey에 null을 넘긴다. */
    @Transactional
    public boolean markCompiled(UUID documentId, CompileStatus status, String errorCode, String knowledgeGraphKey) {
        if (status == null || status == CompileStatus.REQUESTED) {
            throw new IllegalArgumentException("컴파일 종결 상태만 허용됩니다: " + status);
        }
        return documentRepository.markCompiled(documentId, status, errorCode, knowledgeGraphKey) == 1;
    }
```

`KnowledgeCompileResultService.apply`의 두 호출을 임시로 `transitions.markCompiled(documentId, CompileStatus.COMPLETED, null, null);`와 `transitions.markCompiled(documentId, CompileStatus.FAILED, errorCode, null);`로 바꾼다(Task 8에서 실제 키를 넣는다).

`PaperStatusPollingIntegrationTest.java` 117행 → `documentTransitions.markCompiled(paper.getDocumentId(), CompileStatus.COMPLETED, null, "papers/" + paper.getId() + "/knowledge-bundle/viz.html");`, 128행 → `documentTransitions.markCompiled(paper.getDocumentId(), CompileStatus.FAILED, "PARSED_DOCUMENT_INVALID", null);`. `PaperContentIntegrationTest.java` 41행 → 4번째 인자 `null`.

```bash
grep -rn "markCompiled(" be/src | grep -v "int markCompiled\|boolean markCompiled"   # 모두 4개 인자인지 확인
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew test --tests 'com.ymc.paper.domain.DocumentPersistenceIntegrationTest' --tests 'com.ymc.paper.api.PaperStatusPollingIntegrationTest' --tests 'com.ymc.paper.api.PaperContentIntegrationTest' --tests 'com.ymc.paper.infra.messaging.KnowledgeCompileResultConsumptionIntegrationTest'
```

Expected: PASS.

- [ ] **Step 5: Commit (사용자 승인 후)**

```bash
git add be/src/main/java/com/ymc/paper/domain/DocumentRepository.java be/src/main/java/com/ymc/paper/service/DocumentTransitions.java be/src/main/java/com/ymc/paper/service/KnowledgeCompileResultService.java be/src/test/java/com/ymc/paper/domain/DocumentPersistenceIntegrationTest.java be/src/test/java/com/ymc/paper/api/PaperStatusPollingIntegrationTest.java be/src/test/java/com/ymc/paper/api/PaperContentIntegrationTest.java
git commit -m "[YMC-394] feat(paper): 컴파일 종결 CAS에 knowledge_graph_key 저장 추가"
```

---

### Task 7: manifest에서 viz.html 키 읽기

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/service/port/PaperPackageReader.java`
- Modify: `be/src/main/java/com/ymc/paper/infra/parsing/S3PaperPackageReader.java`
- Modify: `be/src/test/resources/fixtures/paper-package-translated/manifest.json`
- Test: `be/src/test/java/com/ymc/paper/infra/parsing/S3PaperPackageReaderTest.java`

**Interfaces:**
- Produces: `Optional<String> PaperPackageReader.readKnowledgeGraphKey(String manifestKey)`. 반환값은 `packagePrefix + artifacts.knowledge_bundle_viz.path` (예: `papers/{id}/knowledge-bundle/viz.html`).

- [ ] **Step 1: 픽스처 갱신**

`be/src/test/resources/fixtures/paper-package-translated/manifest.json`의 `artifacts`에 마지막 항목으로 추가(앞 항목 끝에 쉼표):

```json
    "knowledge_bundle_viz": {"path": "knowledge-bundle/viz.html", "media_type": "text/html; charset=utf-8"}
```

`paper-package/manifest.json`은 그대로(artifact 없음 케이스).

- [ ] **Step 2: 실패하는 테스트**

`S3PaperPackageReaderTest.java`의 `사이드카_S3_일시_장애는_전파한다` 테스트 뒤에 추가:

```java
    @Test
    void manifest의_knowledge_bundle_viz_path를_패키지_prefix에_붙여_돌려준다(CapturedOutput output) {
        Optional<String> key = reader.readKnowledgeGraphKey("papers/translated/manifest.json");

        assertThat(key).contains("papers/translated/knowledge-bundle/viz.html");
        assertThat(output.getOut()).doesNotContain("knowledge_bundle_viz");
    }

    @Test
    void manifest에_knowledge_bundle_viz가_없으면_empty와_WARN이다(CapturedOutput output) {
        assertThat(reader.readKnowledgeGraphKey("papers/p1/manifest.json")).isEmpty();
        assertThat(output.getOut()).contains("knowledge_bundle_viz");
    }

    @Test
    void knowledge_bundle_viz의_path가_비어_있으면_empty와_WARN이다(CapturedOutput output) {
        Map<String, String> files = new HashMap<>();
        files.put("papers/blank/manifest.json", """
                {
                  "manifest_version": 3,
                  "document_id": "blank",
                  "artifacts": {
                    "frontend_document": {"path": "frontend/document.json"},
                    "structure_document": {"path": "structure/document.json"},
                    "knowledge_bundle_viz": {"path": ""}
                  }
                }
                """);
        S3PaperPackageReader blankReader = new S3PaperPackageReader(mapStorage(files), new ObjectMapper());

        assertThat(blankReader.readKnowledgeGraphKey("papers/blank/manifest.json")).isEmpty();
        assertThat(output.getOut()).contains("knowledge_bundle_viz");
    }

    @Test
    void manifest_S3_일시_장애는_지식_그래프_키_읽기에서도_전파한다() {
        FileStorage failing = new FileStorage() {
            @Override
            public String readUtf8(String fileKey) {
                throw S3Exception.builder().statusCode(503).message("Slow Down").build();
            }

            @Override
            public PresignedUpload presignUpload(
                    String fileKey, String contentType, long contentLength, String checksumSha256) {
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
            public Optional<UploadedObjectMetadata> head(String fileKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void delete(String fileKey) {
                throw new UnsupportedOperationException();
            }
        };
        S3PaperPackageReader failingReader = new S3PaperPackageReader(failing, new ObjectMapper());

        assertThatThrownBy(() -> failingReader.readKnowledgeGraphKey("papers/s3err/manifest.json"))
                .isInstanceOf(S3Exception.class);
    }
```

- [ ] **Step 3: 실패 확인**

```bash
./gradlew test --tests 'com.ymc.paper.infra.parsing.S3PaperPackageReaderTest'
```

Expected: 컴파일 실패 (`readKnowledgeGraphKey` 없음).

- [ ] **Step 4: 포트·구현**

`PaperPackageReader.java`의 `readTranslations` 선언 뒤에 추가:

```java
    /**
     * 컴파일 산출물 지식 그래프(knowledge-bundle/viz.html)의 S3 키. manifest의 knowledge_bundle_viz.path를
     * 다른 artifact와 같은 규칙으로 패키지 prefix에 붙인다.
     *
     * @return artifact가 없거나 path가 비어 있으면 WARN 후 empty. manifest 자체를 못 읽으면 예외.
     */
    Optional<String> readKnowledgeGraphKey(String manifestKey);
```

`import java.util.Optional;` 추가.

`S3PaperPackageReader.java`:
- `import java.util.Optional;` 추가.
- `readTranslations` 메서드 뒤에 추가:

```java
    @Override
    public Optional<String> readKnowledgeGraphKey(String manifestKey) {
        String prefix = packagePrefix(manifestKey);
        Manifest manifest = parse(fileStorage.readUtf8(manifestKey), Manifest.class, manifestKey);
        Manifest.Artifact viz = manifest.artifacts() == null ? null : manifest.artifacts().knowledgeBundleViz();
        if (viz == null || viz.path() == null || viz.path().isBlank()) {
            log.warn("manifest에 knowledge_bundle_viz가 없습니다, 지식 그래프 없이 진행: manifestKey={}", manifestKey);
            return Optional.empty();
        }
        return Optional.of(prefix + viz.path());
    }
```

- `Manifest.Artifacts` record에 필드 추가:

```java
        record Artifacts(
                @JsonProperty("frontend_document") Artifact frontendDocument,
                @JsonProperty("structure_document") Artifact structureDocument,
                @JsonProperty("frontend_translation_ko") Artifact frontendTranslationKo,
                @JsonProperty("knowledge_bundle_viz") Artifact knowledgeBundleViz) {
        }
```

- [ ] **Step 5: 통과 확인**

```bash
./gradlew test --tests 'com.ymc.paper.infra.parsing.S3PaperPackageReaderTest'
```

Expected: PASS (기존 테스트 포함).

- [ ] **Step 6: Commit (사용자 승인 후)**

```bash
git add be/src/main/java/com/ymc/paper/service/port/PaperPackageReader.java be/src/main/java/com/ymc/paper/infra/parsing/S3PaperPackageReader.java be/src/test/resources/fixtures/paper-package-translated/manifest.json be/src/test/java/com/ymc/paper/infra/parsing/S3PaperPackageReaderTest.java
git commit -m "[YMC-394] feat(paper): manifest의 knowledge_bundle_viz에서 지식 그래프 키 읽기"
```

---

### Task 8: 컴파일 결과 반영 시 키 저장

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/service/KnowledgeCompileResultService.java`
- Test: `be/src/test/java/com/ymc/paper/infra/messaging/KnowledgeCompileResultConsumptionIntegrationTest.java`

**Interfaces:**
- Consumes: `PaperPackageReader.readKnowledgeGraphKey`, `DocumentTransitions.markCompiled(…, knowledgeGraphKey)`.

- [ ] **Step 1: 실패하는 테스트**

`KnowledgeCompileResultConsumptionIntegrationTest.java`:
- import 추가: `import com.ymc.paper.domain.KnowledgeGraphStatus;`
- `completedMergesAndMarksReady`의 `assertThat(document.translationStatus()).isEqualTo(TranslationStatus.READY);` 뒤에 추가:

```java
        assertThat(document.getKnowledgeGraphKey())
                .isEqualTo("papers/" + paper.getId() + "/knowledge-bundle/viz.html");
        assertThat(document.knowledgeGraphStatus()).isEqualTo(KnowledgeGraphStatus.READY);
```

- `failedRecordsCodeOnly`의 `assertThat(document.translationStatus()).isEqualTo(TranslationStatus.FAILED);` 뒤에:

```java
        assertThat(document.getKnowledgeGraphKey()).isNull();
        assertThat(document.knowledgeGraphStatus()).isEqualTo(KnowledgeGraphStatus.FAILED);
```

- `completedWithoutSidecarStillCompletes` 끝에(paper-package 픽스처는 knowledge_bundle_viz도 없다):

```java
        Document document = documentOf(paper);
        assertThat(document.getCompileStatus()).isEqualTo(CompileStatus.COMPLETED);
        assertThat(document.getKnowledgeGraphKey()).isNull();
        assertThat(document.knowledgeGraphStatus()).isEqualTo(KnowledgeGraphStatus.FAILED);
```

- `duplicateResultIsConsumed` 끝에:

```java
        assertThat(documentOf(paper).getKnowledgeGraphKey())
                .isEqualTo("papers/" + paper.getId() + "/knowledge-bundle/viz.html");
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests 'com.ymc.paper.infra.messaging.KnowledgeCompileResultConsumptionIntegrationTest'
```

Expected: `completedMergesAndMarksReady`·`duplicateResultIsConsumed` 실패 (키 null).

- [ ] **Step 3: 서비스 수정**

`KnowledgeCompileResultService.java`:
- import 추가: `import com.ymc.paper.service.port.PaperPackageReader;`
- 필드 추가: `private final PaperPackageReader packageReader;`
- COMPLETED 분기를 다음으로 바꾼다:

```java
        if (terminal == CompileStatus.COMPLETED) {
            int merged = mergeService.merge(documentId, manifestKey);
            if (merged == 0 && "en".equals(document.getSourceLanguage())) {
                log.warn("영어 문서인데 병합된 번역이 없습니다, READY로 응답되지만 번역 블록 없음: requestPaperId={}, "
                        + "documentId={}, manifestKey={}", requestPaperId, documentId, manifestKey);
            }
            // manifest를 한 번 더 읽는다. 재파싱 경로가 없어 두 읽기 사이에 manifest가 바뀌지 않는다.
            String knowledgeGraphKey = packageReader.readKnowledgeGraphKey(manifestKey).orElse(null);
            transitions.markCompiled(documentId, CompileStatus.COMPLETED, null, knowledgeGraphKey);
            log.info("컴파일 완료 반영: requestPaperId={}, documentId={}, mergedBlocks={}, knowledgeGraphKey={}",
                    requestPaperId, documentId, merged, knowledgeGraphKey);
            return;
        }
        transitions.markCompiled(documentId, CompileStatus.FAILED, errorCode, null);
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew test --tests 'com.ymc.paper.infra.messaging.KnowledgeCompileResultConsumptionIntegrationTest' --tests 'com.ymc.paper.service.DocumentTranslationMergeIntegrationTest'
```

Expected: PASS.

- [ ] **Step 5: Commit (사용자 승인 후)**

```bash
git add be/src/main/java/com/ymc/paper/service/KnowledgeCompileResultService.java be/src/test/java/com/ymc/paper/infra/messaging/KnowledgeCompileResultConsumptionIntegrationTest.java
git commit -m "[YMC-394] feat(paper): 컴파일 완료 반영 시 지식 그래프 키 저장"
```

---

### Task 9: `/status`에 knowledgeGraphStatus

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/service/PaperStatusView.java`
- Modify: `be/src/main/java/com/ymc/paper/service/PaperDocumentViews.java` (`statusView`)
- Modify: `be/src/main/java/com/ymc/paper/api/dto/PaperStatusResponse.java`
- Modify: `be/src/main/java/com/ymc/paper/api/PaperController.java` (`toResponse`)
- Test: `be/src/test/java/com/ymc/paper/api/PaperStatusPollingIntegrationTest.java`

**Interfaces:**
- Produces: `PaperStatusView(UUID paperId, PaperStatus status, TranslationStatus translationStatus, KnowledgeGraphStatus knowledgeGraphStatus, Instant updatedAt)`; 응답 JSON `knowledgeGraphStatus`(null 가능, 항상 존재).

- [ ] **Step 1: 실패하는 테스트**

`PaperStatusPollingIntegrationTest.java`:
- import 추가: `import static org.hamcrest.Matchers.nullValue;`
- `reportsTranslationStatus`의 네 `mockMvc.perform(...)` 체인에 각각 `.andExpect(jsonPath("$.knowledgeGraphStatus").value(...))`를 추가한다. 순서대로 `"PENDING"`(적재 전, compile null), `"PENDING"`(적재 뒤), `"PENDING"`(REQUESTED), `"READY"`(markCompiled COMPLETED + 키. Task 6에서 키를 넘기도록 이미 바꿨다).
- `reportsFailedTranslation`의 status 조회 체인에 `.andExpect(jsonPath("$.knowledgeGraphStatus").value("FAILED"))` 추가.
- `pendingUploadIsNotApplicable`의 체인에 `.andExpect(jsonPath("$.knowledgeGraphStatus").value(nullValue()))` 추가.
- 클래스 끝에 추가:

```java
    @Test
    @DisplayName("지식 그래프 상태: 컴파일 COMPLETED인데 키가 없으면 번역 READY라도 FAILED")
    void reportsFailedGraphWhenCompletedWithoutKey() throws Exception {
        Paper paper = givenProcessingPaper("graph-no-key.pdf");
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        documentContentIngestService.ingest(paper.getDocumentId(), givenTranslatedPackageOnS3(paper.getId()));
        documentTransitions.markCompiled(paper.getDocumentId(), CompileStatus.COMPLETED, null, null);

        mockMvc.perform(get("/api/papers/{paperId}/status", paper.getId()).with(userJwt()))
                .andExpect(jsonPath("$.translationStatus").value("READY"))
                .andExpect(jsonPath("$.knowledgeGraphStatus").value("FAILED"));
    }

    @Test
    @DisplayName("지식 그래프 상태: 언어 없는 문서는 번역 NOT_APPLICABLE이어도 그래프는 READY")
    void reportsReadyGraphForNonEnglishPaper() throws Exception {
        Paper paper = givenProcessingPaper("graph-ko.pdf");
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        documentContentIngestService.ingest(paper.getDocumentId(), givenPackageOnS3(paper.getId()));   // 언어 null
        documentTransitions.markCompiled(paper.getDocumentId(), CompileStatus.COMPLETED, null,
                "papers/" + paper.getId() + "/knowledge-bundle/viz.html");

        mockMvc.perform(get("/api/papers/{paperId}/status", paper.getId()).with(userJwt()))
                .andExpect(jsonPath("$.translationStatus").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.knowledgeGraphStatus").value("READY"));
    }
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests 'com.ymc.paper.api.PaperStatusPollingIntegrationTest'
```

Expected: `knowledgeGraphStatus` 경로 없음으로 실패.

- [ ] **Step 3: 구현**

`PaperStatusView.java`:

```java
package com.ymc.paper.service;

import java.time.Instant;
import java.util.UUID;

import com.ymc.paper.domain.KnowledgeGraphStatus;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.paper.domain.TranslationStatus;

/** 상태 응답의 재료. 엔티티를 api 레이어로 넘기지 않기 위한 값 (be/CLAUDE.md). knowledgeGraphStatus는 Document 연결 전 null. */
public record PaperStatusView(
        UUID paperId, PaperStatus status, TranslationStatus translationStatus,
        KnowledgeGraphStatus knowledgeGraphStatus, Instant updatedAt) {
}
```

`PaperDocumentViews.statusView`:

```java
    public PaperStatusView statusView(Paper paper) {
        Document document = documentOf(paper).orElse(null);
        TranslationStatus translationStatus = document == null
                ? TranslationStatus.NOT_APPLICABLE : document.translationStatus();
        // 연결 전에는 컴파일 상태를 판단할 근거가 없다 — PENDING으로 뭉개지 않고 null로 드러낸다.
        KnowledgeGraphStatus knowledgeGraphStatus = document == null ? null : document.knowledgeGraphStatus();
        return new PaperStatusView(paper.getId(), derivedStatus(paper, document), translationStatus,
                knowledgeGraphStatus, derivedUpdatedAt(paper, document));
    }
```

import `com.ymc.paper.domain.KnowledgeGraphStatus` 추가.

`PaperStatusResponse.java`:

```java
public record PaperStatusResponse(
        UUID paperId, PaperStatus status, TranslationStatus translationStatus,
        KnowledgeGraphStatus knowledgeGraphStatus, Instant updatedAt) {
}
```

import 추가. `PaperController.toResponse`:

```java
    private static PaperStatusResponse toResponse(PaperStatusView view) {
        return new PaperStatusResponse(view.paperId(), view.status(), view.translationStatus(),
                view.knowledgeGraphStatus(), view.updatedAt());
    }
```

`PaperStatusView`를 생성하는 다른 곳이 있는지 확인해 5개 인자로 맞춘다:

```bash
grep -rn "new PaperStatusView(" be/src
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew test --tests 'com.ymc.paper.api.PaperStatusPollingIntegrationTest' --tests 'com.ymc.paper.api.PaperUploadCompletionIntegrationTest'
```

Expected: PASS. `pendingUploadIsNotApplicable`에서 `knowledgeGraphStatus`가 JSON에 `null`로 존재하는지(경로 없음 오류가 아닌지) 확인.

- [ ] **Step 5: Commit (사용자 승인 후)**

```bash
git add be/src/main/java/com/ymc/paper/service/PaperStatusView.java be/src/main/java/com/ymc/paper/service/PaperDocumentViews.java be/src/main/java/com/ymc/paper/api/dto/PaperStatusResponse.java be/src/main/java/com/ymc/paper/api/PaperController.java be/src/test/java/com/ymc/paper/api/PaperStatusPollingIntegrationTest.java
git commit -m "[YMC-394] feat(paper): 상태 응답에 knowledgeGraphStatus 추가"
```

---

### Task 10: `GET /api/papers/{paperId}/knowledge-graph`

**Files:**
- Modify: `be/src/main/java/com/ymc/common/error/ErrorCode.java`
- Create: `be/src/main/java/com/ymc/paper/service/KnowledgeGraphViewService.java`
- Create: `be/src/main/java/com/ymc/paper/api/dto/KnowledgeGraphViewResponse.java`
- Modify: `be/src/main/java/com/ymc/paper/api/PaperController.java`
- Test: `be/src/test/java/com/ymc/paper/api/KnowledgeGraphViewIntegrationTest.java`

**Interfaces:**
- Consumes: `AssetUrlCache.issue(String s3Key): PresignedDownload`, `PaperDocumentViews.documentOf(Paper)`.
- Produces: `ErrorCode.KNOWLEDGE_GRAPH_NOT_READY`(409), `KnowledgeGraphViewService.view(UUID paperId, UUID ownerId): PresignedDownload`, 응답 `{ url, expiresAt }`.

- [ ] **Step 1: 실패하는 테스트**

`be/src/test/java/com/ymc/paper/api/KnowledgeGraphViewIntegrationTest.java`:

```java
package com.ymc.paper.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ymc.paper.domain.CompileStatus;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

class KnowledgeGraphViewIntegrationTest extends IntegrationTest {

    private static final String VIZ_SUFFIX = "/knowledge-bundle/viz.html";

    /** 파싱·적재까지 끝난 논문. 컴파일 상태는 각 테스트가 정한다. */
    private Paper givenIngestedPaper(String filename) {
        Paper paper = givenProcessingPaper(filename);
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        documentContentIngestService.ingest(paper.getDocumentId(), givenTranslatedPackageOnS3(paper.getId()));
        return reload(paper.getId());
    }

    @Test
    @DisplayName("READY: 200과 viz.html을 가리키는 presigned URL, Content-Disposition 없음")
    void returnsPresignedUrlWhenReady() throws Exception {
        Paper paper = givenIngestedPaper("graph-ready.pdf");
        documentTransitions.markCompiled(paper.getDocumentId(), CompileStatus.COMPLETED, null,
                "papers/" + paper.getId() + VIZ_SUFFIX);

        mockMvc.perform(get("/api/papers/{id}/knowledge-graph", paper.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(containsString(paper.getId() + VIZ_SUFFIX)))
                .andExpect(jsonPath("$.url").value(not(containsString("response-content-disposition"))))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    @DisplayName("PENDING(REQUESTED): 409 KNOWLEDGE_GRAPH_NOT_READY")
    void rejectsWhilePending() throws Exception {
        Paper paper = givenIngestedPaper("graph-pending.pdf");
        documentTransitions.markCompileRequested(paper.getDocumentId());

        mockMvc.perform(get("/api/papers/{id}/knowledge-graph", paper.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_GRAPH_NOT_READY"));
    }

    @Test
    @DisplayName("FAILED: 409 KNOWLEDGE_GRAPH_NOT_READY")
    void rejectsWhenFailed() throws Exception {
        Paper paper = givenIngestedPaper("graph-failed.pdf");
        documentTransitions.markCompiled(paper.getDocumentId(), CompileStatus.FAILED, "PARSED_DOCUMENT_INVALID", null);

        mockMvc.perform(get("/api/papers/{id}/knowledge-graph", paper.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_GRAPH_NOT_READY"));
    }

    @Test
    @DisplayName("COMPLETED인데 키 없음: 409 KNOWLEDGE_GRAPH_NOT_READY")
    void rejectsWhenCompletedWithoutKey() throws Exception {
        Paper paper = givenIngestedPaper("graph-no-key.pdf");
        documentTransitions.markCompiled(paper.getDocumentId(), CompileStatus.COMPLETED, null, null);

        mockMvc.perform(get("/api/papers/{id}/knowledge-graph", paper.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_GRAPH_NOT_READY"));
    }

    @Test
    @DisplayName("업로드 전(Document 미연결): 409 KNOWLEDGE_GRAPH_NOT_READY")
    void rejectsPendingUpload() throws Exception {
        Paper paper = givenPendingPaper("graph-upload-pending.pdf");

        mockMvc.perform(get("/api/papers/{id}/knowledge-graph", paper.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_GRAPH_NOT_READY"));
    }

    @Test
    @DisplayName("없는 paperId: 404 PAPER_NOT_FOUND")
    void rejectsUnknownPaperId() throws Exception {
        mockMvc.perform(get("/api/papers/{id}/knowledge-graph", UUID.randomUUID()).with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
    }

    @Test
    @DisplayName("남의 논문: presigned URL을 발급하지 않고 403 FORBIDDEN")
    void rejectsOtherUsersPaper() throws Exception {
        Paper paper = givenIngestedPaper("graph-someone-else.pdf");
        documentTransitions.markCompiled(paper.getDocumentId(), CompileStatus.COMPLETED, null,
                "papers/" + paper.getId() + VIZ_SUFFIX);

        mockMvc.perform(get("/api/papers/{id}/knowledge-graph", paper.getId()).with(otherUserJwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(fileStorage, never()).presignAssetGet(any());
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests 'com.ymc.paper.api.KnowledgeGraphViewIntegrationTest'
```

Expected: 404 또는 405로 실패 (경로 없음).

- [ ] **Step 3: 구현**

`ErrorCode.java`의 `PAPER_USAGE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS);`를 `,`로 바꾸고 뒤에 추가:

```java
    /** 지식 그래프가 아직 준비되지 않음 — PENDING·FAILED·Document 연결 전 (FT-009) */
    KNOWLEDGE_GRAPH_NOT_READY(HttpStatus.CONFLICT);
```

`be/src/main/java/com/ymc/paper/api/dto/KnowledgeGraphViewResponse.java`:

```java
package com.ymc.paper.api.dto;

import java.time.Instant;

import com.ymc.paper.service.port.PresignedDownload;

/** 계약 `KnowledgeGraphView`. */
public record KnowledgeGraphViewResponse(String url, Instant expiresAt) {

    public static KnowledgeGraphViewResponse from(PresignedDownload presigned) {
        return new KnowledgeGraphViewResponse(presigned.url(), presigned.expiresAt());
    }
}
```

`be/src/main/java/com/ymc/paper/service/KnowledgeGraphViewService.java`:

```java
package com.ymc.paper.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.KnowledgeGraphStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.paper.service.port.PresignedDownload;

import lombok.RequiredArgsConstructor;

/**
 * 지식 그래프 viz.html을 여는 presigned GET URL 발급. 발급 전이 유일한 검증 지점이다 — 발급된 URL은 BE를 거치지 않는다.
 * 만료 창 안에서는 같은 URL을 돌려줘 브라우저 캐시가 산다.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeGraphViewService {

    private final PaperRepository paperRepository;
    private final PaperDocumentViews views;
    private final AssetUrlCache assetUrlCache;

    /**
     * @throws ApiException {@code PAPER_NOT_FOUND} — 존재하지 않는 paperId
     * @throws ApiException {@code FORBIDDEN} — 소유자가 아님
     * @throws ApiException {@code KNOWLEDGE_GRAPH_NOT_READY} — Document 미연결이거나 READY가 아님
     */
    @Transactional(readOnly = true)
    public PresignedDownload view(UUID paperId, UUID ownerId) {
        Paper paper = paperRepository.findActiveById(paperId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.PAPER_NOT_FOUND, "존재하지 않는 논문입니다: " + paperId));
        if (!paper.getOwnerId().equals(ownerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "이 논문에 접근할 권한이 없습니다.");
        }
        Document document = views.documentOf(paper).orElse(null);
        if (document == null || document.knowledgeGraphStatus() != KnowledgeGraphStatus.READY) {
            throw new ApiException(ErrorCode.KNOWLEDGE_GRAPH_NOT_READY, "지식 그래프가 아직 준비되지 않았습니다: " + paperId);
        }
        return assetUrlCache.issue(document.getKnowledgeGraphKey());
    }
}
```

`PaperController.java`:
- import 추가: `com.ymc.paper.api.dto.KnowledgeGraphViewResponse`, `com.ymc.paper.service.KnowledgeGraphViewService`.
- 필드 추가: `private final KnowledgeGraphViewService knowledgeGraphViewService;`
- `download` 메서드 뒤에 추가:

```java
    /** 지식 그래프 viz.html을 여는 presigned GET URL 발급. */
    @GetMapping("/{paperId}/knowledge-graph")
    public KnowledgeGraphViewResponse knowledgeGraph(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paperId) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        return KnowledgeGraphViewResponse.from(knowledgeGraphViewService.view(paperId, ownerId));
    }
```

- [ ] **Step 4: 통과 확인**

```bash
./gradlew test --tests 'com.ymc.paper.api.KnowledgeGraphViewIntegrationTest' --tests 'com.ymc.paper.api.PaperDownloadIntegrationTest'
```

Expected: PASS.

- [ ] **Step 5: BE 전체 테스트**

```bash
./gradlew test
```

Expected: 전부 PASS. 실패가 있으면 여기서 고친다.

- [ ] **Step 6: Commit (사용자 승인 후)**

```bash
git add be/src/main/java/com/ymc/common/error/ErrorCode.java be/src/main/java/com/ymc/paper/service/KnowledgeGraphViewService.java be/src/main/java/com/ymc/paper/api/dto/KnowledgeGraphViewResponse.java be/src/main/java/com/ymc/paper/api/PaperController.java be/src/test/java/com/ymc/paper/api/KnowledgeGraphViewIntegrationTest.java
git commit -m "[YMC-394] feat(paper): 지식 그래프 presigned URL 발급 엔드포인트 추가"
```

---

## Part C — FE (`app/fe`)

### Task 11: 타입·API 클라이언트·상태 헬퍼·공용 상태 쿼리

**Files:**
- Modify: `fe/src/api/types.ts`
- Modify: `fe/src/api/papers.ts`
- Create: `fe/src/routes/study/knowledgeGraphStatus.ts`
- Create: `fe/src/routes/study/usePaperStatusQuery.ts`
- Test: `fe/src/api/papers.test.ts`, `fe/src/routes/study/knowledgeGraphStatus.test.ts`

**Interfaces:**
- Produces: `KnowledgeGraphStatus`, `PaperStatusResponse.knowledgeGraphStatus: KnowledgeGraphStatus | null`, `KnowledgeGraphView { url; expiresAt }`, `getKnowledgeGraphView(paperId)`, `knowledgeGraphDisabledReason(status)`, `statusRefetchInterval(data)`, `usePaperStatusQuery(paperId)`.

- [ ] **Step 1: 실패하는 테스트**

`fe/src/routes/study/knowledgeGraphStatus.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import { knowledgeGraphDisabledReason, statusRefetchInterval } from './knowledgeGraphStatus';
import { TRANSLATION_POLL_MS } from './translationStatus';

describe('knowledgeGraphDisabledReason', () => {
  it('상태별 툴팁 문구를 아트보드 그대로 돌려준다', () => {
    expect(knowledgeGraphDisabledReason('PENDING')).toBe('지식 그래프를 준비하고 있습니다');
    expect(knowledgeGraphDisabledReason('FAILED')).toBe('지식 그래프를 준비하지 못했습니다');
  });

  it('READY면 비활성 사유가 없다', () => {
    expect(knowledgeGraphDisabledReason('READY')).toBe('');
  });

  it('null(Document 연결 전)·undefined는 실패 문구다', () => {
    expect(knowledgeGraphDisabledReason(null)).toBe('지식 그래프를 준비하지 못했습니다');
    expect(knowledgeGraphDisabledReason(undefined)).toBe('지식 그래프를 준비하지 못했습니다');
  });
});

describe('statusRefetchInterval', () => {
  it('번역·지식 그래프 중 하나라도 PENDING이면 5초 폴링', () => {
    expect(statusRefetchInterval({ translationStatus: 'PENDING', knowledgeGraphStatus: 'READY' })).toBe(TRANSLATION_POLL_MS);
    expect(statusRefetchInterval({ translationStatus: 'READY', knowledgeGraphStatus: 'PENDING' })).toBe(TRANSLATION_POLL_MS);
    expect(statusRefetchInterval({ translationStatus: 'NOT_APPLICABLE', knowledgeGraphStatus: 'PENDING' })).toBe(TRANSLATION_POLL_MS);
  });

  it('둘 다 종결이거나 null이면 폴링하지 않는다', () => {
    expect(statusRefetchInterval({ translationStatus: 'READY', knowledgeGraphStatus: 'READY' })).toBe(false);
    expect(statusRefetchInterval({ translationStatus: 'FAILED', knowledgeGraphStatus: 'FAILED' })).toBe(false);
    expect(statusRefetchInterval({ translationStatus: 'NOT_APPLICABLE', knowledgeGraphStatus: null })).toBe(false);
    expect(statusRefetchInterval(undefined)).toBe(false);
  });
});
```

`fe/src/api/papers.test.ts`의 import에 `getKnowledgeGraphView`를 추가하고 `getDownloadUrl` 테스트 뒤에:

```ts
  it('getKnowledgeGraphView: GET /knowledge-graph → {url, expiresAt}', async () => {
    mockFetch({ body: { url: 'https://s3/viz.html', expiresAt: '2026-09-17T00:10:00Z' } });
    const res = await getKnowledgeGraphView('p1');
    expect(globalThis.fetch).toHaveBeenCalledWith('/api/papers/p1/knowledge-graph', expect.objectContaining({}));
    expect(res.url).toBe('https://s3/viz.html');
  });
```

- [ ] **Step 2: 실패 확인**

```bash
cd fe && npx vitest run src/routes/study/knowledgeGraphStatus.test.ts src/api/papers.test.ts
```

Expected: 모듈 없음·export 없음으로 실패.

- [ ] **Step 3: 타입·클라이언트**

`fe/src/api/types.ts`의 `TranslationStatus` 선언 뒤에 추가:

```ts
// 지식 그래프(viz.html) 준비 상태. 컴파일이 만드는 산출물이라 파싱 status와 별개다. Document 연결 전에는 null.
export type KnowledgeGraphStatus = 'PENDING' | 'READY' | 'FAILED';
```

`PaperStatusResponse`에 필드 추가:

```ts
export interface PaperStatusResponse {
  paperId: string;
  status: PaperStatus;
  translationStatus: TranslationStatus;
  knowledgeGraphStatus: KnowledgeGraphStatus | null;
  updatedAt: string;
}
```

`PaperContentAssetDto` 선언 뒤에 추가:

```ts
// GET /api/papers/{paperId}/knowledge-graph. viz.html을 여는 presigned GET URL — iframe src로 쓴다.
export interface KnowledgeGraphView { url: string; expiresAt: string; }
```

`fe/src/api/papers.ts`: import에 `type KnowledgeGraphView` 추가, `getDownloadUrl` 뒤에:

```ts
// 지식 그래프 viz.html을 여는 presigned GET URL 발급 (계약 0.7.0). READY가 아니면 409 KNOWLEDGE_GRAPH_NOT_READY.
export async function getKnowledgeGraphView(paperId: string): Promise<KnowledgeGraphView> {
  const res = await authFetch(`/api/papers/${paperId}/knowledge-graph`);
  if (!res.ok) throw await apiError(res);
  return res.json(); // { url, expiresAt }
}
```

- [ ] **Step 4: 헬퍼·훅**

`fe/src/routes/study/knowledgeGraphStatus.ts`:

```ts
// 지식 그래프 버튼의 비활성 사유와 상단 바 공용 폴링 판정. 툴팁은 아트보드 문구 그대로.
import type { KnowledgeGraphStatus, PaperStatusResponse } from '../../api/types';
import { TRANSLATION_POLL_MS, translationRefetchInterval } from './translationStatus';

const REASON: Record<KnowledgeGraphStatus, string> = {
  PENDING: '지식 그래프를 준비하고 있습니다',
  READY: '',
  FAILED: '지식 그래프를 준비하지 못했습니다',
};

/** READY면 빈 문자열. null(Document 연결 전)·undefined는 산출물이 없는 것과 같이 실패 문구다. */
export function knowledgeGraphDisabledReason(status: KnowledgeGraphStatus | null | undefined): string {
  return status ? REASON[status] : REASON.FAILED;
}

type StatusSignals = Pick<PaperStatusResponse, 'translationStatus' | 'knowledgeGraphStatus'>;

/** 번역·지식 그래프 중 하나라도 PENDING이면 5초 폴링. null은 준비 중이 아니므로 폴링하지 않는다. */
export function statusRefetchInterval(data: StatusSignals | undefined): number | false {
  if (!data) return false;
  if (translationRefetchInterval(data.translationStatus) !== false) return TRANSLATION_POLL_MS;
  return data.knowledgeGraphStatus === 'PENDING' ? TRANSLATION_POLL_MS : false;
}
```

`fe/src/routes/study/usePaperStatusQuery.ts`:

```ts
// 학습·지식 그래프 화면이 공유하는 상태 조회. 키가 같아 캐시를 공유하고 폴링 옵션은 여기 한 곳에만 둔다.
import { useQuery } from '@tanstack/react-query';
import { getStatus } from '../../api/papers';
import { statusRefetchInterval } from './knowledgeGraphStatus';

export function usePaperStatusQuery(paperId: string | undefined) {
  return useQuery({
    queryKey: ['paper-status', paperId],
    queryFn: () => getStatus(paperId as string),
    enabled: !!paperId,
    // 번역·지식 그래프가 준비 중일 때만 폴링한다. 서재 폴링과 달리 파싱 상태는 이미 COMPLETED다.
    refetchInterval: (query) => statusRefetchInterval(query.state.data),
  });
}
```

- [ ] **Step 5: 통과 확인**

```bash
npx vitest run src/routes/study/knowledgeGraphStatus.test.ts src/api/papers.test.ts src/routes/study/translationStatus.test.ts && npm run typecheck
```

Expected: PASS. typecheck는 `StudyPage.test.tsx`의 `statusResponse()`에 `knowledgeGraphStatus`가 없어 실패할 수 있다 — Task 12 Step 1에서 고친다. 그 오류 하나만 남는지 확인하고 넘어간다.

- [ ] **Step 6: Commit (사용자 승인 후)**

```bash
git add fe/src/api/types.ts fe/src/api/papers.ts fe/src/api/papers.test.ts fe/src/routes/study/knowledgeGraphStatus.ts fe/src/routes/study/knowledgeGraphStatus.test.ts fe/src/routes/study/usePaperStatusQuery.ts
git commit -m "[YMC-394] feat(fe): 지식 그래프 상태 타입·URL 발급 클라이언트·공용 상태 쿼리 추가"
```

---

### Task 12: StudyTopBar 추출과 학습 화면 연결

**Files:**
- Create: `fe/src/routes/study/StudyTopBar.tsx`
- Modify: `fe/src/routes/StudyPage.tsx`
- Test: `fe/src/routes/study/StudyTopBar.test.tsx`, `fe/src/routes/StudyPage.test.tsx`

**Interfaces:**
- Consumes: `knowledgeGraphDisabledReason`, `usePaperStatusQuery`.
- Produces: `StudyTopBar({ paperId, title, current: 'content' | 'graph', knowledgeGraphStatus, rightSlot? })`. `본문` 링크 `/papers/{paperId}`, `지식 그래프` 링크 `/papers/{paperId}/graph`.

- [ ] **Step 1: 실패하는 테스트**

`fe/src/routes/study/StudyTopBar.test.tsx`:

```tsx
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { StudyTopBar, type StudyTopBarProps } from './StudyTopBar';

vi.mock('../../account/AccountMenu', () => ({ AccountMenu: () => <div data-testid="account-menu" /> }));

afterEach(cleanup);

function renderBar(props: Partial<StudyTopBarProps> = {}) {
  render(
    <MemoryRouter initialEntries={['/papers/p1']}>
      <Routes>
        <Route
          path="/papers/:paperId/*"
          element={<StudyTopBar paperId="p1" title="Attention" current="content" knowledgeGraphStatus="READY" {...props} />}
        />
        <Route path="/papers/p1/graph" element={<div>GRAPH-ROUTE</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

function contentLink(): HTMLAnchorElement {
  return screen.getByRole('link', { name: '본문' }) as HTMLAnchorElement;
}
function graphLink(): HTMLAnchorElement {
  return screen.getByRole('link', { name: '지식 그래프' }) as HTMLAnchorElement;
}

describe('StudyTopBar — 본문 | 지식 그래프 쌍', () => {
  it('현재 화면이 aria-current로 드러나고 링크 경로가 맞다', () => {
    renderBar();
    expect(contentLink().getAttribute('aria-current')).toBe('page');
    expect(graphLink().getAttribute('aria-current')).toBeNull();
    expect(contentLink().getAttribute('href')).toBe('/papers/p1');
    expect(graphLink().getAttribute('href')).toBe('/papers/p1/graph');
    expect(screen.getByText('Attention')).toBeTruthy();
    expect(screen.getByTestId('account-menu')).toBeTruthy();
  });

  it('graph가 현재면 지식 그래프가 눌린다', () => {
    renderBar({ current: 'graph' });
    expect(graphLink().getAttribute('aria-current')).toBe('page');
    expect(contentLink().getAttribute('aria-current')).toBeNull();
  });

  it('READY가 아니면 지식 그래프가 비활성이고 툴팁이 사유다', () => {
    renderBar({ knowledgeGraphStatus: 'PENDING' });
    expect(graphLink().getAttribute('aria-disabled')).toBe('true');
    expect(graphLink().title).toBe('지식 그래프를 준비하고 있습니다');
    fireEvent.click(graphLink());
    expect(screen.queryByText('GRAPH-ROUTE')).toBeNull();
  });

  it('FAILED·null도 비활성이다', () => {
    renderBar({ knowledgeGraphStatus: 'FAILED' });
    expect(graphLink().title).toBe('지식 그래프를 준비하지 못했습니다');
    cleanup();
    renderBar({ knowledgeGraphStatus: null });
    expect(graphLink().getAttribute('aria-disabled')).toBe('true');
  });

  it('READY면 클릭으로 그래프 화면에 간다', () => {
    renderBar();
    expect(graphLink().getAttribute('aria-disabled')).toBeNull();
    expect(graphLink().title).toBe('지식 그래프');
    fireEvent.click(graphLink());
    expect(screen.getByText('GRAPH-ROUTE')).toBeTruthy();
  });

  it('rightSlot이 있으면 계정 메뉴 왼쪽에 그린다', () => {
    renderBar({ rightSlot: <button type="button">번역</button> });
    expect(screen.getByRole('button', { name: '번역' })).toBeTruthy();
  });
});
```

`fe/src/routes/StudyPage.test.tsx`:
- import에 `type KnowledgeGraphStatus` 추가.
- `statusResponse` 헬퍼를 다음으로 바꾼다:

```tsx
function statusResponse(translationStatus: TranslationStatus = 'READY', knowledgeGraphStatus: KnowledgeGraphStatus | null = 'READY') {
  return { paperId: 'p1', status: 'COMPLETED' as const, translationStatus, knowledgeGraphStatus, updatedAt: '2026-09-09T00:00:00Z' };
}
```

- 파일 끝에 describe 추가:

```tsx
describe('StudyPage — 상단 바 본문 | 지식 그래프', () => {
  function graphLink(): HTMLAnchorElement {
    return screen.getByRole('link', { name: '지식 그래프' }) as HTMLAnchorElement;
  }

  it('본문이 현재 화면으로 눌려 있고 READY면 지식 그래프가 활성이다', async () => {
    vi.mocked(fetchPaperContent).mockResolvedValue(contentResponse(true));
    renderStudy();
    await waitFor(() => expect(modeButton()).toBeTruthy());
    expect(screen.getByRole('link', { name: '본문' }).getAttribute('aria-current')).toBe('page');
    expect(graphLink().getAttribute('href')).toBe('/papers/p1/graph');
    expect(graphLink().getAttribute('aria-disabled')).toBeNull();
    // 상단 바 추출 뒤에도 제목·야간 모드·번역 버튼이 그대로다
    expect(screen.getByText('제목')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Night Study Mode 켜기' })).toBeTruthy();
    expect(modeButton().disabled).toBe(false);
  });

  it('지식 그래프 PENDING이면 비활성이고 번역 READY라도 폴링해 READY가 되면 켜진다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      vi.mocked(getStatus)
        .mockResolvedValueOnce(statusResponse('READY', 'PENDING'))
        .mockResolvedValue(statusResponse('READY', 'READY'));
      vi.mocked(fetchPaperContent).mockResolvedValue(contentResponse(true));
      renderStudy();
      await waitFor(() => expect(modeButton()).toBeTruthy());
      expect(graphLink().getAttribute('aria-disabled')).toBe('true');
      expect(graphLink().title).toBe('지식 그래프를 준비하고 있습니다');

      await vi.advanceTimersByTimeAsync(5000);

      await waitFor(() => expect(graphLink().getAttribute('aria-disabled')).toBeNull());
      expect(getStatus).toHaveBeenCalledTimes(2);
      // 번역은 처음부터 READY였으니 본문을 다시 받지 않는다
      expect(fetchPaperContent).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it('지식 그래프 FAILED면 실패 툴팁이다', async () => {
    vi.mocked(getStatus).mockResolvedValue(statusResponse('READY', 'FAILED'));
    vi.mocked(fetchPaperContent).mockResolvedValue(contentResponse(true));
    renderStudy();
    await waitFor(() => expect(modeButton()).toBeTruthy());
    expect(graphLink().title).toBe('지식 그래프를 준비하지 못했습니다');
  });
});
```

- [ ] **Step 2: 실패 확인**

```bash
npx vitest run src/routes/study/StudyTopBar.test.tsx src/routes/StudyPage.test.tsx
```

Expected: `StudyTopBar` 모듈 없음, StudyPage에 링크 없음으로 실패.

- [ ] **Step 3: StudyTopBar 구현**

`fe/src/routes/study/StudyTopBar.tsx`:

```tsx
// 학습·지식 그래프 화면이 공유하는 R1 상단 바. 아트보드 Paper Knowledge Graph Page의 `본문 | 지식 그래프` 쌍을
// 옮겼고, 학습 화면 전용 컨트롤(번역·야간 모드)은 rightSlot으로 받는다.
import { type MouseEvent, type ReactNode } from 'react';
import { Link } from 'react-router';
import { ArrowLeft, BookOpen } from '@phosphor-icons/react';
import { PaperStackMark } from '../../design/components/PaperStackMark';
import { AccountMenu } from '../../account/AccountMenu';
import type { KnowledgeGraphStatus } from '../../api/types';
import { knowledgeGraphDisabledReason } from './knowledgeGraphStatus';

export type StudyView = 'content' | 'graph';

export interface StudyTopBarProps {
  paperId: string;
  title: string;
  current: StudyView;
  knowledgeGraphStatus: KnowledgeGraphStatus | null | undefined;
  /** 학습 화면 전용 컨트롤. 계정 메뉴 왼쪽에 놓인다. */
  rightSlot?: ReactNode;
}

const DIVIDER = { width: '1px', height: '18px', background: 'rgba(255,253,247,0.18)', flexShrink: 0 } as const;

/** 아트보드 .pt-topbar-btn. 현재 화면은 밝은 배경, 비활성은 흐리게. */
function pairLinkStyle(isCurrent: boolean, disabled: boolean) {
  return {
    height: '32px',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    gap: '7px',
    flexShrink: 0,
    background: isCurrent ? 'var(--color-on-dark)' : 'rgba(255,253,247,0.08)',
    border: `1px solid ${isCurrent ? 'var(--color-on-dark)' : 'rgba(255,253,247,0.22)'}`,
    borderRadius: '8px',
    color: isCurrent ? 'var(--color-bg-walnut)' : 'var(--color-on-dark)',
    padding: '0 10px 0 8px',
    fontFamily: 'var(--font-sans)',
    fontSize: '13px',
    fontWeight: 600,
    textDecoration: 'none',
    whiteSpace: 'nowrap',
    opacity: disabled ? 0.42 : 1,
    cursor: disabled ? 'not-allowed' : 'pointer',
  } as const;
}

/** 아트보드의 지식 그래프 아이콘(노드 여섯 개와 연결선). Phosphor에 같은 모양이 없어 그대로 옮겼다. */
function KnowledgeGraphIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      aria-hidden="true"
      style={{ width: 18, height: 18, fill: 'none', stroke: 'currentColor', strokeWidth: 1.45, strokeLinecap: 'round', strokeLinejoin: 'round', flexShrink: 0 }}
    >
      <path d="M5.2 6.6 10.7 4M13.2 4.4l5 3.1M5.5 8.7l2.2 6M10 15.8l6.1 1M18.4 9.5l-.9 5M9 14.6l2.5-8.4M12.9 6.2l3.9 1.9M9.7 16.8l2.3 2.1M16.1 17.8l-2.2 1.4" />
      <circle cx="4.5" cy="7.6" r="2" />
      <circle cx="12" cy="3.8" r="1.8" />
      <circle cx="19" cy="8.3" r="2" />
      <circle cx="8.5" cy="16.2" r="2.1" />
      <circle cx="17" cy="17" r="2" />
      <circle cx="12.8" cy="20" r="1.4" />
    </svg>
  );
}

export function StudyTopBar({ paperId, title, current, knowledgeGraphStatus, rightSlot }: StudyTopBarProps) {
  const graphDisabled = knowledgeGraphStatus !== 'READY';
  const graphReason = knowledgeGraphDisabledReason(knowledgeGraphStatus);

  function blockIfDisabled(e: MouseEvent<HTMLAnchorElement>) {
    if (graphDisabled) e.preventDefault();
  }

  return (
    <div
      style={{
        height: '64px',
        flexShrink: 0,
        background: 'var(--color-bg-walnut)',
        color: 'var(--color-on-dark)',
        display: 'flex',
        alignItems: 'stretch',
      }}
    >
      <div style={{ flex: 1, minWidth: 0, display: 'flex', alignItems: 'center', padding: '0 20px 0 16px', gap: '16px' }}>
        <Link to="/" style={{ display: 'flex', alignItems: 'center', gap: '10px', flexShrink: 0, textDecoration: 'none', color: 'inherit' }}>
          <PaperStackMark size={22} color="var(--color-on-dark)" style={{ flexShrink: 0 }} />
          <span style={{ fontFamily: 'var(--font-serif)', fontWeight: 600, fontSize: '18px', letterSpacing: '-0.005em', whiteSpace: 'nowrap' }}>
            Paper Teacher
          </span>
        </Link>
        <div style={DIVIDER} />
        <Link
          to="/library"
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: '6px',
            background: 'transparent',
            border: 'none',
            padding: '7px 10px',
            borderRadius: '9999px',
            color: 'var(--color-on-dark)',
            fontFamily: 'var(--font-sans)',
            fontSize: '13px',
            fontWeight: 600,
            whiteSpace: 'nowrap',
            flexShrink: 0,
            textDecoration: 'none',
          }}
        >
          <ArrowLeft size={14} />
          서재로
        </Link>
        <div style={DIVIDER} />
        <div
          style={{
            fontFamily: 'var(--font-serif)',
            fontSize: '15px',
            fontWeight: 600,
            color: 'var(--color-on-dark)',
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            flex: 1,
            minWidth: 0,
            opacity: 0.92,
          }}
        >
          {title}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '6px', flexShrink: 0 }}>
          <Link
            to={`/papers/${paperId}`}
            aria-current={current === 'content' ? 'page' : undefined}
            title="본문"
            style={pairLinkStyle(current === 'content', false)}
          >
            <BookOpen size={17} />
            <span>본문</span>
          </Link>
          <Link
            to={`/papers/${paperId}/graph`}
            aria-current={current === 'graph' ? 'page' : undefined}
            aria-disabled={graphDisabled ? 'true' : undefined}
            title={graphDisabled ? graphReason : '지식 그래프'}
            onClick={blockIfDisabled}
            style={pairLinkStyle(current === 'graph', graphDisabled)}
          >
            <KnowledgeGraphIcon />
            <span>지식 그래프</span>
          </Link>
        </div>
      </div>
      <div
        style={{
          flexShrink: 0,
          display: 'flex',
          alignItems: 'center',
          gap: '14px',
          padding: '0 20px',
          borderLeft: '1px solid rgba(255,253,247,0.14)',
        }}
      >
        {rightSlot}
        {rightSlot ? <div style={DIVIDER} /> : null}
        <AccountMenu />
      </div>
    </div>
  );
}
```

- [ ] **Step 4: StudyPage 연결**

`fe/src/routes/StudyPage.tsx`:
- import 정리: `useQuery`는 그대로 쓰되(본문 쿼리) `getStatus`, `ArrowLeft`, `PaperStackMark`, `AccountMenu`, `translationRefetchInterval` import를 지우고 다음을 추가:

```tsx
import { StudyTopBar } from './study/StudyTopBar';
import { usePaperStatusQuery } from './study/usePaperStatusQuery';
import type { KnowledgeGraphStatus } from '../api/types';
```

(`translationDisabledReason`은 계속 쓴다. `Link`는 상단 바에서만 쓰였으므로 다른 사용처가 없으면 import에서 지운다. `ApiError`, `Navigate`, `useParams`는 유지.)

- `StudyPage()`의 `statusQuery` 선언을 `const statusQuery = usePaperStatusQuery(paperId);`로 바꾼다.
- 마지막 return을 다음으로 바꾼다:

```tsx
  return (
    <StudyPageContent
      paperId={paperId}
      translationStatus={statusQuery.data.translationStatus}
      knowledgeGraphStatus={statusQuery.data.knowledgeGraphStatus}
    />
  );
```

- `StudyPageContent` 시그니처:

```tsx
function StudyPageContent({
  paperId, translationStatus, knowledgeGraphStatus,
}: { paperId: string; translationStatus: TranslationStatus; knowledgeGraphStatus: KnowledgeGraphStatus | null }) {
```

- `{/* R1 Global top bar */}` 주석부터 그 `<div>` 블록 끝(`<AccountMenu />` 뒤 두 개의 `</div>`)까지를 다음으로 교체:

```tsx
      {/* R1 Global top bar */}
      <StudyTopBar
        paperId={paperId}
        title={titleText}
        current="content"
        knowledgeGraphStatus={knowledgeGraphStatus}
        rightSlot={
          <>
            <TranslationModeButton
              mode={effectiveMode}
              disabled={!translationEnabled}
              disabledReason={translationDisabledReason(translationStatus, hasTranslation)}
              onCycle={handleCycleTranslation}
            />
            <IconButton
              icon={nightMode ? 'sun' : 'moon'}
              label={nightMode ? '주간 모드로 전환' : 'Night Study Mode 켜기'}
              size={32}
              onClick={() => setNightMode((v) => !v)}
              style={{ color: 'var(--color-on-dark)' }}
            />
          </>
        }
      />
```

- [ ] **Step 5: 통과 확인**

```bash
npx vitest run src/routes/study/StudyTopBar.test.tsx src/routes/StudyPage.test.tsx src/routes/study && npm run typecheck
```

Expected: PASS, typecheck 오류 0. 사용하지 않는 import가 남으면 typecheck/lint가 잡는다 — 지운다.

- [ ] **Step 6: Commit (사용자 승인 후)**

```bash
git add fe/src/routes/study/StudyTopBar.tsx fe/src/routes/study/StudyTopBar.test.tsx fe/src/routes/StudyPage.tsx fe/src/routes/StudyPage.test.tsx
git commit -m "[YMC-394] feat(fe): 학습 화면 상단 바를 StudyTopBar로 분리하고 본문·지식 그래프 쌍 추가"
```

---

### Task 13: KnowledgeGraphPage와 라우트

**Files:**
- Create: `fe/src/routes/KnowledgeGraphPage.tsx`
- Modify: `fe/src/main.tsx`
- Test: `fe/src/routes/KnowledgeGraphPage.test.tsx`

**Interfaces:**
- Consumes: `usePaperStatusQuery`, `getKnowledgeGraphView`, `getPaperContent`(제목용, `['paper-content', paperId]` 캐시 공유), `StudyTopBar`, `knowledgeGraphDisabledReason`.
- Produces: 라우트 `/papers/:paperId/graph`.

- [ ] **Step 1: 실패하는 테스트**

`fe/src/routes/KnowledgeGraphPage.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import KnowledgeGraphPage from './KnowledgeGraphPage';
import { getStatus, getKnowledgeGraphView, fetchPaperContent } from '../api/papers';
import { ApiError, type KnowledgeGraphStatus, type PaperStatus } from '../api/types';

vi.mock('../api/papers', () => ({ getStatus: vi.fn(), getKnowledgeGraphView: vi.fn(), fetchPaperContent: vi.fn() }));
vi.mock('../account/AccountMenu', () => ({ AccountMenu: () => <div /> }));

function statusResponse(knowledgeGraphStatus: KnowledgeGraphStatus | null = 'READY', status: PaperStatus = 'COMPLETED') {
  return { paperId: 'p1', status, translationStatus: 'READY' as const, knowledgeGraphStatus, updatedAt: '2026-09-17T00:00:00Z' };
}

function renderGraph() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/papers/p1/graph']}>
        <Routes>
          <Route path="/papers/:paperId/graph" element={<KnowledgeGraphPage />} />
          <Route path="/papers/:paperId" element={<div>STUDY-ROUTE</div>} />
          <Route path="/library" element={<div>LIBRARY-ROUTE</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function iframe(): HTMLIFrameElement | null {
  return document.querySelector('iframe[title="지식 그래프"]');
}

beforeEach(() => {
  vi.mocked(getStatus).mockResolvedValue(statusResponse());
  vi.mocked(fetchPaperContent).mockResolvedValue({
    paperId: 'p1', title: '제목', sourceLanguage: 'en', translationStatus: 'READY', schemaVersion: 1, blocks: [], assets: {},
  });
  vi.mocked(getKnowledgeGraphView).mockResolvedValue({ url: 'https://s3.example/viz.html?sig=1', expiresAt: '2026-09-17T00:10:00Z' });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('KnowledgeGraphPage', () => {
  it('READY면 URL을 받아 iframe src로 넣고 sandbox를 건다', async () => {
    renderGraph();
    await waitFor(() => expect(iframe()).toBeTruthy());
    expect(iframe()!.getAttribute('src')).toBe('https://s3.example/viz.html?sig=1');
    expect(iframe()!.getAttribute('sandbox')).toBe('allow-scripts allow-same-origin');
    expect(getKnowledgeGraphView).toHaveBeenCalledWith('p1');
    // 상단 바: 지식 그래프가 현재, 번역 버튼 없음
    expect(screen.getByRole('link', { name: '지식 그래프' }).getAttribute('aria-current')).toBe('page');
    expect(screen.queryByRole('button', { name: /번역/ })).toBeNull();
    expect(screen.getByText('제목')).toBeTruthy();
  });

  it('PENDING이면 URL을 요청하지 않고 준비 중 문구와 본문으로 링크를 보여준다', async () => {
    vi.mocked(getStatus).mockResolvedValue(statusResponse('PENDING'));
    renderGraph();
    await waitFor(() => expect(screen.getByText('지식 그래프를 준비하고 있습니다')).toBeTruthy());
    expect(getKnowledgeGraphView).not.toHaveBeenCalled();
    expect(iframe()).toBeNull();
    const back = screen.getByRole('link', { name: '본문으로' }) as HTMLAnchorElement;
    expect(back.getAttribute('href')).toBe('/papers/p1');
    fireEvent.click(back);
    expect(screen.getByText('STUDY-ROUTE')).toBeTruthy();
  });

  it('FAILED면 실패 문구다', async () => {
    vi.mocked(getStatus).mockResolvedValue(statusResponse('FAILED'));
    renderGraph();
    await waitFor(() => expect(screen.getByText('지식 그래프를 준비하지 못했습니다')).toBeTruthy());
    expect(getKnowledgeGraphView).not.toHaveBeenCalled();
  });

  it('URL 발급 실패면 문구와 다시 시도 버튼이고 누르면 재요청한다', async () => {
    vi.mocked(getKnowledgeGraphView)
      .mockRejectedValueOnce(new ApiError('conflict', 'KNOWLEDGE_GRAPH_NOT_READY', 409))
      .mockResolvedValue({ url: 'https://s3.example/viz.html?sig=2', expiresAt: '2026-09-17T00:10:00Z' });
    renderGraph();
    await waitFor(() => expect(screen.getByText('지식 그래프를 불러오지 못했습니다')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(iframe()).toBeTruthy());
    expect(iframe()!.getAttribute('src')).toBe('https://s3.example/viz.html?sig=2');
    expect(getKnowledgeGraphView).toHaveBeenCalledTimes(2);
  });

  it('파싱이 COMPLETED가 아니면 서재로 돌려보낸다', async () => {
    vi.mocked(getStatus).mockResolvedValue(statusResponse('PENDING', 'PROCESSING'));
    renderGraph();
    await waitFor(() => expect(screen.getByText('LIBRARY-ROUTE')).toBeTruthy());
  });

  it('없는 논문(404)은 서재로 돌려보낸다', async () => {
    vi.mocked(getStatus).mockRejectedValue(new ApiError('not found', 'PAPER_NOT_FOUND', 404));
    renderGraph();
    await waitFor(() => expect(screen.getByText('LIBRARY-ROUTE')).toBeTruthy());
  });
});
```

- [ ] **Step 2: 실패 확인**

```bash
npx vitest run src/routes/KnowledgeGraphPage.test.tsx
```

Expected: 모듈 없음으로 실패.

- [ ] **Step 3: 페이지 구현**

`fe/src/routes/KnowledgeGraphPage.tsx`:

```tsx
// 지식 그래프 화면: AI 컴파일 워커가 만든 단독 HTML(viz.html)을 presigned URL로 iframe에 그대로 띄운다.
// 그래프·섹션 리더·번역 토글은 viz.html 자체 기능이라 여기서는 상단 바와 준비 상태만 다룬다.
// 이식: project-docs/design/v2/Paper Knowledge Graph Page.dc.html — R1 top bar / R2 iframe.
import type { ReactNode } from 'react';
import { Link, Navigate, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { getKnowledgeGraphView } from '../api/papers';
import { ApiError, type KnowledgeGraphStatus } from '../api/types';
import { getPaperContent } from '../markdown/paperContent';
import { StudyTopBar } from './study/StudyTopBar';
import { usePaperStatusQuery } from './study/usePaperStatusQuery';
import { knowledgeGraphDisabledReason } from './study/knowledgeGraphStatus';

export default function KnowledgeGraphPage() {
  const { paperId } = useParams<{ paperId: string }>();
  const statusQuery = usePaperStatusQuery(paperId);

  if (!paperId) {
    return <Navigate to="/library" replace state={{ toast: '잘못된 접근입니다' }} />;
  }
  if (statusQuery.isPending) {
    return <Centered>불러오는 중…</Centered>;
  }
  if (statusQuery.isError) {
    const gone = statusQuery.error instanceof ApiError && statusQuery.error.httpStatus === 404;
    return <Navigate to="/library" replace state={{ toast: gone ? '삭제되었거나 없는 논문입니다' : '논문 상태를 불러오지 못했습니다' }} />;
  }
  // 학습 화면과 같은 규칙 — 파싱이 끝나지 않은 논문은 서재로.
  if (statusQuery.data.status !== 'COMPLETED') {
    const toast =
      statusQuery.data.status === 'FAILED' || statusQuery.data.status === 'EXPIRED'
        ? '분석에 실패한 논문입니다'
        : '아직 분석 중인 논문입니다';
    return <Navigate to="/library" replace state={{ toast }} />;
  }

  return <KnowledgeGraphContent paperId={paperId} knowledgeGraphStatus={statusQuery.data.knowledgeGraphStatus} />;
}

function KnowledgeGraphContent({ paperId, knowledgeGraphStatus }: { paperId: string; knowledgeGraphStatus: KnowledgeGraphStatus | null }) {
  // 제목만 쓰지만 학습 화면과 같은 키라 오가는 동안 캐시를 공유한다.
  const contentQuery = useQuery({
    queryKey: ['paper-content', paperId],
    queryFn: () => getPaperContent(paperId),
  });
  const ready = knowledgeGraphStatus === 'READY';
  // 진입할 때마다 새 URL을 받는다. 만료(10분)는 로드가 끝난 문서에 영향이 없다.
  const viewQuery = useQuery({
    queryKey: ['knowledge-graph-view', paperId],
    queryFn: () => getKnowledgeGraphView(paperId),
    enabled: ready,
    staleTime: 0,
    gcTime: 0,
    retry: false,
  });

  const titleText = contentQuery.data?.title ?? 'Paper Teacher';

  return (
    <div
      style={{
        height: '100vh',
        width: '100%',
        display: 'flex',
        flexDirection: 'column',
        background: 'var(--color-bg-canvas)',
        fontFamily: 'var(--font-sans)',
        overflow: 'hidden',
      }}
    >
      {/* R1 Global top bar — 번역·야간 모드는 viz.html이 스스로 다루므로 없다 */}
      <StudyTopBar paperId={paperId} title={titleText} current="graph" knowledgeGraphStatus={knowledgeGraphStatus} />

      {/* R2 viz.html */}
      <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', background: 'var(--color-bg-canvas)' }}>
        {!ready ? (
          <Centered>
            {knowledgeGraphDisabledReason(knowledgeGraphStatus)}{' '}
            <Link to={`/papers/${paperId}`} style={{ marginLeft: 8 }}>본문으로</Link>
          </Centered>
        ) : viewQuery.isPending ? (
          <Centered>지식 그래프를 불러오는 중…</Centered>
        ) : viewQuery.isError ? (
          <Centered>
            지식 그래프를 불러오지 못했습니다{' '}
            <button type="button" onClick={() => viewQuery.refetch()} style={{ marginLeft: 8 }}>다시 시도</button>
          </Centered>
        ) : (
          // S3 오리진 문서라 앱 오리진과 격리된다. allow-same-origin은 viz.html의 localStorage(번역 모드 기억)용이다.
          // iframe 안 로드 실패는 cross-origin이라 감지할 수 없다 — 다시 시도는 URL 발급 실패에만 붙는다.
          <iframe
            src={viewQuery.data.url}
            title="지식 그래프"
            sandbox="allow-scripts allow-same-origin"
            style={{ flex: 1, width: '100%', border: 'none', background: 'var(--color-bg-canvas)' }}
          />
        )}
      </div>
    </div>
  );
}

function Centered({ children }: { children: ReactNode }) {
  return (
    <div style={{ padding: 48, textAlign: 'center', fontFamily: 'var(--font-sans)', color: 'var(--color-text-muted)' }}>
      {children}
    </div>
  );
}
```

- [ ] **Step 4: 라우트**

`fe/src/main.tsx`: `import KnowledgeGraphPage from './routes/KnowledgeGraphPage';` 추가, `RequireAuth` children의 `{ path: '/papers/:paperId', element: <StudyPage /> },` 뒤에:

```tsx
      { path: '/papers/:paperId/graph', element: <KnowledgeGraphPage /> },
```

- [ ] **Step 5: 통과 확인**

```bash
npx vitest run src/routes/KnowledgeGraphPage.test.tsx && npm run typecheck
```

Expected: PASS.

- [ ] **Step 6: Commit (사용자 승인 후)**

```bash
git add fe/src/routes/KnowledgeGraphPage.tsx fe/src/routes/KnowledgeGraphPage.test.tsx fe/src/main.tsx
git commit -m "[YMC-394] feat(fe): 지식 그래프 화면과 /papers/:paperId/graph 라우트 추가"
```

---

### Task 14: 전체 검증과 로컬 실화면 확인

- [ ] **Step 1: FE 전체**

```bash
cd fe && npm test && npm run typecheck && npm run build
```

Expected: 모두 성공. 실패하면 여기서 고치고 해당 Task의 커밋에 `--amend`하지 말고 새 fix 커밋으로 남긴다.

- [ ] **Step 2: BE 전체**

```bash
cd ../be && ./gradlew test
```

Expected: 전부 PASS.

- [ ] **Step 3: 로컬 실화면 (선택, 로컬 compose가 떠 있을 때)**

`.claude/launch.json`의 `fe-dev`(5173)로 FE를 띄우고, 컴파일이 끝난 논문의 학습 화면에서 `지식 그래프`가 활성인지, 클릭 시 `/papers/{id}/graph`에서 iframe이 렌더되는지 확인한다. 로컬 LocalStack에는 viz.html 객체가 없을 수 있으므로 iframe 안이 S3 오류 XML이면 정상 — 발급 URL이 `knowledge-bundle/viz.html`을 가리키는지만 본다. dev 실확인은 Task 15 뒤.

- [ ] **Step 4: 사용자에게 핵심 diff 요약 보여주기**

`git log --oneline origin/main..HEAD`와 변경 파일 목록을 보여주고 PR 진행 여부를 묻는다.

---

### Task 15: app PR과 dev 확인

- [ ] **Step 1: 푸시·PR (각각 사용자 승인 후, project-docs PR 머지 뒤)**

```bash
git push -u origin YMC-394-knowledge-graph-serving
gh pr create --repo team-ymc/app --title "[YMC-394] 지식 그래프 viz.html 앱 서빙" --body-file - <<'EOF'
## 배경

컴파일 워커가 만드는 `knowledge-bundle/viz.html`을 앱에서 열 수 있게 한다. 설계는 `docs/superpowers/specs/2026-09-17-knowledge-graph-serving-design.md`.

## 변경사항

- BE: `document.knowledge_graph_key` 저장(컴파일 완료 반영 시 manifest `knowledge_bundle_viz.path`), `/status`에 `knowledgeGraphStatus`(PENDING/READY/FAILED/null), `GET /api/papers/{paperId}/knowledge-graph`(소유자·READY 검증 후 presigned GET URL, 409 `KNOWLEDGE_GRAPH_NOT_READY`).
- FE: 상단 바를 `StudyTopBar`로 분리하고 `본문 | 지식 그래프` 쌍, 새 화면 `/papers/:paperId/graph`(iframe), 번역·지식 그래프 중 하나라도 PENDING이면 5초 폴링.

**판단** — 종결된 compile_status는 바꾸지 않으므로 SQL로 COMPLETED 처리한 옛 문서는 그래프 FAILED로 남는다. manifest에 artifact가 없으면 번역 사이드카와 같이 WARN만 남기고 COMPLETED로 종결한다.

## 검증

- BE `./gradlew test` / FE `npm test`·`typecheck`·`build` 통과.
- 아직 검증하지 못한 것: dev에서 실논문 컴파일 뒤 버튼 활성 전환과 iframe 렌더(머지 후 확인).

## 의존

- project-docs PR: (Task 4에서 만든 PR 링크)
EOF
```

- [ ] **Step 2: dev 반영 뒤 확인**

1. dev DB: `alter table document add column if not exists knowledge_graph_key varchar(255);` (dev는 `ddl-auto: update`라 BE 기동이 자동으로 추가하지만 prod 절차와 맞춰 확인).
2. 새 논문 업로드 → 파싱·컴파일 완료 → 학습 화면에서 새로고침 없이 `지식 그래프` 활성 → 클릭 → viz.html 렌더(그래프·섹션 리더·번역 토글 동작).
3. 옛 문서(SQL로 COMPLETED) → `지식 그래프` 비활성, 툴팁 "지식 그래프를 준비하지 못했습니다". `/papers/{id}/graph` 직접 진입 시 같은 문구와 `본문으로`.
4. Jira YMC-394에 확인 결과 코멘트(사용자 승인 후).
