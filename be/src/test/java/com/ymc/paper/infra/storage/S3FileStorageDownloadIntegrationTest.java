package com.ymc.paper.infra.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ymc.paper.service.port.PresignedDownload;
import com.ymc.support.IntegrationTest;

/** spec: paper-download (Task 2). presign GET URL에 content-disposition이 서명되는지. */
class S3FileStorageDownloadIntegrationTest extends IntegrationTest {

    @Test
    @DisplayName("presignDownload: attachment content-disposition과 원본 파일명이 서명 쿼리에 실린다")
    void signsContentDisposition() {
        PresignedDownload d = fileStorage.presignDownload(
                "papers/550e8400-e29b-41d4-a716-446655440000/original.pdf",
                "attention.pdf");

        assertThat(d.url()).contains("response-content-disposition");
        assertThat(d.url()).contains("attention.pdf");
        assertThat(d.url()).contains("X-Amz-Signature");
        assertThat(d.expiresAt()).isNotNull();
    }
}
