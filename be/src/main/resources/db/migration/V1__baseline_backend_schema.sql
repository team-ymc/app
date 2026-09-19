-- YMC-391: Backend가 소유하는 PostgreSQL 스키마의 Flyway 기준선.
--
-- 빈 DB에서는 이 파일이 전체 스키마를 만든다. Flyway 도입 전에 Hibernate ddl-auto=update로
-- 생성된 local·dev DB는 version 0으로 baseline된 뒤 이 파일을 실행한다. 따라서 CREATE와
-- 알려진 legacy 보정은 재실행에 안전하게 작성한다.

create table if not exists users (
    id           uuid                        not null,
    provider     varchar(32)                 not null,
    provider_id  varchar(255)                not null,
    email        varchar(255),
    display_name varchar(255),
    created_at   timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_users_provider_provider_id unique (provider, provider_id),
    constraint ck_users_provider check (provider in ('GOOGLE'))
);

create table if not exists refresh_token (
    id         uuid                        not null,
    user_id    uuid                        not null,
    token_hash varchar(64)                 not null,
    expires_at timestamp(6) with time zone not null,
    revoked_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_refresh_token_hash unique (token_hash)
);

create table if not exists document (
    id                   uuid                        not null,
    checksum_sha256      varchar(44)                 not null,
    file_key             varchar(255)                not null,
    status               varchar(32)                 not null,
    error_code           varchar(255),
    source_language      varchar(8),
    compile_status       varchar(32),
    compile_error_code   varchar(255),
    knowledge_graph_key  varchar(255),
    request_paper_id     uuid                        not null,
    created_at           timestamp(6) with time zone not null,
    updated_at           timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_document_checksum unique (checksum_sha256),
    constraint ck_document_status check (status in ('UPLOADED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    constraint ck_document_compile_status check (
        compile_status is null or compile_status in ('REQUESTED', 'COMPLETED', 'FAILED'))
);

alter table document add column if not exists source_language varchar(8);
alter table document add column if not exists compile_status varchar(32);
alter table document add column if not exists compile_error_code varchar(255);
alter table document add column if not exists knowledge_graph_key varchar(255);
create unique index if not exists ux_document_request_paper on document (request_paper_id);

create table if not exists document_content (
    document_id     uuid                        not null,
    title           varchar(255),
    schema_version  integer                     not null,
    source_language varchar(8),
    ingested_at     timestamp(6) with time zone not null,
    primary key (document_id)
);

alter table document_content add column if not exists source_language varchar(8);

create table if not exists document_content_block (
    id            bigserial    not null,
    document_id   uuid         not null,
    block_id      varchar(255) not null,
    global_order  integer      not null,
    label         varchar(32)  not null,
    heading_level integer,
    section_path  jsonb        not null,
    content       jsonb        not null,
    primary key (id),
    constraint uk_document_content_block unique (document_id, block_id)
);

create index if not exists ix_document_content_block_order
    on document_content_block (document_id, global_order);

create table if not exists document_content_asset (
    id          bigserial    not null,
    document_id uuid         not null,
    asset_key   varchar(255) not null,
    s3_key      varchar(255) not null,
    media_type  varchar(255) not null,
    primary key (id),
    constraint uk_document_content_asset unique (document_id, asset_key)
);

create table if not exists paper (
    id               uuid                        not null,
    owner_id         uuid                        not null,
    filename         varchar(255)                not null,
    title_override   varchar(255),
    file_key         varchar(255)                not null,
    document_id      uuid,
    created_at       timestamp(6) with time zone not null,
    updated_at       timestamp(6) with time zone not null,
    last_accessed_at timestamp(6) with time zone,
    expired_at       timestamp(6) with time zone,
    deleted_at       timestamp(6) with time zone,
    primary key (id)
);

alter table paper add column if not exists title_override varchar(255);
alter table paper add column if not exists last_accessed_at timestamp(6) with time zone;
alter table paper add column if not exists expired_at timestamp(6) with time zone;
alter table paper add column if not exists deleted_at timestamp(6) with time zone;
alter table paper drop constraint if exists uk_paper_owner_filename;
create index if not exists ix_paper_document on paper (document_id);

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'fk_paper_document') then
        alter table paper add constraint fk_paper_document
            foreign key (document_id) references document (id);
    end if;
end $$;

create table if not exists chat_session (
    id              uuid                        not null,
    owner_id        uuid                        not null,
    paper_id        uuid                        not null,
    title           varchar(120)                not null,
    created_at      timestamp(6) with time zone not null,
    last_message_at timestamp(6) with time zone not null,
    deleted_at      timestamp(6) with time zone,
    primary key (id)
);

alter table chat_session add column if not exists deleted_at timestamp(6) with time zone;
create index if not exists ix_chat_session_owner on chat_session (owner_id);

create table if not exists chat_message (
    id                uuid                        not null,
    session_id        uuid                        not null,
    role              varchar(16)                 not null,
    content           text,
    status            varchar(16)                 not null,
    client_message_id uuid                        not null,
    seq               integer                     not null,
    created_at        timestamp(6) with time zone not null,
    completed_at      timestamp(6) with time zone,
    primary key (id),
    constraint fk_chat_message_session foreign key (session_id) references chat_session (id),
    constraint uk_chat_message_client_id_role unique (client_message_id, role),
    constraint uk_chat_message_session_seq unique (session_id, seq),
    constraint ck_chat_message_role check (role in ('USER', 'ASSISTANT')),
    constraint ck_chat_message_status check (status in ('GENERATING', 'COMPLETED', 'FAILED'))
);

create table if not exists translation_run (
    id           uuid                        not null,
    owner_id     uuid                        not null,
    paper_id     uuid                        not null,
    status       varchar(16)                 not null,
    selection    jsonb                       not null,
    translation  text,
    created_at   timestamp(6) with time zone not null,
    completed_at timestamp(6) with time zone,
    primary key (id),
    constraint ck_translation_run_status check (status in ('GENERATING', 'COMPLETED', 'FAILED'))
);

create index if not exists ix_translation_run_owner_status
    on translation_run (owner_id, status);

create table if not exists plan_entitlement (
    id         uuid                        not null,
    user_id    uuid                        not null,
    plan_code  varchar(16)                 not null,
    started_at timestamp(6) with time zone not null,
    ended_at   timestamp(6) with time zone not null,
    created_at timestamp(6) with time zone not null,
    primary key (id),
    constraint ck_plan_entitlement_plan_code check (plan_code in ('FREE', 'PRO'))
);

create index if not exists idx_plan_entitlement_lookup
    on plan_entitlement (user_id, plan_code, started_at, ended_at);

create table if not exists usage_bucket (
    id           uuid                        not null,
    user_id      uuid                        not null,
    usage_type   varchar(32)                 not null,
    plan_code    varchar(16)                 not null,
    bucket_start timestamp(6) with time zone not null,
    created_at   timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_usage_bucket unique (user_id, usage_type, plan_code, bucket_start),
    constraint ck_usage_bucket_type check (usage_type in ('AI_QUERY', 'PAPER_REGISTRATION')),
    constraint ck_usage_bucket_plan check (plan_code in ('FREE', 'PRO'))
);

create table if not exists usage_record (
    id                 uuid                        not null,
    bucket_id          uuid                        not null,
    usage_type         varchar(32)                 not null,
    source_id          uuid                        not null,
    source_type        varchar(32),
    status             varchar(16)                 not null,
    estimated_cost_usd numeric(14, 8),
    created_at         timestamp(6) with time zone not null,
    updated_at         timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_usage_record_type_source unique (usage_type, source_id),
    constraint ck_usage_record_type check (usage_type in ('AI_QUERY', 'PAPER_REGISTRATION')),
    constraint ck_usage_record_source_type check (
        source_type is null or source_type in ('CHAT_MESSAGE', 'INLINE_TRANSLATION', 'PAPER')),
    constraint ck_usage_record_status check (status in ('RESERVED', 'CONFIRMED', 'RELEASED'))
);

alter table usage_record add column if not exists source_type varchar(32);
update usage_record
set source_type = case usage_type when 'AI_QUERY' then 'CHAT_MESSAGE' else 'PAPER' end
where source_type is null;
alter table usage_record alter column source_type set not null;
create index if not exists idx_usage_record_bucket on usage_record (bucket_id);

-- ddl-auto=update 시절의 번역 상태를 현재 모델로 한 번 정규화한다.
update document d
set source_language = c.source_language
from document_content c
where c.document_id = d.id and d.source_language is null;

update document d
set compile_status = 'COMPLETED'
where d.compile_status is null
  and exists (
      select 1 from document_content_block b
      where b.document_id = d.id and b.content ? 'textKor'
  );

update document
set compile_status = 'FAILED', compile_error_code = 'LEGACY_NO_TRANSLATION'
where compile_status is null and status = 'COMPLETED' and source_language = 'en';
