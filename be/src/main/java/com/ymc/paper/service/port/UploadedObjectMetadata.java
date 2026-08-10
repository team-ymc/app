package com.ymc.paper.service.port;

/** S3 HEAD로 확인한 업로드 객체 메타데이터. */
public record UploadedObjectMetadata(long contentLength) {
}
