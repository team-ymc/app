-- FT-011 플랜·사용량. prod 첫 배포 전 실행 (dev는 ddl-auto가 생성).
create table plan_entitlement (
    id          uuid not null,
    user_id     uuid not null,
    -- 현재 PRO만. 유효 기간은 [started_at, ended_at)
    plan_code   varchar(16) not null,
    started_at  timestamp(6) with time zone not null,
    ended_at    timestamp(6) with time zone not null,
    created_at  timestamp(6) with time zone not null,
    primary key (id)
);
create index idx_plan_entitlement_lookup
    on plan_entitlement (user_id, plan_code, started_at, ended_at);

-- 월 버킷 — 예약 직렬화의 잠금 앵커. 집계 카운터 없음
create table usage_bucket (
    id           uuid not null,
    user_id      uuid not null,
    usage_type   varchar(32) not null,
    -- 판정 플랜별 버킷 분리 — 월 중 플랜 변경 시 집계가 섞이지 않는다
    plan_code    varchar(16) not null,
    -- KST 월 1일 00:00의 UTC 시각
    bucket_start timestamp(6) with time zone not null,
    created_at   timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_usage_bucket unique (user_id, usage_type, plan_code, bucket_start)
);

-- 실행별 원장. 해제해도 RELEASED로 남긴다
create table usage_record (
    id                 uuid not null,
    bucket_id          uuid not null,
    usage_type         varchar(32) not null,
    -- AI 질의: clientMessageId, 문서 등록: paperId
    source_id          uuid not null,
    -- 실행 종류: CHAT_MESSAGE / INLINE_TRANSLATION / PAPER. 집계 구분용, 유니크는 (usage_type, source_id) 유지
    source_type        varchar(32) not null,
    -- RESERVED → CONFIRMED / RELEASED
    status             varchar(16) not null,
    -- AI CONFIRMED에만. run.completed의 추정 비용(USD)
    estimated_cost_usd numeric(14, 8),
    created_at         timestamp(6) with time zone not null,
    updated_at         timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_usage_record_type_source unique (usage_type, source_id)
);
create index idx_usage_record_bucket on usage_record (bucket_id);

-- 기존 배포 DB에 source_type을 붙일 때 (컬럼이 이미 nullable로 있으면 alter add는 생략)
-- alter table usage_record add column source_type varchar(32);
-- update usage_record set source_type = case usage_type when 'AI_QUERY' then 'CHAT_MESSAGE' else 'PAPER' end
--  where source_type is null;
-- alter table usage_record alter column source_type set not null;
