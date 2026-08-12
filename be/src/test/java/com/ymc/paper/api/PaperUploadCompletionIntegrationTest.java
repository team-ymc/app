package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.service.PaperUploadCompletionService;
import com.ymc.paper.service.PaperUploadPolicy;
import com.ymc.paper.service.port.UploadedObjectMetadata;
import com.ymc.support.IntegrationTest;

/**
 * spec: paper-upload-completion, Document 중복 제거 전환 (tasks 5.2).
 *
 * <p>발행 실패처럼 컨트롤러가 5xx로 끝나는 시나리오는 MockMvc가 예외를 그대로 되던지므로
 * 서비스를 직접 호출해 "예외가 나가고 document는 UPLOADED로 반납된다"를 검증한다.
 */
class PaperUploadCompletionIntegrationTest extends IntegrationTest {

    @Autowired
    private PaperUploadCompletionService completionService;

    @Autowired
    private PaperUploadPolicy uploadPolicy;

    @Test
    void 신규_checksum이면_document를_만들고_발행하고_PROCESSING을_반환한다() throws Exception {
        Paper paper = givenPendingPaper("first.pdf");
        givenUploadedObject(paper);

        mockMvc.perform(post("/api/papers/{id}/complete", paper.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        Paper linked = reload(paper.getId());
        assertThat(linked.getDocumentId()).isNotNull();
        Document document = documentRepository.findById(linked.getDocumentId()).orElseThrow();
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(document.getRequestPaperId()).isEqualTo(paper.getId());
        assertThat(document.getFileKey()).isEqualTo(paper.getFileKey());
        verify(parseRequestPublisher).publish(paper.getId(), paper.getFileKey());
    }

    @Test
    void 기존_checksum이면_연결만_하고_발행없이_현재_상태를_반환하고_중복_객체를_지운다() throws Exception {
        // 선행자: 같은 바이트를 먼저 완료
        Paper first = givenPendingPaper("origin.pdf");
        givenUploadedObject(first);
        mockMvc.perform(post("/api/papers/{id}/complete", first.getId()).with(userJwt()))
                .andExpect(status().isOk());
        clearInvocations(parseRequestPublisher);

        // 다른 사용자가 같은 바이트를 다른 파일명으로 업로드
        Paper second = paperRepository.save(Paper.register(OTHER_USER_ID, "copy.pdf", Instant.now()));
        givenUploadedObject(second);

        mockMvc.perform(post("/api/papers/{id}/complete", second.getId()).with(otherUserJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));   // 기존 작업 상태 즉시 반환

        assertThat(reload(second.getId()).getDocumentId())
                .isEqualTo(reload(first.getId()).getDocumentId());
        verify(parseRequestPublisher, never()).publish(any(), any());
        verify(fileStorage).delete(second.getFileKey());     // 중복 객체 삭제
        verify(fileStorage, never()).delete(first.getFileKey());   // 대표 원본은 보존
    }

    @Test
    void 발행_실패면_예외가_나가고_document는_UPLOADED로_반납되고_재호출이_구제한다() throws Exception {
        Paper paper = givenPendingPaper("rescue.pdf");
        givenUploadedObject(paper);
        doThrow(new RuntimeException("SQS down")).doCallRealMethod()
                .when(parseRequestPublisher).publish(any(), any());

        assertThatThrownBy(() -> completionService.complete(paper.getId(), TEST_USER_ID))
                .isInstanceOf(RuntimeException.class);

        Document document = documentRepository
                .findById(reload(paper.getId()).getDocumentId()).orElseThrow();
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.UPLOADED);   // 반납됨

        // 같은 Paper의 complete 재호출이 구제 발행한다 (HEAD 없이)
        clearInvocations(fileStorage);
        mockMvc.perform(post("/api/papers/{id}/complete", paper.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
        verify(fileStorage, never()).head(any());
        verify(parseRequestPublisher, times(2)).publish(paper.getId(), paper.getFileKey());
    }

    @Test
    void 구제_발행_식별자는_재호출한_second가_아니라_최초_first의_paperId다() throws Exception {
        Paper first = givenPendingPaper("rescue-first.pdf");
        givenUploadedObject(first);
        doThrow(new RuntimeException("SQS down")).doCallRealMethod()
                .when(parseRequestPublisher).publish(any(), any());

        assertThatThrownBy(() -> completionService.complete(first.getId(), TEST_USER_ID))
                .isInstanceOf(RuntimeException.class);

        Document document = documentRepository
                .findById(reload(first.getId()).getDocumentId()).orElseThrow();
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.UPLOADED);   // 반납됨

        // 같은 바이트를 다른 사용자가 다른 파일명으로 업로드 (second) — 구제 발행은 first를 대상으로 나가야 한다
        Paper second = paperRepository.save(Paper.register(OTHER_USER_ID, "rescue-second.pdf", Instant.now()));
        givenUploadedObject(second);

        mockMvc.perform(post("/api/papers/{id}/complete", second.getId()).with(otherUserJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        verify(parseRequestPublisher, times(2)).publish(first.getId(), first.getFileKey());
        verify(fileStorage).delete(second.getFileKey());
        verify(fileStorage, never()).delete(first.getFileKey());
        assertThat(reload(second.getId()).getDocumentId())
                .isEqualTo(reload(first.getId()).getDocumentId());
    }

    @Test
    void 이미_연결된_paper의_재호출은_HEAD없이_현재_상태를_반환한다() throws Exception {
        Paper paper = givenProcessingPaper("idem.pdf");
        clearInvocations(fileStorage, parseRequestPublisher);

        mockMvc.perform(post("/api/papers/{id}/complete", paper.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        verify(fileStorage, never()).head(any());
        verify(parseRequestPublisher, never()).publish(any(), any());
    }

    @Test
    void 중복_객체_삭제가_실패해도_complete는_성공한다() throws Exception {
        Paper first = givenPendingPaper("origin2.pdf");
        givenUploadedObject(first);
        mockMvc.perform(post("/api/papers/{id}/complete", first.getId()).with(userJwt()))
                .andExpect(status().isOk());

        Paper second = paperRepository.save(Paper.register(OTHER_USER_ID, "copy2.pdf", Instant.now()));
        givenUploadedObject(second);
        doThrow(new RuntimeException("delete fail")).when(fileStorage).delete(second.getFileKey());

        mockMvc.perform(post("/api/papers/{id}/complete", second.getId()).with(otherUserJwt()))
                .andExpect(status().isOk());
        assertThat(reload(second.getId()).getDocumentId()).isNotNull();
    }

    @Test
    @DisplayName("S3에 객체가 없으면 전이 없이 409 UPLOAD_NOT_FOUND — 연결 없음 (재시도 가능)")
    void rejectsWhenObjectMissing() throws Exception {
        Paper paper = givenPendingPaper("attention-is-all-you-need.pdf");   // 업로드하지 않았다

        mockMvc.perform(post("/api/papers/{id}/complete", paper.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPLOAD_NOT_FOUND"));

        assertThat(reload(paper.getId()).getDocumentId()).isNull();
        verify(parseRequestPublisher, never()).publish(any(), any());
        assertThat(receive(parseRequestQueueUrl(), 1)).isEmpty();
    }

    @Test
    void S3_검증_checksum이_없으면_409_UPLOAD_CHECKSUM_MISSING_상태유지_발행없음() throws Exception {
        Paper paper = givenPendingPaper("no-checksum.pdf");
        givenUploadedObjectWithoutChecksum(paper);

        mockMvc.perform(post("/api/papers/{id}/complete", paper.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPLOAD_CHECKSUM_MISSING"));

        assertThat(reload(paper.getId()).getDocumentId()).isNull();
        verify(parseRequestPublisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("실제 객체가 50 MiB를 넘으면 삭제하고 413 FILE_TOO_LARGE — 파싱 요청은 발행하지 않는다")
    void rejectsAndDeletesOversizedObject() throws Exception {
        Paper paper = givenPendingPaper("attention-is-all-you-need.pdf");
        UploadedObjectMetadata oversized =
                new UploadedObjectMetadata(uploadPolicy.maxFileBytes() + 1, checksumOf(TEST_PDF_BYTES));
        doReturn(Optional.of(oversized)).when(fileStorage).head(paper.getFileKey());
        doNothing().when(fileStorage).delete(paper.getFileKey());

        mockMvc.perform(post("/api/papers/{id}/complete", paper.getId()).with(userJwt()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));

        verify(fileStorage).delete(paper.getFileKey());
        verify(parseRequestPublisher, never()).publish(any(), any());
        assertThat(reload(paper.getId()).getDocumentId()).isNull();
        assertThat(receive(parseRequestQueueUrl(), 1)).isEmpty();
    }

    @Test
    @DisplayName("없는 paperId: S3를 조회하지 않고 404 PAPER_NOT_FOUND")
    void rejectsUnknownPaperId() throws Exception {
        mockMvc.perform(post("/api/papers/{id}/complete", UUID.randomUUID()).with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));

        verify(fileStorage, never()).head(any());
    }

    @Test
    @DisplayName("남의 논문: 연결도 발행도 없이 403 FORBIDDEN")
    void rejectsOtherUsersPaper() throws Exception {
        Paper paper = givenPendingPaper("attention-is-all-you-need.pdf");
        givenUploadedObject(paper);   // 객체는 있다 — 막는 것은 소유자 검증뿐이다

        mockMvc.perform(post("/api/papers/{id}/complete", paper.getId()).with(otherUserJwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(reload(paper.getId()).getDocumentId()).isNull();
        verify(parseRequestPublisher, never()).publish(any(), any());
        assertThat(receive(parseRequestQueueUrl(), 1)).isEmpty();
    }
}
