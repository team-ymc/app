package com.ymc.paper.service.port;

import java.util.Optional;

/**
 * 원본 파일 저장소(S3) 포트.
 * BE는 업로드 권한을 위임하고 업로드 객체의 메타데이터만 확인한다 (ADR-001).
 */
public interface FileStorage {

    /**
     * 업로드 권한을 짧게 위임하는 presigned PUT URL을 발급한다. 만료 시각은 구현이 설정에서 정한다.
     *
     * <p>contentType과 contentLength가 서명에 들어가므로 클라이언트는 PUT 시 같은
     * {@code Content-Type}과 정확히 같은 바이트 수를 보내야 한다 — 다르면 S3가 거절한다.
     * 크기를 서명에 넣는 것이 상한을 S3에서 강제하는 수단이다.
     */
    PresignedUpload presignUpload(String fileKey, String contentType, long contentLength);

    /** 업로드가 실제로 끝났는지 확인하고 메타데이터를 반환한다 (S3 HEAD). */
    Optional<UploadedObjectMetadata> head(String fileKey);

    /** 거절된 원본 객체를 삭제한다. */
    void delete(String fileKey);

    /**
     * 원본 파일을 내려받는 presigned GET URL을 발급한다. 만료는 구현이 설정에서 정한다.
     *
     * <p>서명에 Content-Disposition(attachment; filename=주어진 filename)을 포함해
     * 브라우저가 원본 파일명으로 저장하게 한다.
     */
    PresignedDownload presignDownload(String fileKey, String filename);

    /** 패키지 파일(manifest·document.json·tex·html)을 UTF-8 문자열로 읽는다. */
    String readUtf8(String fileKey);

    /** 이미지·차트 asset의 인라인 표시용 presigned GET. 다운로드용과 달리 Content-Disposition을 싣지 않는다. */
    PresignedDownload presignAssetGet(String fileKey);
}
