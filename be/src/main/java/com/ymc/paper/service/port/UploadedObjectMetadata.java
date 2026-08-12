package com.ymc.paper.service.port;

/** S3 HEAD로 확인한 업로드 객체 메타데이터. checksumSha256은 S3가 검증한 값이며 없을 수 있다. */
public record UploadedObjectMetadata(long contentLength, String checksumSha256) {
}
