-- 운영 중 적용한다. 잠금을 오래 기다리지 않고 실패하게 해 서비스 요청이 줄 서지 않게 한다.
set local lock_timeout = '3s';

-- 앱은 document 행을 먼저 잠그고 paper를 읽는다. 같은 순서로 잠가 교착을 피한다.
alter table document add column if not exists attempt integer not null default 0;
alter table document add column if not exists compile_attempt integer not null default 0;
alter table paper add column if not exists failed_at timestamp(6) with time zone;
alter table paper add column if not exists failed_error_code varchar(255);

-- 이미 실패한 Paper를 채운다. 빠뜨리면 다른 사용자의 재시도 성공이 이 Paper를 차감 없이 연다.
update paper p
   set failed_at = d.updated_at,
       failed_error_code = d.error_code
  from document d
 where p.document_id = d.id
   and d.status = 'FAILED'
   and p.failed_at is null;

-- 이미 한 번 돌았던 Document의 횟수를 맞춘다.
update document set attempt = 1 where status <> 'UPLOADED' and attempt = 0;
update document set compile_attempt = 1 where compile_status is not null and compile_attempt = 0;
