package com.ymc.paper.infra.parsing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ymc.paper.service.port.FileStorage;
import com.ymc.paper.service.port.PaperPackageReader;
import com.ymc.paper.service.port.ParsedPaperPackage;

import lombok.RequiredArgsConstructor;

/**
 * 파서 산출물(snake_case, ai repo S3_BUCKET_STRUCTURE) → 계약형(camelCase) 변환.
 *
 * <p>파서 JSON은 여분 필드(bbox·page_index 등)가 많고 스키마가 진화하므로 record마다
 * {@code @JsonIgnoreProperties}로 관대하게 받는다 — 전역 fail-on-unknown-properties를 우회해야 한다.
 *
 * <p>asset 경로의 SSOT는 structure/document.json의 assets 레지스트리다
 * (manifest.assets.registry = "structure_document"). frontend 문서의 assets 맵에는 경로가 없다.
 */
@Component
@RequiredArgsConstructor
public class S3PaperPackageReader implements PaperPackageReader {

    private final FileStorage fileStorage;
    private final ObjectMapper objectMapper;

    @Override
    public ParsedPaperPackage read(String manifestKey) {
        String prefix = packagePrefix(manifestKey);

        Manifest manifest = parse(fileStorage.readUtf8(manifestKey), Manifest.class, manifestKey);
        if (manifest.artifacts() == null) {
            throw new IllegalStateException("manifest.artifacts가 없습니다: " + manifestKey);
        }
        String frontendKey = prefix + required(manifest.artifacts().frontendDocument(), "frontend_document").path();
        String structureKey = prefix + required(manifest.artifacts().structureDocument(), "structure_document").path();

        FrontendDocument frontend = parse(fileStorage.readUtf8(frontendKey), FrontendDocument.class, frontendKey);
        StructureDocument structure = parse(fileStorage.readUtf8(structureKey), StructureDocument.class, structureKey);

        List<ParsedPaperPackage.Block> blocks = new ArrayList<>();
        List<ParsedPaperPackage.Asset> assets = new ArrayList<>();
        String title = null;

        for (FrontendBlock block : frontend.blocks()) {
            JsonNode content = resolveContent(block, structure.assets(), prefix, assets);
            if (title == null && "doc_title".equals(block.blockLabel())) {
                title = block.blockContent().path("text").asText(null);
            }
            blocks.add(new ParsedPaperPackage.Block(
                    block.blockId(),
                    block.globalBlockOrder(),
                    block.blockLabel(),
                    block.headingLevel(),
                    block.sectionPath() == null ? List.of() : block.sectionPath(),
                    content));
        }
        return new ParsedPaperPackage(title, frontend.schemaVersion(), List.copyOf(blocks), List.copyOf(assets));
    }

    /** block_content.format 기준으로 계약 content를 만든다. label이 아니라 format이다 — chart도 format은 image. */
    private JsonNode resolveContent(FrontendBlock block, Map<String, RegisteredAsset> registry,
            String prefix, List<ParsedPaperPackage.Asset> assets) {
        if (block.blockContent() == null) {
            throw new IllegalStateException("block_content가 없습니다: blockId=" + block.blockId());
        }
        String format = block.blockContent().path("format").asText();
        return switch (format) {
            case "text" -> textContent(block);
            case "formula" -> inlined(block, registry, prefix, "formula", "tex");
            case "table" -> inlined(block, registry, prefix, "table", "html");
            case "image" -> imageContent(block, registry, prefix, assets);
            default -> throw new IllegalStateException(
                    "알 수 없는 block_content.format: %s (blockId=%s)".formatted(format, block.blockId()));
        };
    }

    private JsonNode textContent(FrontendBlock block) {
        String text = block.blockContent().path("text").asText(null);
        if (text == null) {
            throw new IllegalStateException("text 블록에 text가 없습니다: blockId=" + block.blockId());
        }
        return objectMapper.createObjectNode().put("format", "text").put("text", text);
    }

    private JsonNode inlined(FrontendBlock block, Map<String, RegisteredAsset> registry,
            String prefix, String format, String field) {
        RegisteredAsset asset = registeredAsset(block, registry);
        String body = fileStorage.readUtf8(prefix + asset.path());
        return objectMapper.createObjectNode().put("format", format).put(field, body);
    }

    private JsonNode imageContent(FrontendBlock block, Map<String, RegisteredAsset> registry,
            String prefix, List<ParsedPaperPackage.Asset> assets) {
        String assetKey = block.blockContent().path("asset_key").asText();
        RegisteredAsset asset = registeredAsset(block, registry);
        assets.add(new ParsedPaperPackage.Asset(assetKey, prefix + asset.path(), asset.mediaType()));
        ObjectNode content = objectMapper.createObjectNode();
        return content.put("format", "image").put("assetKey", assetKey);
    }

    private RegisteredAsset registeredAsset(FrontendBlock block, Map<String, RegisteredAsset> registry) {
        String assetKey = block.blockContent().path("asset_key").asText(null);
        if (assetKey == null) {
            throw new IllegalStateException("asset_key가 없습니다: blockId=" + block.blockId());
        }
        RegisteredAsset asset = registry.get(assetKey);
        if (asset == null || asset.path() == null) {
            throw new IllegalStateException(
                    "레지스트리에 없는 asset 참조: %s (blockId=%s)".formatted(assetKey, block.blockId()));
        }
        return asset;
    }

    private static String packagePrefix(String manifestKey) {
        int lastSlash = manifestKey.lastIndexOf('/');
        if (lastSlash < 0) {
            throw new IllegalStateException("패키지 prefix를 만들 수 없는 manifestKey: " + manifestKey);
        }
        return manifestKey.substring(0, lastSlash + 1);
    }

    private <T> T parse(String json, Class<T> type, String sourceKey) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("파서 산출물 역직렬화 실패: " + sourceKey, e);
        }
    }

    private static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, () -> "manifest.artifacts." + name + "가 없습니다.");
    }

    // ---- 파서 JSON 대응 record (snake_case, lenient) ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Manifest(Artifacts artifacts) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Artifacts(
                @JsonProperty("frontend_document") Artifact frontendDocument,
                @JsonProperty("structure_document") Artifact structureDocument) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Artifact(String path) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FrontendDocument(
            @JsonProperty("schema_version") int schemaVersion,
            List<FrontendBlock> blocks) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FrontendBlock(
            @JsonProperty("block_id") String blockId,
            @JsonProperty("global_block_order") int globalBlockOrder,
            @JsonProperty("block_label") String blockLabel,
            @JsonProperty("heading_level") Integer headingLevel,
            @JsonProperty("section_path") List<String> sectionPath,
            @JsonProperty("block_content") JsonNode blockContent) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StructureDocument(Map<String, RegisteredAsset> assets) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegisteredAsset(String path, @JsonProperty("media_type") String mediaType) {
    }
}
