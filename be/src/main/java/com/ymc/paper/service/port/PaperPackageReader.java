package com.ymc.paper.service.port;

/**
 * S3의 파서 산출물 패키지를 읽어 계약형으로 변환한다.
 * 구현: {@code infra/parsing/S3PaperPackageReader}.
 */
public interface PaperPackageReader {

    /**
     * @param manifestKey 패키지 manifest.json의 S3 key. 마지막 '/'까지가 패키지 prefix다.
     * @throws IllegalStateException 패키지가 계약과 어긋남(파일 누락·asset 참조 불일치 등).
     *         재시도해도 같은 결과인 비복구 오류지만, 부분 적재를 남기지 않는 게 우선이라 예외로 올린다
     *         — SQS 재전달 5회 후 DLQ로 빠진다.
     */
    ParsedPaperPackage read(String manifestKey);
}
