package com.ymc.paper.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ymc.support.IntegrationTest;

class DocumentPersistenceIntegrationTest extends IntegrationTest {

    private UUID insert(String checksum, UUID requestPaperId) {
        UUID id = UUID.randomUUID();
        Integer inserted = tx.execute(s -> documentRepository.insertIfAbsent(
                id, checksum, "uploads/" + requestPaperId + "/original.pdf", requestPaperId, Instant.now()));
        return inserted == 1 ? id : null;
    }

    @Test
    void 같은_checksum은_한_번만_insert되고_두번째는_0row다() {
        String checksum = randomChecksum();
        assertThat(insert(checksum, UUID.randomUUID())).isNotNull();
        assertThat(insert(checksum, UUID.randomUUID())).isNull();   // ON CONFLICT DO NOTHING
        assertThat(documentRepository.findByChecksumSha256(checksum)).isPresent();
    }

    @Test
    void 같은_requestPaperId도_한_번만_insert되고_두번째는_0row다() {
        UUID requestPaperId = UUID.randomUUID();
        assertThat(insert(randomChecksum(), requestPaperId)).isNotNull();
        assertThat(insert(randomChecksum(), requestPaperId)).isNull();
        assertThat(documentRepository.findByRequestPaperId(requestPaperId)).isPresent();
        assertThat(documentRepository.count()).isEqualTo(1);
    }

    @Test
    void 선점_CAS는_UPLOADED에서만_1row다() {
        String checksum = randomChecksum();
        UUID id = insert(checksum, UUID.randomUUID());
        assertThat(tx.<Integer>execute(s -> documentRepository.markProcessing(id, Instant.now()))).isEqualTo(1);
        assertThat(tx.<Integer>execute(s -> documentRepository.markProcessing(id, Instant.now()))).isEqualTo(0);
    }

    @Test
    void 반납은_PROCESSING을_UPLOADED로_되돌린다() {
        UUID id = insert(randomChecksum(), UUID.randomUUID());
        tx.execute(s -> documentRepository.markProcessing(id, Instant.now()));
        assertThat(tx.<Integer>execute(s -> documentRepository.revertToUploaded(id, Instant.now()))).isEqualTo(1);
        assertThat(documentRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(DocumentStatus.UPLOADED);
    }

    @Test
    void 결과_전이는_UPLOADED에서도_PROCESSING에서도_1row고_중복은_0row다() {
        // UPLOADED 경로 검증
        UUID uploadedDoc = insert(randomChecksum(), UUID.randomUUID());
        assertThat(tx.<Integer>execute(s -> documentRepository.markParsed(
                uploadedDoc, DocumentStatus.COMPLETED, null, Instant.now()))).isEqualTo(1);
        assertThat(tx.<Integer>execute(s -> documentRepository.markParsed(
                uploadedDoc, DocumentStatus.FAILED, "X", Instant.now()))).isEqualTo(0);

        // PROCESSING 경로 검증
        UUID processingDoc = insert(randomChecksum(), UUID.randomUUID());
        assertThat(tx.<Integer>execute(s -> documentRepository.markProcessing(processingDoc, Instant.now())))
                .isEqualTo(1);
        assertThat(tx.<Integer>execute(s -> documentRepository.markParsed(
                processingDoc, DocumentStatus.FAILED, "PARSE_RETRIES_EXHAUSTED", Instant.now()))).isEqualTo(1);
        assertThat(tx.<Integer>execute(s -> documentRepository.markParsed(
                processingDoc, DocumentStatus.COMPLETED, null, Instant.now()))).isEqualTo(0);
    }

    @Test
    void 연결_CAS는_document_id가_null일_때만_1row고_updated_at을_갱신한다() {
        var paper = givenPendingPaper("link.pdf");
        Instant before = paper.getUpdatedAt();
        UUID docId = insert(randomChecksum(), paper.getId());
        assertThat(tx.<Integer>execute(s -> paperRepository.linkDocument(
                paper.getId(), docId, Instant.now()))).isEqualTo(1);
        assertThat(tx.<Integer>execute(s -> paperRepository.linkDocument(
                paper.getId(), docId, Instant.now()))).isEqualTo(0);
        var linked = reload(paper.getId());
        assertThat(linked.getDocumentId()).isEqualTo(docId);
        assertThat(linked.getUpdatedAt()).isAfterOrEqualTo(before);
    }
}
