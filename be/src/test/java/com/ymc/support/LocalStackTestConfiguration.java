package com.ymc.support;

import java.util.Map;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.utility.DockerImageName;

import com.ymc.common.config.AwsProperties;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * 통합 테스트용 LocalStack(S3·SQS) 컨테이너. presign·HEAD·큐 발행/수신을 실제 서비스로 검증한다 (design D9).
 *
 * <p>{@link TestcontainersConfiguration}과 같은 이유로 컨테이너를 빈으로 등록한다 — 스프링 컨텍스트
 * 캐싱에 올라타 전체 테스트 실행에서 1회만 기동한다.
 *
 * <p>{@code @ServiceConnection}은 LocalStack을 모르므로(우리 설정 키는 {@code aws.*}) 엔드포인트·자격증명을
 * {@link DynamicPropertyRegistrar}로 주입한다. 이 값이 application.yml의 local 프로파일보다 우선한다.
 * region은 덮지 않는다 — 운영과 같은 리전으로 서명·생성해 경로를 최대한 같게 둔다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class LocalStackTestConfiguration {

    /** ⚠ 이미지 태그는 infra/local/docker-compose.yml의 localstack 서비스와 일치해야 한다. */
    private static final DockerImageName LOCALSTACK_IMAGE =
            DockerImageName.parse("localstack/localstack:3");

    @Bean
    LocalStackContainer localStackContainer() {
        return new LocalStackContainer(LOCALSTACK_IMAGE).withServices(Service.S3, Service.SQS);
    }

    @Bean
    DynamicPropertyRegistrar awsEndpointProperties(LocalStackContainer localstack) {
        return registry -> {
            registry.add("aws.endpoint", () -> localstack.getEndpoint().toString());
            registry.add("aws.credentials.access-key", localstack::getAccessKey);
            registry.add("aws.credentials.secret-key", localstack::getSecretKey);
        };
    }

    /**
     * 버킷·큐 생성. 운영에선 IaC가 만드는 것들이라 애플리케이션 코드에 없다 — 테스트에서만 손으로 만든다.
     *
     * <p>싱글턴 초기화 중에 실행되므로 {@code @SqsListener} 리스너 컨테이너(SmartLifecycle)가
     * 폴링을 시작하기 전에 큐가 존재한다.
     */
    @Bean
    InitializingBean awsResourceBootstrap(S3Client s3, SqsClient sqs, AwsProperties props) {
        return () -> {
            try {
                s3.createBucket(CreateBucketRequest.builder().bucket(props.s3().bucket()).build());
            } catch (BucketAlreadyOwnedByYouException e) {
                // 컨텍스트 재사용 시 이미 있다 — 무시
            }
            sqs.createQueue(CreateQueueRequest.builder()
                    .queueName(props.sqs().parseRequestQueue())
                    .build());

            // parse-results는 visibility timeout을 짧게 준다. 일시적 장애의 재전달을 검증해야 하는데
            // 기본값(30초)이면 그 테스트 하나가 30초를 잡아먹는다. 처리 자체는 DB update 한 번이라
            // 정상 경로가 이 시간을 넘길 일은 없다.
            sqs.createQueue(CreateQueueRequest.builder()
                    .queueName(props.sqs().parseResultQueue())
                    .attributesWithStrings(Map.of(
                            QueueAttributeName.VISIBILITY_TIMEOUT.toString(), "2"))
                    .build());
        };
    }
}
