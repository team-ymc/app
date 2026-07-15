package com.ymc.common.config;

import java.net.URI;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/**
 * AWS SDK v2 클라이언트 빈.
 * - local: endpoint override + static creds(test/test) + S3 path-style
 * - prod : override 없음 → 실제 AWS 엔드포인트 + IAM 역할 자동 획득
 *
 * <p>{@link SqsAsyncClient}까지 여기서 만드는 이유: spring-cloud-aws의 SqsAutoConfiguration은
 * {@code @ConditionalOnMissingBean}이라 이 빈이 있으면 물러난다. 덕분에 {@code @SqsListener}도
 * spring.cloud.aws.* 설정을 따로 두지 않고 이 파일의 aws.* 하나만 따른다.
 */
@Configuration
@EnableConfigurationProperties(AwsProperties.class)
public class AwsConfig {
    @Bean
    public S3Client s3Client(AwsProperties props) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(credentialsProvider(props));
        if (props.hasEndpointOverride()) {
            builder.endpointOverride(URI.create(props.endpoint()))
                    // path-style: 버킷을 호스트명이 아닌 경로에 → 로컬 호스트명 통일 유지
                    .forcePathStyle(true);
        }
        return builder.build();
    }

    @Bean
    public S3Presigner s3Presigner(AwsProperties props) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(credentialsProvider(props));
        if (props.hasEndpointOverride()) {
            builder.endpointOverride(URI.create(props.endpoint()))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build());
        }
        return builder.build();
    }

    @Bean
    public SqsClient sqsClient(AwsProperties props) {
        SqsClientBuilder builder = SqsClient.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(credentialsProvider(props));
        if (props.hasEndpointOverride()) {
            builder.endpointOverride(URI.create(props.endpoint()));
        }
        return builder.build();
    }

    /** {@code @SqsListener}(spring-cloud-aws)가 쓰는 클라이언트. 발행은 동기 {@link SqsClient}가 맡는다. */
    @Bean
    public SqsAsyncClient sqsAsyncClient(AwsProperties props) {
        SqsAsyncClientBuilder builder = SqsAsyncClient.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(credentialsProvider(props));
        if (props.hasEndpointOverride()) {
            builder.endpointOverride(URI.create(props.endpoint()));
        }
        return builder.build();
    }

    private AwsCredentialsProvider credentialsProvider(AwsProperties props) {
        if (props.hasStaticCredentials()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                    props.credentials().accessKey(), props.credentials().secretKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }
}
