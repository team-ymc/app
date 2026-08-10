package com.ymc.paper.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/** PDF 원본 업로드 정책. FE 안내와 무관하게 presign 서명과 complete 검증이 이 값을 강제한다. */
@ConfigurationProperties(prefix = "paper.upload")
public record PaperUploadPolicy(@DefaultValue("50MB") DataSize maxFileSize) {

    public long maxFileBytes() {
        return maxFileSize.toBytes();
    }

    public boolean exceedsLimit(long bytes) {
        return bytes > maxFileBytes();
    }

    public String tooLargeMessage() {
        return "PDF 파일은 최대 %d MiB까지 업로드할 수 있습니다.".formatted(maxFileSize.toMegabytes());
    }
}
