package com.ymc.paper.domain;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.ymc.support.IntegrationTest;

/** V3는 다시 실행해도 안전하게 썼다. 테스트는 준비한 행 위에 같은 파일을 한 번 더 실행해 채우기를 확인한다. */
class FailedPaperMigrationIntegrationTest extends IntegrationTest {

    @Autowired
    DataSource dataSource;

    private void runMigration() {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__failed_paper_retry.sql"))
                .execute(dataSource);
    }

    @Test
    @DisplayName("이미 실패한 Document에 연결된 Paper는 failed_at과 실패 코드가 채워진다")
    void backfillsFailedPapers() {
        Paper failed = givenProcessingPaper("failed.pdf");
        jdbcTemplate.update(
                "update document set status = 'FAILED', error_code = 'PARSE_FAILED' where id = ?",
                failed.getDocumentId());
        jdbcTemplate.update("update paper set failed_at = null, failed_error_code = null");
        Paper completed = givenProcessingPaper("completed.pdf");
        jdbcTemplate.update("update document set status = 'COMPLETED' where id = ?",
                completed.getDocumentId());

        runMigration();

        assertThat(reload(failed.getId()).getFailedAt()).isNotNull();
        assertThat(reload(failed.getId()).getFailedErrorCode()).isEqualTo("PARSE_FAILED");
        assertThat(reload(completed.getId()).getFailedAt()).isNull();
    }

    @Test
    @DisplayName("이미 한 번 돌았던 Document는 시도 횟수를 1로 맞추고 시작 전 Document는 0으로 둔다")
    void alignsAttempts() {
        Paper processed = givenProcessingPaper("processed.pdf");
        jdbcTemplate.update("update document set compile_status = 'FAILED' where id = ?",
                processed.getDocumentId());
        Document uploaded = givenLinkedDocument(givenPendingPaper("waiting.pdf"));
        jdbcTemplate.update("update document set attempt = 0, compile_attempt = 0");

        runMigration();

        Document ran = documentRepository.findById(processed.getDocumentId()).orElseThrow();
        assertThat(ran.getAttempt()).isEqualTo(1);
        assertThat(ran.getCompileAttempt()).isEqualTo(1);
        Document fresh = documentRepository.findById(uploaded.getId()).orElseThrow();
        assertThat(fresh.getAttempt()).isZero();
        assertThat(fresh.getCompileAttempt()).isZero();
    }
}
