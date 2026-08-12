-- document 테이블 — 같은 파일 바이트(SHA-256)의 공유 단위. 여러 paper가 참조한다.
--
-- prod는 validate라 배포 전에 이 스크립트를 운영 DB에 반영해야 한다.
-- ⚠ Document 엔티티가 바뀌면 이 파일도 함께 고칠 것.

create table document (
    id               uuid                        not null,
    checksum_sha256  varchar(44)                 not null,
    file_key         varchar(255)                not null,
    status           varchar(32)                 not null
        check (status in ('UPLOADED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    error_code       varchar(255),
    request_paper_id uuid                        not null,
    created_at       timestamp(6) with time zone not null,
    updated_at       timestamp(6) with time zone not null,

    primary key (id),

    -- 동일 파일 판정의 근거. 동시 complete의 생성 경쟁은 이 제약 + ON CONFLICT로 1명만 이긴다.
    constraint uk_document_checksum unique (checksum_sha256)
);

-- AI 결과(paper_id = request_paper_id) 역조회 경로.
create unique index ux_document_request_paper on document (request_paper_id);
