-- 파싱 산출물 3테이블 — document 단위로 공유된다. 행 존재 = 적재 완료.
-- prod는 validate라 배포 전에 반영해야 한다. ⚠ 엔티티가 바뀌면 이 파일도 함께 고칠 것.

create table document_content (
    document_id    uuid                        not null,
    title          varchar(255),
    schema_version integer                     not null,
    ingested_at    timestamp(6) with time zone not null,
    primary key (document_id)
);

create table document_content_block (
    id            bigserial                   not null,
    document_id   uuid                        not null,
    block_id      varchar(255)                not null,
    global_order  integer                     not null,
    label         varchar(32)                 not null,
    heading_level integer,
    section_path  jsonb                       not null,
    content       jsonb                       not null,
    primary key (id),
    constraint uk_document_content_block unique (document_id, block_id)
);
create index ix_document_content_block_order on document_content_block (document_id, global_order);

create table document_content_asset (
    id          bigserial    not null,
    document_id uuid         not null,
    asset_key   varchar(255) not null,
    s3_key      varchar(255) not null,
    media_type  varchar(255) not null,
    primary key (id),
    constraint uk_document_content_asset unique (document_id, asset_key)
);
