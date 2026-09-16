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
    -- 번역 상태 계산용. source_language는 적재 때 document_content에서 복제한다.
    source_language    varchar(8),
    compile_status     varchar(32)
        check (compile_status in ('REQUESTED', 'COMPLETED', 'FAILED')),
    compile_error_code varchar(255),
    request_paper_id uuid                        not null,
    created_at       timestamp(6) with time zone not null,
    updated_at       timestamp(6) with time zone not null,

    primary key (id),

    -- 동일 파일 판정의 근거. 동시 complete의 생성 경쟁은 이 제약 + ON CONFLICT로 1명만 이긴다.
    constraint uk_document_checksum unique (checksum_sha256)
);

-- AI 결과(paper_id = request_paper_id) 역조회 경로.
create unique index ux_document_request_paper on document (request_paper_id);

-- 기존 DB 반영 (prod 첫 배포 전 필수):
-- alter table document add column if not exists source_language varchar(8);
-- alter table document add column if not exists compile_status varchar(32)
--     check (compile_status in ('REQUESTED', 'COMPLETED', 'FAILED'));
-- alter table document add column if not exists compile_error_code varchar(255);

-- dev 1회 SQL (배포 뒤). 파서 시절 번역이 이미 블록에 있는 문서는 COMPLETED, 영어인데 번역이 없는
-- 문서는 FAILED로 채운다. null로 두면 PENDING으로 보여 화면이 폴링을 계속한다.
-- update document d set source_language = c.source_language
--   from document_content c where c.document_id = d.id and d.source_language is null;
-- update document d set compile_status = 'COMPLETED'
--   where d.compile_status is null and exists (
--     select 1 from document_content_block b
--     where b.document_id = d.id and b.content ? 'textKor');
-- update document d set compile_status = 'FAILED', compile_error_code = 'LEGACY_NO_TRANSLATION'
--   where d.compile_status is null and d.status = 'COMPLETED' and d.source_language = 'en';
