-- 베타 테스터 Pro 부여. :user_id를 치환해 실행한다.
-- KST 부여 시각부터 달력상 1개월 — PG interval이 말일을 보정한다 (1/31 + 1 month = 2/28).
insert into plan_entitlement (id, user_id, plan_code, started_at, ended_at, created_at)
values (
    gen_random_uuid(),
    :user_id,
    'PRO',
    now(),
    ((now() at time zone 'Asia/Seoul') + interval '1 month') at time zone 'Asia/Seoul',
    now()
);
