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
