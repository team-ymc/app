// 인라인 이름 변경은 확장자를 고정 표시하고 이름만 편집한다. .pdf가 아닌 이름은 억지로 바꾸지 않는다.
export function splitPdfName(filename: string): { stem: string; ext: string } {
  const m = /^(.*)(\.pdf)$/i.exec(filename);
  return m ? { stem: m[1], ext: m[2] } : { stem: filename, ext: '' };
}

export function joinPdfName(stem: string, ext: string): string {
  return `${stem.trim()}${ext}`;
}
