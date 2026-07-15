package com.ymc.paper.infra.storage;

import org.springframework.stereotype.Component;

import com.ymc.common.config.AwsProperties;
import com.ymc.paper.service.port.FileStorage;
import com.ymc.paper.service.port.PresignedUpload;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.http.HttpStatusCode;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * {@link FileStorage}의 S3 구현. local(LocalStack)·prod의 차이는 {@link AwsProperties}의
 * endpoint 설정뿐이며 {@link S3Presigner} 빈이 path-style까지 이미 처리한다 (design D5).
 */
@Component
@RequiredArgsConstructor
public class S3FileStorage implements FileStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final AwsProperties props;

    @Override
    public PresignedUpload presignUpload(String fileKey, String contentType) {
        PresignedPutObjectRequest presigned = presigner.presignPutObject(
                PutObjectPresignRequest.builder()
                        .signatureDuration(props.s3().presignExpiry())
                        .putObjectRequest(PutObjectRequest.builder()
                                .bucket(props.s3().bucket())
                                .key(fileKey)
                                .contentType(contentType)
                                .build())
                        .build());
        return new PresignedUpload(presigned.url().toString(), presigned.expiration());
    }

    /**
     * 객체 없음(404)만 false로 접는다. S3 일시 장애(5xx·타임아웃)는 삼키지 않고 그대로 던져
     * 5xx가 되게 한다 — 장애를 "업로드 안 됨"으로 오인해 409를 주면 FE가 헛되이 재업로드한다.
     *
     * <p>HEAD 응답에는 본문이 없어 에러 코드를 못 읽는 경우가 있다. SDK가 {@link NoSuchKeyException}으로
     * 매핑하지 못하고 상태코드만 있는 {@link S3Exception}으로 던지는 엔드포인트가 있어 둘 다 받는다.
     */
    @Override
    public boolean exists(String fileKey) {
        try {
            s3.headObject(HeadObjectRequest.builder()   // HEAD 요청
                    .bucket(props.s3().bucket())
                    .key(fileKey)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {                // 404
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == HttpStatusCode.NOT_FOUND) {
                return false;
            }
            throw e; // 5xx : 서버 장애 전파
        }
    }
}
