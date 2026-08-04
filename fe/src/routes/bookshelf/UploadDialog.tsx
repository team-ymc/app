// 이식: project-docs/design/v1/Paper Bookshelf Page.dc.html의 <!-- Upload dialog --> (프레임·타이포·간격
// 값 그대로) + 구 App.jsx(fe/src/App.jsx, 죽은 코드)의 업로드 시퀀스(createPaper → uploadToS3 XHR 진행률 →
// completeUpload). sc-if→{cond && …}, onClick={{ stopPropagation }}→stopPropagation, style="{{ x }}"→style={x}
// 기계적 전사(플랜 공통 변환표). 목업은 mockUpload 한 단계뿐이라 idle/file-selected/uploading 3단계 카드는
// App.jsx D4 상태 모델을 같은 다이얼로그 프레임 안에 이식했다.
import { useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { X, UploadSimple, FileText } from '@phosphor-icons/react';
import { Button } from '../../design/components/Button';
import { createPaper, uploadToS3, completeUpload } from '../../api/papers';
import { ApiError } from '../../api/types';

type UploadPhase = 'idle' | 'file-selected' | 'uploading';

export interface UploadDialogProps {
  open: boolean;
  onClose: () => void;
  onUploaded: () => void;
}

function formatBytes(bytes: number | undefined): string {
  if (bytes === undefined) return '';
  const mb = bytes / (1024 * 1024);
  if (mb >= 1) return `${mb.toFixed(1)} MB`;
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

// ApiError면 code·message, 아니면 message만 보여준다 (409 DUPLICATE_FILENAME / presigned 만료(403) /
// S3 PUT 실패 / complete 4xx 4경로를 다이얼로그 안에 그대로 노출).
function describeError(err: unknown): string {
  if (err instanceof ApiError) return err.code ? `${err.code}: ${err.message}` : err.message;
  if (err instanceof Error) return err.message;
  return '알 수 없는 오류가 발생했습니다';
}

export default function UploadDialog({ open, onClose, onUploaded }: UploadDialogProps) {
  const queryClient = useQueryClient();

  const [phase, setPhase] = useState<UploadPhase>('idle');
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [uploadPct, setUploadPct] = useState(0);
  const [dragOver, setDragOver] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const fileInputRef = useRef<HTMLInputElement>(null);

  if (!open) return null;

  function stopPropagation(e: React.MouseEvent) {
    e.stopPropagation();
  }

  function pickFile() {
    fileInputRef.current?.click();
  }

  function onFileChosen(file: File | null) {
    if (!file) return;
    if (file.type !== 'application/pdf') {
      setError(new Error('PDF 파일만 업로드할 수 있습니다'));
      return;
    }
    setError(null);
    setSelectedFile(file);
    setPhase('file-selected');
  }

  function handleFileInputChange(e: React.ChangeEvent<HTMLInputElement>) {
    onFileChosen(e.target.files?.[0] ?? null);
    e.target.value = '';
  }

  function clearFile() {
    setSelectedFile(null);
    setPhase('idle');
  }

  function onDragOver(e: React.DragEvent) {
    e.preventDefault();
    setDragOver(true);
  }

  function onDragLeave() {
    setDragOver(false);
  }

  function onDrop(e: React.DragEvent) {
    e.preventDefault();
    setDragOver(false);
    onFileChosen(e.dataTransfer.files?.[0] ?? null);
  }

  function handleClose() {
    if (phase === 'uploading') return; // 업로드 중엔 XHR abort 방지를 위해 닫지 않는다
    onClose();
  }

  async function startUpload() {
    if (phase !== 'file-selected' || !selectedFile) return;
    setPhase('uploading');
    setUploadPct(0);
    setError(null);
    try {
      const created = await createPaper(selectedFile.name, 'application/pdf');
      await uploadToS3(created.uploadUrl, selectedFile, (pct) => setUploadPct(pct));
      await completeUpload(created.paperId);
      queryClient.invalidateQueries({ queryKey: ['papers'] });
      onClose();
      onUploaded();
    } catch (e) {
      setError(e); // 숨기지 않는다 — 다이얼로그 안에 그대로 노출
      setPhase('file-selected'); // 재시도 가능하도록 복귀
    }
  }

  const isUploading = phase === 'uploading';

  const dropzoneStyle: CSSProperties = {
    border: `1px dashed ${dragOver ? 'var(--color-primary)' : 'var(--color-border)'}`,
    borderRadius: 'var(--radius-structural)',
    padding: '36px 16px',
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    gap: '10px',
    background: 'var(--color-bg-surface)',
    cursor: 'pointer',
  };

  const fileCardStyle: CSSProperties = {
    border: '1px dashed var(--color-border)',
    borderRadius: 'var(--radius-structural)',
    padding: '16px',
    display: 'flex',
    alignItems: isUploading ? 'flex-start' : 'center',
    gap: '12px',
    background: 'var(--color-bg-surface)',
    boxSizing: 'border-box',
  };

  const thumbStyle: CSSProperties = {
    width: '38px',
    height: '48px',
    flexShrink: 0,
    background: 'var(--color-bg-paper)',
    border: '1px solid var(--color-border)',
    borderRadius: 'var(--radius-paper)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
  };

  return (
    <div
      onClick={handleClose}
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(31,53,82,0.35)',
        zIndex: 60,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <div
        onClick={stopPropagation}
        style={{
          position: 'relative',
          width: '420px',
          maxWidth: '90vw',
          background: 'var(--color-bg-paper)',
          border: '1px solid var(--color-border)',
          borderRadius: 'var(--radius-control)',
          boxShadow: 'var(--shadow-menu)',
          padding: '28px',
          boxSizing: 'border-box',
        }}
      >
        <button
          onClick={handleClose}
          disabled={isUploading}
          aria-label="닫기"
          style={{
            position: 'absolute',
            top: '14px',
            right: '14px',
            background: 'transparent',
            border: 'none',
            cursor: isUploading ? 'not-allowed' : 'pointer',
            color: 'var(--color-text-muted)',
            padding: '4px',
            display: 'flex',
            alignItems: 'center',
            opacity: isUploading ? 0.5 : 1,
          }}
        >
          <X size={16} />
        </button>

        <div
          style={{
            fontFamily: 'var(--font-serif)',
            fontSize: '19px',
            fontWeight: 600,
            color: 'var(--color-text-heading)',
            margin: '2px 24px 6px 0',
            whiteSpace: 'nowrap',
          }}
        >
          종이 등록
        </div>
        <div
          style={{
            fontFamily: 'var(--font-sans)',
            fontSize: '13px',
            color: 'var(--color-text-muted)',
            marginBottom: '18px',
          }}
        >
          PDF 파일을 업로드하면 서재에 추가됩니다
        </div>

        <input
          ref={fileInputRef}
          type="file"
          accept="application/pdf"
          onChange={handleFileInputChange}
          style={{ display: 'none' }}
        />

        {phase === 'idle' && (
          <div onClick={pickFile} onDragOver={onDragOver} onDragLeave={onDragLeave} onDrop={onDrop} style={dropzoneStyle}>
            <UploadSimple size={26} color="var(--color-text-muted)" />
            <div style={{ fontFamily: 'var(--font-sans)', fontSize: '13px', color: 'var(--color-text-muted)' }}>
              파일을 여기로 드래그하거나 클릭하여 선택하세요
            </div>
          </div>
        )}

        {(phase === 'file-selected' || phase === 'uploading') && selectedFile && (
          <div style={fileCardStyle}>
            <div style={thumbStyle}>
              <FileText size={20} color="var(--color-text-muted)" />
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div
                style={{
                  fontFamily: 'var(--font-sans)',
                  fontSize: '13px',
                  fontWeight: 600,
                  color: 'var(--color-text-heading)',
                  whiteSpace: 'nowrap',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                }}
              >
                {selectedFile.name}
              </div>
              <div style={{ fontFamily: 'var(--font-sans)', fontSize: '12px', color: 'var(--color-text-muted)', marginTop: '2px' }}>
                {isUploading ? `업로드 중… ${uploadPct}%` : formatBytes(selectedFile.size)}
              </div>
              {isUploading && (
                <div
                  style={{
                    height: '5px',
                    background: 'var(--color-border)',
                    borderRadius: 'var(--radius-pill)',
                    overflow: 'hidden',
                    marginTop: '8px',
                  }}
                >
                  <div
                    style={{
                      height: '100%',
                      width: `${uploadPct}%`,
                      background: 'var(--color-primary)',
                      borderRadius: 'var(--radius-pill)',
                      transition: 'width 150ms ease',
                    }}
                  />
                </div>
              )}
            </div>
            {!isUploading && (
              <button
                onClick={clearFile}
                aria-label="파일 제거"
                style={{
                  width: '26px',
                  height: '26px',
                  flexShrink: 0,
                  borderRadius: 'var(--radius-control)',
                  border: '1px solid var(--color-border)',
                  background: 'var(--color-bg-paper)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  cursor: 'pointer',
                  color: 'var(--color-text-muted)',
                }}
              >
                <X size={12} />
              </button>
            )}
          </div>
        )}

        {error != null && (
          <div
            style={{
              marginTop: '14px',
              padding: '10px 12px',
              borderRadius: 'var(--radius-structural)',
              border: '1px solid var(--color-danger)',
              background: 'var(--color-bg-surface)',
              color: 'var(--color-danger)',
              fontFamily: 'var(--font-sans)',
              fontSize: '12.5px',
              lineHeight: 1.4,
            }}
          >
            {describeError(error)}
          </div>
        )}

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '22px' }}>
          <Button variant="secondary" onClick={handleClose} disabled={isUploading}>
            <span style={{ whiteSpace: 'nowrap' }}>취소</span>
          </Button>
          <Button variant="primary" onClick={startUpload} disabled={phase !== 'file-selected'}>
            <span style={{ whiteSpace: 'nowrap' }}>{isUploading ? '업로드 중…' : '업로드'}</span>
          </Button>
        </div>
      </div>
    </div>
  );
}
