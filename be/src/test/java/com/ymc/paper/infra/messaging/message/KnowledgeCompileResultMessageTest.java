package com.ymc.paper.infra.messaging.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.paper.domain.CompileStatus;

class KnowledgeCompileResultMessageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID PAPER_ID = UUID.randomUUID();

    private KnowledgeCompileResultMessage parse(String json) throws Exception {
        return MAPPER.readValue(json, KnowledgeCompileResultMessage.class);
    }

    @Test
    void completed는_manifest_key가_있어야_유효하다() throws Exception {
        KnowledgeCompileResultMessage ok = parse("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"papers/x/manifest.json"}
                """.formatted(PAPER_ID));
        assertThat(ok.contractViolation()).isEmpty();
        assertThat(ok.terminalStatus()).isEqualTo(CompileStatus.COMPLETED);

        KnowledgeCompileResultMessage missing = parse("""
                {"paper_id":"%s","status":"completed","message":"ok"}
                """.formatted(PAPER_ID));
        assertThat(missing.contractViolation().orElseThrow()).contains("manifest_key");
    }

    @Test
    void failed는_error_code가_있어야_유효하고_코드는_그대로_보존한다() throws Exception {
        KnowledgeCompileResultMessage ok = parse("""
                {"paper_id":"%s","status":"failed","error":{"code":"PARSED_DOCUMENT_INVALID","message":"x"}}
                """.formatted(PAPER_ID));
        assertThat(ok.contractViolation()).isEmpty();
        assertThat(ok.terminalStatus()).isEqualTo(CompileStatus.FAILED);
        assertThat(ok.errorCode()).isEqualTo("PARSED_DOCUMENT_INVALID");

        KnowledgeCompileResultMessage missing = parse("""
                {"paper_id":"%s","status":"failed"}
                """.formatted(PAPER_ID));
        assertThat(missing.contractViolation().orElseThrow()).contains("error.code");
    }

    @Test
    void 모르는_error_code도_위반이_아니라_그대로_보존한다() throws Exception {
        KnowledgeCompileResultMessage m = parse("""
                {"paper_id":"%s","status":"failed","error":{"code":"SOMETHING_NEW","message":"x"}}
                """.formatted(PAPER_ID));
        assertThat(m.contractViolation()).isEmpty();
        assertThat(m.errorCode()).isEqualTo("SOMETHING_NEW");
    }

    @Test
    void paper_id_status_누락과_모르는_status는_위반이다() throws Exception {
        assertThat(parse("{\"status\":\"completed\",\"manifest_key\":\"k\"}").contractViolation().orElseThrow())
                .contains("paper_id");
        assertThat(parse("{\"paper_id\":\"%s\"}".formatted(PAPER_ID)).contractViolation().orElseThrow())
                .contains("status");
        assertThat(parse("{\"paper_id\":\"%s\",\"status\":\"running\"}".formatted(PAPER_ID)).contractViolation()
                        .orElseThrow())
                .contains("running");
    }

    @Test
    void 모르는_필드는_무시한다() throws Exception {
        KnowledgeCompileResultMessage m = parse("""
                {"paper_id":"%s","status":"completed","message":"ok","manifest_key":"k","extra":{"a":1}}
                """.formatted(PAPER_ID));
        assertThat(m.contractViolation()).isEmpty();
    }
}
