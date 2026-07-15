package com.ymc.paper.service.port;

/**
 * 원본 파일 저장소(S3) 포트.
 * BE는 업로드 권한을 위임하고 업로드 여부만 확인한다 (ADR-001).
 */
public interface FileStorage {

    /**
     * 업로드 권한을 짧게 위임하는 presigned PUT URL을 발급한다. 만료 시각은 구현이 설정에서 정한다.
     *
     * <p>서명에 contentType이 포함되므로 클라이언트는 PUT 시 같은 {@code Content-Type} 헤더를
     * 보내야 한다 — 다르면 S3가 거절한다.
     */
    PresignedUpload presignUpload(String fileKey, String contentType);

    /** 업로드가 실제로 끝났는지 확인한다 (S3 HEAD). */
    boolean exists(String fileKey);

    /**
     * 원본 파일을 내려받는 presigned GET URL을 발급한다. 만료는 구현이 설정에서 정한다.
     *
     * <p>서명에 Content-Disposition(attachment; filename=주어진 filename)을 포함해
     * 브라우저가 원본 파일명으로 저장하게 한다.
     */
    PresignedDownload presignDownload(String fileKey, String filename);
}
