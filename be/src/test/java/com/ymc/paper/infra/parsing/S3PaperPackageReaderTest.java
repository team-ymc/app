package com.ymc.paper.infra.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.paper.service.port.FileStorage;
import com.ymc.paper.service.port.ParsedPaperPackage;
import com.ymc.paper.service.port.PresignedDownload;
import com.ymc.paper.service.port.PresignedUpload;
import com.ymc.paper.service.port.UploadedObjectMetadata;

@ExtendWith(OutputCaptureExtension.class)
class S3PaperPackageReaderTest {

    /** fileKey의 prefix로 어느 fixtures/paper-package* 디렉터리를 읽을지 정한다. */
    private static final Map<String, String> PREFIX_TO_FIXTURE = new LinkedHashMap<>();

    static {
        PREFIX_TO_FIXTURE.put("papers/broken/", "paper-package-broken");
        PREFIX_TO_FIXTURE.put("papers/translated/", "paper-package-translated");
        PREFIX_TO_FIXTURE.put("papers/badlang/", "paper-package-badlang");
        PREFIX_TO_FIXTURE.put("papers/p1/", "paper-package");
    }

    /** S3 대신 클래스패스 fixtures/paper-package* 디렉터리를 읽는 가짜 저장소. */
    private static final FileStorage FAKE_STORAGE = new FileStorage() {
        @Override
        public String readUtf8(String fileKey) {
            String prefix = PREFIX_TO_FIXTURE.keySet().stream()
                    .filter(fileKey::startsWith)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("매핑되지 않은 fileKey: " + fileKey));
            String resource = "/fixtures/" + PREFIX_TO_FIXTURE.get(prefix) + "/" + fileKey.substring(prefix.length());
            try (InputStream in = S3PaperPackageReaderTest.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("픽스처 없음: " + resource);
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public PresignedUpload presignUpload(
                String fileKey, String contentType, long contentLength, String checksumSha256) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PresignedDownload presignDownload(String fileKey, String filename) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PresignedDownload presignAssetGet(String fileKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UploadedObjectMetadata> head(String fileKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String fileKey) {
            throw new UnsupportedOperationException();
        }
    };

    private final S3PaperPackageReader reader =
            new S3PaperPackageReader(FAKE_STORAGE, new ObjectMapper());

    @Test
    void 패키지를_읽어_계약형으로_변환한다() {
        ParsedPaperPackage pkg = reader.read("papers/p1/manifest.json");

        assertThat(pkg.title()).isEqualTo("Fixture Paper Title");
        assertThat(pkg.schemaVersion()).isEqualTo(1);
        assertThat(pkg.blocks()).hasSize(10);
        assertThat(pkg.blocks()).extracting(ParsedPaperPackage.Block::globalOrder)
                .isSorted();
    }

    @Test
    void 수식과_표는_asset_파일_내용이_인라인된다() {
        ParsedPaperPackage pkg = reader.read("papers/p1/manifest.json");

        ParsedPaperPackage.Block formula = blockById(pkg, "p0001-b0000");
        assertThat(formula.content().get("format").asText()).isEqualTo("formula");
        assertThat(formula.content().get("tex").asText()).contains("softmax");
        assertThat(formula.content().has("assetKey")).isFalse();

        ParsedPaperPackage.Block table = blockById(pkg, "p0001-b0001");
        assertThat(table.content().get("format").asText()).isEqualTo("table");
        assertThat(table.content().get("html").asText()).contains("<table>");
    }

    @Test
    void 이미지와_차트는_assetKey_참조로_남고_asset_목록에_s3Key가_잡힌다() {
        ParsedPaperPackage pkg = reader.read("papers/p1/manifest.json");

        ParsedPaperPackage.Block image = blockById(pkg, "p0001-b0002");
        assertThat(image.content().get("format").asText()).isEqualTo("image");
        assertThat(image.content().get("assetKey").asText()).isEqualTo("image_0");

        assertThat(pkg.assets()).containsExactlyInAnyOrder(
                new ParsedPaperPackage.Asset("image_0", "papers/p1/assets/images/image_0.jpg", "image/jpeg"),
                new ParsedPaperPackage.Asset("image_1", "papers/p1/assets/images/image_1.png", "image/png"));
    }

    @Test
    void 제목_계열_블록은_headingLevel과_sectionPath를_보존한다() {
        ParsedPaperPackage pkg = reader.read("papers/p1/manifest.json");

        ParsedPaperPackage.Block title = blockById(pkg, "p0000-b0000");
        assertThat(title.label()).isEqualTo("doc_title");
        assertThat(title.headingLevel()).isEqualTo(1);

        ParsedPaperPackage.Block section = blockById(pkg, "p0000-b0001");
        assertThat(section.headingLevel()).isEqualTo(2);
        assertThat(section.sectionPath()).containsExactly("p0000-b0000", "p0000-b0001");
    }

    @Test
    void 레지스트리에_없는_asset_참조는_예외다() {
        assertThatThrownBy(() -> reader.read("papers/broken/manifest.json"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void manifest에_artifacts가_없으면_예외다() {
        Map<String, String> files = new HashMap<>();
        files.put("papers/x/manifest.json", """
                {
                  "manifest_version": 1,
                  "document_id": "x"
                }
                """);
        S3PaperPackageReader reader = new S3PaperPackageReader(mapStorage(files), new ObjectMapper());

        assertThatThrownBy(() -> reader.read("papers/x/manifest.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("artifacts");
    }

    @Test
    void 블록에_block_content가_없으면_예외다() {
        Map<String, String> files = new HashMap<>();
        files.put("papers/y/manifest.json", """
                {
                  "manifest_version": 1,
                  "document_id": "y",
                  "artifacts": {
                    "frontend_document": {"path": "frontend/document.json"},
                    "structure_document": {"path": "structure/document.json"}
                  }
                }
                """);
        files.put("papers/y/frontend/document.json", """
                {
                  "schema_version": 1,
                  "blocks": [
                    {"block_id": "b0", "global_block_order": 0, "block_label": "text", "section_path": []}
                  ]
                }
                """);
        files.put("papers/y/structure/document.json", """
                {"assets": {}}
                """);
        S3PaperPackageReader reader = new S3PaperPackageReader(mapStorage(files), new ObjectMapper());

        assertThatThrownBy(() -> reader.read("papers/y/manifest.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("b0");
    }

    @Test
    void 번역이_있는_패키지는_sourceLanguage와_textKor를_담고_WARN이_없다(CapturedOutput output) {
        ParsedPaperPackage pkg = reader.read("papers/translated/manifest.json");

        assertThat(pkg.sourceLanguage()).isEqualTo("en");

        ParsedPaperPackage.Block abstractBlock = blockById(pkg, "p0000-b0001");
        assertThat(abstractBlock.content().get("textKor").asText()).contains("새로운 구조");
        ParsedPaperPackage.Block textBlock = blockById(pkg, "p0000-b0002");
        assertThat(textBlock.content().get("textKor").asText()).contains("영어로 된 본문");

        ParsedPaperPackage.Block reference = blockById(pkg, "p0000-b0003");
        assertThat(reference.content().has("textKor")).isFalse();
        ParsedPaperPackage.Block image = blockById(pkg, "p0000-b0004");
        assertThat(image.content().has("textKor")).isFalse();

        assertThat(output.getOut()).doesNotContain("WARN");
    }

    @Test
    void 번역_도입_전_패키지는_sourceLanguage가_null이고_textKor가_없으며_WARN이_없다(CapturedOutput output) {
        ParsedPaperPackage pkg = reader.read("papers/p1/manifest.json");

        assertThat(pkg.sourceLanguage()).isNull();
        assertThat(pkg.blocks()).allSatisfy(b -> assertThat(b.content().has("textKor")).isFalse());
        assertThat(output.getOut()).doesNotContain("WARN");
    }

    @Test
    void frontend만_형식이_어긋나면_manifest값으로_폴백하고_정합성_A와_C_WARN을_남긴다(CapturedOutput output) {
        ParsedPaperPackage pkg = reader.read("papers/badlang/manifest.json");

        assertThat(pkg.sourceLanguage()).isEqualTo("en");
        assertThat(output.getOut()).contains("unknown");
        assertThat(output.getOut()).contains("번역이 없는 블록");
        assertThat(output.getOut()).contains("참고문헌");
        // 한쪽만 유효하므로(정규화 후 비교) 불일치 WARN은 뜨지 않는다.
        assertThat(output.getOut()).doesNotContain("불일치");
    }

    @Test
    void manifest와_frontend가_둘다_유효하고_다르면_불일치_WARN을_남기고_frontend값을_쓴다(CapturedOutput output) {
        Map<String, String> files = new HashMap<>();
        files.put("papers/langmismatch/manifest.json", """
                {
                  "manifest_version": 1,
                  "document_id": "langmismatch",
                  "source_language": "ko",
                  "artifacts": {
                    "frontend_document": {"path": "frontend/document.json"},
                    "structure_document": {"path": "structure/document.json"}
                  }
                }
                """);
        files.put("papers/langmismatch/frontend/document.json", """
                {"schema_version":1,"source_language":"en","blocks":[
                  {"block_id":"b0","global_block_order":0,"block_label":"text","heading_level":null,"section_path":[],
                   "block_content":{"format":"text","text":"본문","text_kor":"번역"}}
                ]}
                """);
        files.put("papers/langmismatch/structure/document.json", """
                {"assets": {}}
                """);
        S3PaperPackageReader reader = new S3PaperPackageReader(mapStorage(files), new ObjectMapper());

        ParsedPaperPackage pkg = reader.read("papers/langmismatch/manifest.json");

        assertThat(pkg.sourceLanguage()).isEqualTo("en");
        assertThat(output.getOut()).contains("불일치");
    }

    @ParameterizedTest
    @ValueSource(strings = {"zz", "EN", "en-US", ""})
    void 형식이_아닌_source_language는_null로_적재되고_WARN을_남긴다(String invalid, CapturedOutput output) {
        Map<String, String> files = sourceLanguageOnlyPackage("z1", invalid);
        S3PaperPackageReader reader = new S3PaperPackageReader(mapStorage(files), new ObjectMapper());

        ParsedPaperPackage pkg = reader.read("papers/z1/manifest.json");

        assertThat(pkg.sourceLanguage()).isNull();
        assertThat(output.getOut()).contains("papers/z1/manifest.json");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ko", "und"})
    void 유효한_source_language는_그대로_적재되고_WARN이_없다(String valid, CapturedOutput output) {
        Map<String, String> files = sourceLanguageOnlyPackage("z2", valid);
        S3PaperPackageReader reader = new S3PaperPackageReader(mapStorage(files), new ObjectMapper());

        ParsedPaperPackage pkg = reader.read("papers/z2/manifest.json");

        assertThat(pkg.sourceLanguage()).isEqualTo(valid);
        assertThat(output.getOut()).doesNotContain("WARN");
    }

    @Test
    void en이_아닌데_text_kor가_있으면_정합성_B_WARN을_남긴다(CapturedOutput output) {
        Map<String, String> files = sourceLanguageOnlyPackage("z3", "ko");
        files.put("papers/z3/frontend/document.json", """
                {"schema_version":1,"source_language":"ko","blocks":[
                  {"block_id":"b0","global_block_order":0,"block_label":"text","heading_level":null,"section_path":[],
                   "block_content":{"format":"text","text":"본문","text_kor":"번역되면 안 되는 값"}}
                ]}
                """);
        S3PaperPackageReader reader = new S3PaperPackageReader(mapStorage(files), new ObjectMapper());

        ParsedPaperPackage pkg = reader.read("papers/z3/manifest.json");

        assertThat(pkg.sourceLanguage()).isEqualTo("ko");
        assertThat(output.getOut()).contains("en이 아닌데");
    }

    /** manifest·frontend 모두 source_language만 다르고 나머지는 최소인 패키지. */
    private static Map<String, String> sourceLanguageOnlyPackage(String paperId, String frontendSourceLanguage) {
        Map<String, String> files = new HashMap<>();
        files.put("papers/" + paperId + "/manifest.json", """
                {
                  "manifest_version": 1,
                  "document_id": "%s",
                  "artifacts": {
                    "frontend_document": {"path": "frontend/document.json"},
                    "structure_document": {"path": "structure/document.json"}
                  }
                }
                """.formatted(paperId));
        files.put("papers/" + paperId + "/frontend/document.json", """
                {"schema_version":1,"source_language":"%s","blocks":[
                  {"block_id":"b0","global_block_order":0,"block_label":"text","heading_level":null,"section_path":[],
                   "block_content":{"format":"text","text":"본문"}}
                ]}
                """.formatted(frontendSourceLanguage));
        files.put("papers/" + paperId + "/structure/document.json", """
                {"assets": {}}
                """);
        return files;
    }

    private static ParsedPaperPackage.Block blockById(ParsedPaperPackage pkg, String blockId) {
        return pkg.blocks().stream().filter(b -> b.blockId().equals(blockId)).findFirst().orElseThrow();
    }

    /** 클래스패스 픽스처 없이 케이스별 JSON을 직접 주입하는 in-memory 가짜 저장소. */
    private static FileStorage mapStorage(Map<String, String> files) {
        return new FileStorage() {
            @Override
            public String readUtf8(String fileKey) {
                String content = files.get(fileKey);
                if (content == null) {
                    throw new IllegalStateException("정의되지 않은 키: " + fileKey);
                }
                return content;
            }

            @Override
            public PresignedUpload presignUpload(
                    String fileKey, String contentType, long contentLength, String checksumSha256) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PresignedDownload presignDownload(String fileKey, String filename) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PresignedDownload presignAssetGet(String fileKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<UploadedObjectMetadata> head(String fileKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void delete(String fileKey) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
