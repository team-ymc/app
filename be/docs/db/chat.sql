-- chat_session · chat_message 테이블 (FT-007 / YMC-256)
--
-- 왜 이 파일이 있나: local·dev는 ddl-auto: update로 스키마가 자동 생성되지만 prod는 validate라
-- 테이블이 미리 없으면 애플리케이션이 뜨지 않는다. 배포 전에 이 스크립트를 운영 DB에 반영해야 한다.
-- 마이그레이션 도구(Flyway 등) 도입은 별도 결정이며, 그때까지는 이 파일이 산출물이다 (paper.sql과 동일).
--
-- ⚠ ChatSession·ChatMessage 엔티티가 바뀌면 이 파일도 함께 고쳐야 한다.
--
-- 상태 라이프사이클(계약 openapi.yaml ChatMessageStatus):
--   assistant: GENERATING → COMPLETED | FAILED (조건부 UPDATE로만 전이)
--   user 메시지는 생성 즉시 COMPLETED.

create table chat_session (
    id         uuid                        not null,
    owner_id   uuid                        not null,
    paper_id   uuid                        not null,
    created_at timestamp(6) with time zone not null,

    primary key (id)
);

create table chat_message (
    id                uuid                        not null,
    session_id        uuid                        not null,
    role              varchar(16)                 not null
        check (role in ('USER', 'ASSISTANT')),
    content           text,
    status            varchar(16)                 not null
        check (status in ('GENERATING', 'COMPLETED', 'FAILED')),
    client_message_id uuid                        not null,
    created_at        timestamp(6) with time zone not null,
    completed_at      timestamp(6) with time zone,

    primary key (id),

    constraint fk_chat_message_session foreign key (session_id) references chat_session (id),

    -- 재전송 멱등(DUPLICATE_MESSAGE·CLIENT_MESSAGE_ID_CONFLICT)의 최종 방어선.
    -- user·assistant 두 행이 같은 clientMessageId를 나눠 가지므로 (client_message_id, role) 단위다.
    constraint uk_chat_message_client_id_role unique (client_message_id, role)
);

-- 세션 내 메시지 정렬 조회(히스토리 조회 YMC-260 대비)와 GENERATING 존재 검증용.
create index ix_chat_message_session_created on chat_message (session_id, created_at);
