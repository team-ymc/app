# 인라인 채팅 selection 설계 (요청 릴레이)

2026-08-10 · YMC-312 · 상태: 승인됨

## 목표

Paper Viewer에서 선택한 구절을 채팅 질문의 컨텍스트로 AI에 전달한다.
계약은 머지됨: FE↔BE `ChatMessageStreamRequest.selection`(project-docs#30),
BE↔AI `inline-pdf-agent-run-stream.yml`(AI 제공).

**범위: 요청 릴레이만.** selection은 DB에 저장하지 않고 이력 응답에도 없다.
과거 대화를 다시 열면 질문·답변 텍스트만 남는다. 저장·이력 복원은 후속.

## 접근

FE는 회수 커밋 2개를 cherry-pick으로 복원(리뷰·테스트 완료된 코드),
BE는 릴레이 범위에 맞게 신규 최소 작성. 이전 BE 커밋(2f62501)은
저장·이력이 섞여 있어 재사용하지 않는다.

## 데이터 흐름

```
FE 선택(Range) → 블록 앵커+UTF-16 offset → POST /chat/messages { content, selection? }
BE: bean validation(형식만) → ChatStreamService → AiRunRequest(+selection)
어댑터: camelCase→snake_case → AI { selection: { start: {block_id, offset?}, end: … } }
```

## BE 변경 (신규, 파일 5개)

| 파일 | 변경 |
|---|---|
| `ChatSelectionDto` (신규) | `{start, end}` + `Anchor {blockId @NotBlank, offset @Min(0) nullable}` |
| `ChatMessageStreamRequest` | `selection` 필드 추가 (`@Valid`, nullable) |
| `ChatController` | selection을 `ChatStreamService.begin`으로 전달 |
| `AiRunRequest` | `selection` 필드 추가 |
| `AiAgentWebClientAdapter` | `StreamRequestBody`에 snake_case 중첩 레코드, null 필드 생략 |

- `ChatCommandService`·`ChatMessage`·DB 불변. selection은 요청 흐름만 통과한다.
- 의미 검증(블록 존재, atomic offset 금지, 150블록/30000자)은 AI가 판정한다.
  BE는 형식 검증만 하고, AI `SELECTION_*`/`PAPER_*` 실패는 기존 매핑 그대로
  `AI_RUN_FAILED`로 처리한다(변경 없음).

## FE 변경 (cherry-pick 복원)

**`db4de0a` — 블록 앵커 + 칩:**
- `selectionAnchors.ts`: Range → `{start:{blockId}, end:{blockId}}`
- `selectionPreview.ts`: 칩에 표시할 선택 구절 미리보기
- `TutorPanel.tsx`: 질문 앞 인용문 제거, 입력창 위 칩 표시(X로 해제)
- `chatStream.ts`/`chatState.ts`: body에 `selection`, 상태 관리
- `useTextSelection.ts`/`SelectionLayer.tsx`/`StudyPage.tsx`: 배관

**`c574fd1` — 문자 offset:**
- `rehypeSourcePos.ts`: 렌더 시 원문 위치 data 속성 주입
- `sourceOffset.ts`: 선택 시 UTF-16 offset 계산, 검증 실패 시 offset 생략
- `paperContent.ts`: `PaperBlock.sourceText`·`sourceOffsetShift`
- 수식(KaTeX)·surrogate pair·헤딩 shift 처리, 테스트 87개 포함
- 의존성: `unist-util-visit`, `@types/hast`

cherry-pick 후: 충돌 확인(베이스 동일해 거의 없음), 전송 형태를 머지된
계약과 대조(fe 테스트).

## 오류 처리

| 상황 | 처리 |
|---|---|
| FE offset 계산 실패 | offset 생략, 블록 앵커만 — 조용한 폴백 |
| FE 앵커 실패 | selection 생략, 논문 전체 질문 — 조용한 폴백 |
| BE 형식 위반 | bean validation 400 |
| AI `SELECTION_*`/`PAPER_*` | 기존 `AI_RUN_FAILED` 매핑 (BE 변경 없음) |

## 테스트·검증

- BE: 어댑터 selection 직렬화(snake_case·null 생략) 단위, 릴레이 통합 1개
- FE: cherry-pick에 포함된 기존 테스트 전체 (`fe/`에서 `npm test`)
- 수동: dev에서 선택→질문→선택 부분 기반 답변 확인

## 배포 순서

1. FE cherry-pick → 테스트
2. BE 신규 커밋 → 테스트
3. PR(app#36 브랜치 이어서) → 리뷰 → dev 배포 (BE 이미지 + FE S3 sync)

BE가 selection을 무시하던 구버전이어도 FE 요청은 실패하지 않도록,
배포는 BE 먼저 반영을 기본 순서로 한다.
