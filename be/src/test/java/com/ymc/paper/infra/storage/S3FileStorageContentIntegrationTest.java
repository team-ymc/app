package com.ymc.paper.infra.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.service.port.FileStorage;
import com.ymc.paper.service.port.PresignedDownload;
import com.ymc.support.IntegrationTest;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** 본문 적재가 쓰는 S3 읽기·asset presign. */
class S3FileStorageContentIntegrationTest extends IntegrationTest {

    @Autowired
    FileStorage storage;

    @Test
    void readUtf8은_객체_본문을_문자열로_돌려준다() {
        put("papers/p1/assets/formulas/formula_0.tex", "E = mc^2");

        assertThat(storage.readUtf8("papers/p1/assets/formulas/formula_0.tex"))
                .isEqualTo("E = mc^2");
    }

    @Test
    void presignAssetGet은_다운로드_강제_없는_GET_URL을_발급한다() {
        put("papers/p1/assets/images/image_0.jpg", "fake-jpg-bytes");

        PresignedDownload presigned = storage.presignAssetGet("papers/p1/assets/images/image_0.jpg");

        assertThat(presigned.url()).contains("papers/p1/assets/images/image_0.jpg");
        assertThat(presigned.url()).doesNotContain("response-content-disposition");
        assertThat(presigned.expiresAt()).isAfter(java.time.Instant.now());
    }

    private void put(String key, String body) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(awsProperties.s3().bucket()).key(key).build(),
                RequestBody.fromBytes(body.getBytes(StandardCharsets.UTF_8)));
    }
}
