package com.ymc.paper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentRepository;
import com.ymc.paper.domain.DocumentStatus;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ParseResultLeadTimeTest {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final DocumentTransitions transitions = mock(DocumentTransitions.class);
    final DocumentRepository documentRepository = mock(DocumentRepository.class);
    final ParseResultService service = new ParseResultService(transitions, documentRepository,
            mock(DocumentContentIngestService.class), mock(KnowledgeCompileStarter.class),
            new ParseMetrics(registry));

    Timer leadTime(String outcome) {
        return registry.get("parse.lead.time").tag("outcome", outcome).timer();
    }

    void givenDocument(DocumentStatus status, Instant updatedAt, boolean transitions_) {
        Document document = mock(Document.class);
        when(document.getId()).thenReturn(UUID.randomUUID());
        when(document.getStatus()).thenReturn(status);
        when(document.getUpdatedAt()).thenReturn(updatedAt);
        when(documentRepository.findByRequestPaperId(any())).thenReturn(Optional.of(document));
        when(transitions.markParsedAndSettle(any(), any(), any())).thenReturn(transitions_);
    }

    @Test void firstResultRecordsElapsedSinceProcessing() {
        givenDocument(DocumentStatus.PROCESSING, Instant.now().minusSeconds(90), true);

        service.apply(UUID.randomUUID(), DocumentStatus.FAILED, "PARSE_RETRIES_EXHAUSTED", null);

        assertThat(leadTime("failed").count()).isEqualTo(1);
        assertThat(leadTime("failed").totalTime(TimeUnit.SECONDS)).isBetween(90.0, 100.0);
        assertThat(leadTime("completed").count()).isZero();
    }

    @Test void duplicateResultIsNotSampled() {
        givenDocument(DocumentStatus.COMPLETED, Instant.now().minusSeconds(90), false);

        service.apply(UUID.randomUUID(), DocumentStatus.COMPLETED, null, null);

        assertThat(leadTime("completed").count()).isZero();
    }

    @Test void resultArrivingBeforeProcessingIsNotSampled() {
        givenDocument(DocumentStatus.UPLOADED, Instant.now().minusSeconds(90), true);

        service.apply(UUID.randomUUID(), DocumentStatus.COMPLETED, null, null);

        assertThat(leadTime("completed").count()).isZero();
    }
}
