# app — 서비스 코드 (be + fe 모노레포)

이 repo는 be(Spring Boot)·fe를 함께 담는다. 스택별 세부 규칙은 각 하위 CLAUDE.md에 있다:
- 백엔드: [be/CLAUDE.md](be/CLAUDE.md)
- 프론트엔드: fe/CLAUDE.md (있는 경우)

아래는 be·fe 공통으로 지킬 규칙이다.

## project-docs 참조

- 요구사항·설계·계약의 SSOT는 `project-docs/`다.
  - 요구사항: `project-docs/features/FT-XXX-*.md`
  - API·이벤트 계약: `project-docs/contracts/` (제일 먼저 `contracts/README.md`)
  - 아키텍처 결정(제품/교차 관심사): `project-docs/decisions/ADR-XXX-*.md`
- 계약(`openapi.yaml`, `asyncapi.yaml`, `schema/*.json`)이 필요하면 반드시 `project-docs/contracts/`에서 읽는다.

## 구현 스펙은 OpenSpec (`app/openspec/`)

- 구현 단위 변경은 OpenSpec change(proposal/tasks)로 관리한다. be·fe 한 repo이므로 openspec도 하나로 두고, change 안에서 be/fe를 구분한다.
- OpenSpec은 **"이번에 어떻게 구현하나"만** 담는다.
- change proposal 머리에 출처 2줄을 명시한다:

  ```
  Source: project-docs/features/FT-XXX-....md / Jira: YMC-XXX
  Decision: project-docs/decisions/ADR-XXX-....md   # 관련 결정이 있을 때
  ```

## 라이브러리 문서는 context7으로 확인

- Spring Boot·JPA·React 등 외부 라이브러리의 API·설정·마이그레이션을 다룰 때는 기억에 의존하지 말고, context7으로 **해당 버전의 공식 문서를 먼저 조회한 뒤** 코드를 쓴다. 모델의 학습 시점이 지난 API는 틀리거나 없는 메서드를 지어낼 수 있다.
- context7은 이 repo의 `.mcp.json`에 등록돼 있다. `app/`에서 Claude Code를 처음 띄울 때 서버 승인 프롬프트가 한 번 뜨며, 승인하면 이후 자동으로 붙는다.