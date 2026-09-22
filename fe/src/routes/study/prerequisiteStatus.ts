// 선행지식 토글의 비활성 사유. 하이라이트 배열이 비면 Design v3 툴팁 문구를 돌려준다.
export function prerequisiteDisabledReason(highlightCount: number): string | undefined {
  return highlightCount === 0 ? '표시할 선행지식이 없습니다' : undefined;
}
