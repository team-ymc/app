package com.ymc.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.clearInvocations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.chat.domain.ChatMessageRepository;
import com.ymc.chat.domain.ChatSessionRepository;
import com.ymc.chat.service.port.AiAgentStreamPort;
import com.ymc.common.config.AwsProperties;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.paper.service.PaperTransitions;
import com.ymc.paper.service.port.FileStorage;
import com.ymc.paper.service.port.ParseRequestPublisher;
import com.ymc.user.domain.RefreshTokenRepository;
import com.ymc.user.domain.UserRepository;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * 통합 테스트 베이스 — PostgreSQL·LocalStack 컨테이너를 띄운 실제 스프링 컨텍스트.
 * 두 설정을 같은 조합으로 import 하므로 하위 테스트 클래스들이 컨텍스트(=컨테이너)를 공유한다.
 */
@SpringBootTest(properties = "ai.fake-stream=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, LocalStackTestConfiguration.class})
public abstract class IntegrationTest {

    /** 비동기 소비를 기다리는 상한. 재전달(visibility timeout 2초)까지 넉넉히 덮는다. */
    protected static final Duration CONSUME_TIMEOUT = Duration.ofSeconds(20);

    /** 테스트 JWT의 subject이자 테스트 데이터의 소유자 (YMC-215). */
    protected static final UUID TEST_USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    protected ChatMessageRepository chatMessageRepository;

    @Autowired
    protected ChatSessionRepository chatSessionRepository;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected PaperRepository paperRepository;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected RefreshTokenRepository refreshTokenRepository;

    @Autowired
    protected S3Client s3;

    @Autowired
    protected SqsClient sqs;

    @Autowired
    protected AwsProperties awsProperties;

    /*
     * 스파이를 (쓰지 않는 테스트까지 포함해) 베이스에 모아 둔 이유: 빈 override는 스프링 컨텍스트
     * 캐시 키의 일부다. 테스트 클래스마다 다른 조합을 선언하면 컨텍스트가 갈라지고 컨테이너도 그만큼
     * 더 뜬다. 조합을 하나로 고정해 통합 테스트 전체가 컨텍스트·컨테이너 한 벌을 공유하게 한다.
     *
     * 스파이는 기본적으로 실제 빈에 위임하므로 스텁하지 않으면 동작이 같고, 테스트마다 자동 리셋된다.
     * 쓰임새: "HEAD를 부르지 않았다" 검증, 큐 발행 실패·전이 커밋 실패 주입.
     */

    @MockitoSpyBean
    protected FileStorage fileStorage;

    @MockitoSpyBean
    protected ParseRequestPublisher parseRequestPublisher;

    @MockitoSpyBean
    protected PaperTransitions paperTransitions;

    @MockitoSpyBean
    protected AiAgentStreamPort aiAgentStreamPort;

    /**
     * 컨텍스트(=컨테이너)를 재사용하므로 테스트마다 DB와 큐를 직접 비운다.
     * PurgeQueue는 호출 간격 제한이 있어 쓰지 않고 받아서 지운다.
     */
    @BeforeEach
    void resetState() {
        chatMessageRepository.deleteAll();
        chatSessionRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        paperRepository.deleteAll();
        drain(parseRequestQueueUrl());
        drain(parseResultQueueUrl());
    }

    /** MockMvc 요청에 인증 principal 주입. 디코더를 거치지 않는 테스트 전용 JWT다. */
    protected RequestPostProcessor userJwt() {
        return SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject(TEST_USER_ID.toString()));
    }

    /** UPLOAD_PENDING 레코드. S3에는 아직 아무것도 없다. */
    protected Paper givenPendingPaper(String filename) {
        return paperRepository.save(Paper.register(TEST_USER_ID, filename, Instant.now()));
    }

    /** 파싱 대기 중(PROCESSING) 레코드 — 결과 수신 시나리오의 출발점. */
    protected Paper givenProcessingPaper(String filename) {
        Paper paper = givenPendingPaper(filename);
        paperTransitions.markUploaded(paper.getId());
        paperTransitions.markProcessing(paper.getId());
        clearInvocations(paperTransitions);
        return reload(paper.getId());
    }

    /** FE가 presigned URL로 업로드한 상황을 만든다 (여기선 서버 자격증명으로 바로 넣는다). */
    protected void givenUploadedObject(Paper paper) {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(awsProperties.s3().bucket())
                        .key(paper.getFileKey())
                        .contentType("application/pdf")
                        .build(),
                RequestBody.fromBytes("%PDF-1.4\n%fake pdf for test\n".getBytes(StandardCharsets.UTF_8)));
    }

    protected Paper reload(UUID paperId) {
        return paperRepository.findById(paperId).orElseThrow();
    }

    protected String parseRequestQueueUrl() {
        return queueUrl(awsProperties.sqs().parseRequestQueue());
    }

    protected String parseResultQueueUrl() {
        return queueUrl(awsProperties.sqs().parseResultQueue());
    }

    protected String queueUrl(String queueName) {
        return sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()).queueUrl();
    }

    /** 큐에 쌓인 메시지를 모두 받아 지운다. */
    protected void drain(String queueUrl) {
        while (true) {
            List<Message> messages = receive(queueUrl, 0);
            if (messages.isEmpty()) {
                return;
            }
            messages.forEach(m -> sqs.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(m.receiptHandle())
                    .build()));
        }
    }

    /** 큐의 메시지를 (지우지 않고) 읽는다. 소비자가 없는 parse-requests 검증용. */
    protected List<Message> receive(String queueUrl, int waitSeconds) {
        return new ArrayList<>(sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(waitSeconds)
                .build())
                .messages());
    }

    /** parse-results에 AI 워커인 척 원문 그대로 발행한다 (역직렬화 실패 시나리오 포함). */
    protected void publishParseResult(String rawJson) {
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(parseResultQueueUrl())
                .messageBody(rawJson)
                .build());
    }

    /**
     * 메시지가 실제로 ack(삭제)될 때까지 기다린다.
     *
     * <p>{@link #receive}가 비었다는 것만으로는 부족하다 — 처리 중(in-flight) 메시지도 안 보인다.
     * ack하지 않으면 visibility timeout 뒤 되살아나므로, "보이는 것"과 "처리 중인 것"이 함께 0이어야
     * 비로소 소비된 것이다. ack하지 않는 구현이면 재전달이 반복돼 이 대기가 끝나지 않는다.
     */
    // SQS는 visible, not_visible, ack 세 상태가 있다.
    // ack 받기 전에는 큐에 남아 있다. 즉, visible + not_visible 둘 다 확인이 필요하다.
    protected void awaitConsumed(String queueUrl) {
        await().atMost(CONSUME_TIMEOUT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Map<QueueAttributeName, String> attributes = sqs.getQueueAttributes(
                    GetQueueAttributesRequest.builder()
                            .queueUrl(queueUrl)
                            .attributeNames(
                                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)
                            .build())
                    .attributes();

            assertThat(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                    .as("큐에 남은 메시지").isEqualTo("0");
            assertThat(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
                    .as("처리 중(ack 대기) 메시지").isEqualTo("0");
        });
    }
}
