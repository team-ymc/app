package com.ymc.paper.service;

import java.util.UUID;

/** AI가 쓰는 파싱 패키지의 S3 key 규칙. 패키지는 Document를 만든 최초 paperId 아래에 놓인다. */
final class PaperPackageKeys {

    private PaperPackageKeys() {
    }

    static String manifestKey(UUID requestPaperId) {
        return "papers/" + requestPaperId + "/manifest.json";
    }
}
