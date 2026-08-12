package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

class PaperContentIntegrationTest extends IntegrationTest {

    /** COMPLETED + 적재까지 끝난 논문. */
    private Paper givenIngestedPaper() {
        Paper paper = givenProcessingPaper("content.pdf");
        documentTransitions.markParsed(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        documentContentIngestService.ingest(paper.getDocumentId(), givenPackageOnS3(paper.getId()));
        return reload(paper.getId());
    }

    @Test
    void 적재된_논문의_본문을_계약형으로_돌려준다() throws Exception {
        Paper paper = givenIngestedPaper();

        mockMvc.perform(get("/api/papers/{id}/content", paper.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paperId").value(paper.getId().toString()))
                .andExpect(jsonPath("$.title").value("Fixture Paper Title"))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.blocks.length()").value(10))
                .andExpect(jsonPath("$.blocks[0].blockId").value("p0000-b0000"))
                .andExpect(jsonPath("$.blocks[0].label").value("doc_title"))
                .andExpect(jsonPath("$.blocks[0].headingLevel").value(1))
                .andExpect(jsonPath("$.blocks[4].content.format").value("formula"))
                .andExpect(jsonPath("$.blocks[5].content.html").exists())
                .andExpect(jsonPath("$.blocks[6].content.assetKey").value("image_0"))
                .andExpect(jsonPath("$.assets.image_0.url").exists())
                .andExpect(jsonPath("$.assets.image_0.mediaType").value("image/jpeg"))
                .andExpect(jsonPath("$.assets.image_0.expiresAt").exists())
                .andExpect(jsonPath("$.assets.formula_0").doesNotExist());
    }

    @Test
    void 없는_논문은_404_PAPER_NOT_FOUND() throws Exception {
        mockMvc.perform(get("/api/papers/{id}/content", UUID.randomUUID()).with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
    }

    @Test
    void 남의_논문은_403_FORBIDDEN() throws Exception {
        Paper paper = givenIngestedPaper();

        mockMvc.perform(get("/api/papers/{id}/content", paper.getId()).with(otherUserJwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 파싱_미완료면_409_PAPER_NOT_READY() throws Exception {
        Paper paper = givenProcessingPaper("processing.pdf");

        mockMvc.perform(get("/api/papers/{id}/content", paper.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_READY"));
    }

    @Test
    void 완료됐지만_미적재면_409_PAPER_NOT_READY() throws Exception {
        Paper paper = givenProcessingPaper("not-ingested.pdf");
        documentTransitions.markParsed(paper.getDocumentId(), DocumentStatus.COMPLETED, null);

        mockMvc.perform(get("/api/papers/{id}/content", paper.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_READY"));
    }

    @Test
    void 만료_창_안에서는_같은_asset에_같은_URL을_재사용한다() throws Exception {
        Paper paper = givenIngestedPaper();

        String first = mockMvc.perform(get("/api/papers/{id}/content", paper.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(get("/api/papers/{id}/content", paper.getId()).with(userJwt()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String firstUrl = objectMapper.readTree(first).at("/assets/image_0/url").asText();
        String secondUrl = objectMapper.readTree(second).at("/assets/image_0/url").asText();
        assertThat(firstUrl).isNotEmpty();
        assertThat(firstUrl).isEqualTo(secondUrl);
    }

    @Test
    void 인증_없으면_401() throws Exception {
        mockMvc.perform(get("/api/papers/{id}/content", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 같은_document에_연결된_두_paper는_같은_본문을_받는다() throws Exception {
        Paper mine = givenPendingPaper("mine.pdf");
        Document document = givenLinkedDocument(mine);
        Paper theirs = paperRepository.save(Paper.register(OTHER_USER_ID, "theirs.pdf", Instant.now()));
        tx.execute(s -> paperRepository.linkDocument(theirs.getId(), document.getId(), Instant.now()));
        tx.execute(s -> documentRepository.markParsed(
                document.getId(), DocumentStatus.COMPLETED, null, Instant.now()));

        String manifestKey = givenPackageOnS3(document.getRequestPaperId());
        documentContentIngestService.ingest(document.getId(), manifestKey);

        mockMvc.perform(get("/api/papers/{id}/content", mine.getId()).with(userJwt()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/papers/{id}/content", theirs.getId()).with(otherUserJwt()))
                .andExpect(status().isOk());
    }
}
