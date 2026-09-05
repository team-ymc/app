-- paper 테이블 (FT-003 / YMC-182·183·184)
--
-- 왜 이 파일이 있나: local·dev는 ddl-auto: update로 스키마가 자동 생성되지만 prod는 validate라
-- 테이블이 미리 없으면 애플리케이션이 뜨지 않는다. 배포 전에 이 스크립트를 운영 DB에 반영해야 한다.
-- 마이그레이션 도구(Flyway 등) 도입은 별도 결정이며, 그때까지는 이 파일이 산출물이다
-- (openspec/changes/ft-003-paper-registration-api/design.md — Risks).
--
-- ⚠ Paper 엔티티가 바뀌면 이 파일도 함께 고쳐야 한다. 어긋나면 prod 기동이 validate에서 실패한다.
-- 재생성: Paper 엔티티를 매핑한 컨텍스트를 다음 속성으로 띄우면 Hibernate가 그대로 뽑아준다.
--   spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create
--   spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=<path>.sql
--
-- 상태는 Paper가 갖지 않는다 — document가 소유하고 조회 시 파생한다(PaperDocumentViews).
-- document_id의 FK·인덱스는 이 파일에만 있다 — Paper 엔티티는 연관관계 없이 컬럼만 매핑하므로
-- ddl-auto(update)로 띄운 local·dev 스키마에는 이 FK·인덱스가 없다(수동 DDL 소유 대상).

create table paper (
    id          uuid                        not null,
    owner_id    uuid                        not null,
    filename    varchar(255)                not null,
    file_key    varchar(255)                not null,
    document_id uuid,
    created_at  timestamp(6) with time zone not null,
    updated_at  timestamp(6) with time zone not null,
    -- 소유자가 마지막으로 접근한 시각
    last_accessed_at timestamp(6) with time zone,
    -- 정리 스케줄러의 만료 시각 (null = 만료 아님). 값이 있으면 상태가 EXPIRED로 파생된다
    expired_at       timestamp(6) with time zone,
    -- 논리 삭제 시각 (null = 살아 있음). 값이 있으면 목록·조회·채팅에서 보이지 않는다
    deleted_at       timestamp(6) with time zone,

    primary key (id),

    constraint fk_paper_document foreign key (document_id) references document (id)
);

create index ix_paper_document on paper (document_id);

-- 기존 환경(local·dev)은 ddl-auto가 제약을 지우지 않으므로 배포 전에 수동으로 실행한다.
-- alter table paper drop constraint if exists uk_paper_owner_filename;
