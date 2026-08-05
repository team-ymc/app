package com.ymc.paper.infra.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.paper.service.port.FileStorage;
import com.ymc.paper.service.port.ParsedPaperPackage;
import com.ymc.paper.service.port.PresignedDownload;
import com.ymc.paper.service.port.PresignedUpload;

class S3PaperPackageReaderTest {

    /** S3 대신 클래스패스 fixtures/paper-package/ (또는 broken 변형)를 읽는 가짜 저장소. */
    private static final FileStorage FAKE_STORAGE = new FileStorage() {
        @Override
        public String readUtf8(String fileKey) {
            String resource = fileKey.startsWith("papers/broken/")
                    ? "/fixtures/paper-package-broken/" + fileKey.substring("papers/broken/".length())
                    : "/fixtures/paper-package/" + fileKey.substring("papers/p1/".length());
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
        public PresignedUpload presignUpload(String fileKey, String contentType) {
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
        public boolean exists(String fileKey) {
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
            public PresignedUpload presignUpload(String fileKey, String contentType) {
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
            public boolean exists(String fileKey) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
