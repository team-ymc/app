package com.ymc.dev;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ymc.common.config.AwsProperties;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * [임시] LocalStack 연동 스모크 테스트 — local 프로파일에서만 뜬다.
 *
 * GET /dev/aws-smoke 한 번으로:
 *   1) S3 업로드  2) presigned GET URL 발급  3) SQS 전송→수신→삭제 왕복
 *
 * presignedGetUrl을 브라우저에 붙여넣어 파일 내용이 보이면
 * 호스트 통일(YMC-185, localhost.localstack.cloud)까지 검증 완료.
 * 본 기능(document 도메인) 구현이 자리잡으면 이 클래스는 삭제한다.
 */
@RestController
@Profile("local")
public class AwsSmokeController {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final SqsClient sqs;
    private final AwsProperties props;

    public AwsSmokeController(S3Client s3, S3Presigner presigner, SqsClient sqs, AwsProperties props) {
        this.s3 = s3;
        this.presigner = presigner;
        this.sqs = sqs;
        this.props = props;
    }

    @GetMapping("/dev/aws-smoke")
    public Map<String, Object> smoke() {
        Map<String, Object> out = new LinkedHashMap<>();

        // 1) S3 업로드
        String key = "smoke/hello-" + System.currentTimeMillis() + ".txt";
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(props.s3().bucket())
                        .key(key)
                        .contentType("text/plain; charset=utf-8")
                        .build(),
                RequestBody.fromString("hello from BE @ " + Instant.now()));
        out.put("s3Put", "s3://" + props.s3().bucket() + "/" + key);

        // 2) presigned GET URL (5분) — 브라우저에서 열리는지 확인
        String presignedUrl = presigner.presignGetObject(
                GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(5))
                        .getObjectRequest(GetObjectRequest.builder()
                                .bucket(props.s3().bucket())
                                .key(key)
                                .build())
                        .build())
                .url()
                .toString();
        out.put("presignedGetUrl", presignedUrl);

        // 3) SQS 왕복 — parse-requests에 보내고 즉시 받아서 삭제.
        //    (원래 이 큐의 소비자는 parser 워커. 스모크에서만 BE가 대신 받는다)
        String queueUrl = sqs.getQueueUrl(GetQueueUrlRequest.builder()
                .queueName(props.sqs().parseRequestQueue())
                .build()).queueUrl();

        // 메시지 전송
        String body = "smoke-" + System.currentTimeMillis();
        String messageId = sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(body)
                .build()).messageId();
        out.put("sqsSentMessageId", messageId);

        // 메시지 수신
        List<Message> received = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(2)
                .build()).messages();

        if (received.isEmpty()) {
            out.put("sqsRoundtrip", "EMPTY — 메시지를 못 받음 (워커가 먼저 소비했거나 지연)");
        } else {
            Message m = received.get(0);
            out.put("sqsReceivedBody", m.body());
            out.put("sqsRoundtrip", body.equals(m.body()) ? "OK" : "MISMATCH — 큐에 다른 메시지가 있었음");
            sqs.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(m.receiptHandle())
                    .build());
        }

        return out;
    }
}
