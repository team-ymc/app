-- 운영 중 적용한다. 잠금을 오래 기다리지 않고 실패하게 해 서비스 요청이 줄 서지 않게 한다.
set local lock_timeout = '3s';

-- 체험 논문 표시. 켜고 끄는 건 운영자가 SQL로 한다 — API가 없다.
alter table paper add column if not exists trial boolean not null default false;
