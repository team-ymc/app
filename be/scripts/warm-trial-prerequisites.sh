#!/usr/bin/env bash
# 체험 논문의 선행지식 설명을 미리 만든다 (YMC-430).
# 체험 경로는 만들어 둔 설명만 돌려주므로, trial을 켜기 전에 마스터 계정으로 한 번 돌린다.
# 로그인 경로로 만든 설명은 trial 논문이면 만료 없이 남는다.
#
# 사용:  TOKEN=<마스터 access token> ./warm-trial-prerequisites.sh https://dev.papertutor.co.kr <paperId> [<paperId> ...]
# TOKEN은 브라우저 개발자 도구의 /api 요청 Authorization 헤더에서 가져온다 (30분 만료).
set -euo pipefail

BASE="${1:?base url}"; shift
: "${TOKEN:?TOKEN env}"
command -v jq >/dev/null || { echo "jq가 필요합니다"; exit 1; }

for paper in "$@"; do
  echo "== $paper"
  ids=$(curl -sS -H "Authorization: Bearer $TOKEN" "$BASE/api/papers/$paper/content" | jq -r '.prerequisiteHighlights[].highlightId')
  total=$(echo "$ids" | grep -c . || true)
  n=0
  for h in $ids; do
    n=$((n+1))
    code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $TOKEN" \
      "$BASE/api/papers/$paper/prerequisite-highlights/$h/definition")
    echo "  [$n/$total] $h -> $code"
    # 429(사용자당 동시 생성 1개)는 잠시 쉬었다가 다시 시도한다.
    while [ "$code" = "429" ]; do
      sleep 2
      code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $TOKEN" \
        "$BASE/api/papers/$paper/prerequisite-highlights/$h/definition")
      echo "  [$n/$total] $h -> $code (재시도)"
    done
  done
done
