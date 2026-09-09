package com.ymc.paper.infra.parsing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
import lombok.extern.slf4j.Slf4j;

/**
 * 파서 산출물(snake_case, ai repo S3_BUCKET_STRUCTURE) → 계약형(camelCase) 변환.
 *
 * <p>파서 JSON은 여분 필드(bbox·page_index 등)가 많고 스키마가 진화하므로 record마다
 * {@code @JsonIgnoreProperties}로 관대하게 받는다 — 전역 fail-on-unknown-properties를 우회해야 한다.
 *
 * <p>asset 경로의 SSOT는 structure/document.json의 assets 레지스트리다
 * (manifest.assets.registry = "structure_document"). frontend 문서의 assets 맵에는 경로가 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S3PaperPackageReader implements PaperPackageReader {

    private static final Set<String> ISO_LANGUAGES = Set.of(Locale.getISOLanguages());
    private static final Set<String> TRANSLATION_EXCLUDED_LABELS = Set.of("reference", "reference_content");

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

        String sourceLanguage = resolveSourceLanguage(manifest, frontend, manifestKey);

        List<ParsedPaperPackage.Block> blocks = new ArrayList<>();
        List<ParsedPaperPackage.Asset> assets = new ArrayList<>();
        String title = null;
        int missingTranslationCount = 0;
        boolean nonEnTranslatedFound = false;
        boolean ineligibleTranslatedFound = false;

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

            boolean isTextFormat = "text".equals(block.blockContent().path("format").asText());
            boolean isEligible = isTextFormat && !TRANSLATION_EXCLUDED_LABELS.contains(block.blockLabel());
            boolean hasTextKor = hasTextKor(block);
            if (isEligible) {
                if ("en".equals(sourceLanguage)) {
                    if (!hasTextKor) {
                        missingTranslationCount++;
                    }
                } else if (hasTextKor) {
                    nonEnTranslatedFound = true;
                }
            } else if (hasTextKor) {
                ineligibleTranslatedFound = true;
            }
        }

        if ("en".equals(sourceLanguage) && missingTranslationCount > 0) {
            log.warn("source_language=en인데 번역이 없는 블록이 있습니다: 개수={}, manifestKey={}",
                    missingTranslationCount, manifestKey);
        }
        if (nonEnTranslatedFound) {
            log.warn("source_language가 en이 아닌데 text_kor가 있는 블록이 있습니다: manifestKey={}", manifestKey);
        }
        if (ineligibleTranslatedFound) {
            log.warn("참고문헌 또는 텍스트가 아닌 블록에 text_kor가 있습니다: manifestKey={}", manifestKey);
        }

        return new ParsedPaperPackage(
                title, frontend.schemaVersion(), sourceLanguage, List.copyOf(blocks), List.copyOf(assets));
    }

    /** manifest·frontend의 source_language를 정규화 후 병합한다. 둘 다 유효하고 다르면 WARN 후 frontend 값을 쓴다. */
    private String resolveSourceLanguage(Manifest manifest, FrontendDocument frontend, String manifestKey) {
        String manifestNormalized = normalizeLanguage(manifest.sourceLanguage(), "manifest.json", manifestKey);
        String frontendNormalized = normalizeLanguage(frontend.sourceLanguage(), "frontend/document.json", manifestKey);

        if (manifestNormalized != null && frontendNormalized != null && !manifestNormalized.equals(frontendNormalized)) {
            log.warn("manifest·frontend의 source_language 불일치, frontend 값 사용: manifest={}, frontend={}, "
                    + "manifestKey={}", manifestNormalized, frontendNormalized, manifestKey);
            return frontendNormalized;
        }
        return frontendNormalized != null ? frontendNormalized : manifestNormalized;
    }

    /** en / ISO 639-1 두 글자 소문자 / und 만 유효하다. 그 외는 WARN 후 null. */
    private String normalizeLanguage(String raw, String where, String manifestKey) {
        if (raw == null) {
            return null;
        }
        if ("und".equals(raw) || ISO_LANGUAGES.contains(raw)) {
            return raw;
        }
        log.warn("source_language 형식이 아닙니다, null로 적재: value={}, where={}, manifestKey={}",
                raw, where, manifestKey);
        return null;
    }

    private boolean hasTextKor(FrontendBlock block) {
        JsonNode textKor = block.blockContent().path("text_kor");
        return textKor.isTextual() && !textKor.asText().isEmpty();
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
        ObjectNode content = objectMapper.createObjectNode().put("format", "text").put("text", text);
        if (hasTextKor(block)) {
            content.put("textKor", block.blockContent().path("text_kor").asText());
        }
        return content;
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
    record Manifest(@JsonProperty("source_language") String sourceLanguage, Artifacts artifacts) {
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
            @JsonProperty("source_language") String sourceLanguage,
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
