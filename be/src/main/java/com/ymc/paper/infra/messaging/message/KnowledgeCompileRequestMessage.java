package com.ymc.paper.infra.messaging.message;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/** messaging.yml 0.3.0 `KnowledgeCompileRequest`. wire 필드는 snake_case, 필드를 늘리지 않는다. */
public record KnowledgeCompileRequestMessage(
        @JsonProperty("paper_id") UUID paperId,
        @JsonProperty("manifest_key") String manifestKey) {
}
