create table document_prerequisite_highlight (
    id           bigserial    not null,
    document_id  uuid         not null,
    highlight_id varchar(64)  not null,
    block_id     varchar(255) not null,
    start_offset integer      not null,
    end_offset   integer      not null,
    text         text         not null,
    primary key (id),
    constraint uk_document_prerequisite_highlight unique (document_id, highlight_id),
    constraint ck_document_prerequisite_highlight_range check (start_offset >= 0 and start_offset < end_offset)
);
