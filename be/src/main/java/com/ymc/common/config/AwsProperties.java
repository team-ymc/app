package com.ymc.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 aws.* 바인딩.
 * - endpoint 비어있음(prod) → 실제 AWS
 * - endpoint 지정(local)   → LocalStack (http://localhost.localstack.cloud:4566)
 * 리소스 이름(큐/버킷)은 환경 무관 공통 — infra/local/bootstrap.sh와 일치해야 한다.
 */
@ConfigurationProperties(prefix = "aws")
public record AwsProperties(
        String region,
        String endpoint,
        Credentials credentials,
        S3 s3,
        Sqs sqs
) {
    public record Credentials(String accessKey, String secretKey) {}

    public record S3(String bucket) {}

    public record Sqs(String parseRequestQueue, String parseResultQueue) {}

    public boolean hasEndpointOverride() {
        return endpoint != null && !endpoint.isBlank();
    }

    public boolean hasStaticCredentials() {
        return credentials != null
                && credentials.accessKey() != null && !credentials.accessKey().isBlank();
    }
}
