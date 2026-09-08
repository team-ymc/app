# BE 채팅 메트릭 실험

계획: [AI 채팅·SSE 용량 테스트](../../../project-docs/testing/capacity/ai-chat-sse/plan.md).
기존 Micrometer → Actuator → Collector 경로를 사용하며 별도 endpoint는 만들지 않는다.

| Prometheus 이름 | 의미 |
|---|---|
| `chat_active_runs` | 시작 트랜잭션 성공 후 실행 중인 채팅 수. FE 연결 종료로 감소하지 않음 |
| `chat_runs_total{outcome}` | 최종 결과 success/error/timeout별 실행 수 |
| `chat_ttft_seconds` | Controller 진입부터 첫 비어 있지 않은 AI delta의 중계 준비까지 histogram |
| `chat_duration_seconds{outcome}` | Controller 진입부터 최종 저장·실패 처리 종료까지 histogram |

success는 별도 `ChatMessageTransitions.complete()` 트랜잭션이 커밋되어 true를 반환한 뒤 기록한다. DB 전이에 실패하거나 전이 소유권을 얻지 못하면 error다. 결과는 실행당 한 번만 집계하고 첫 delta 없는 요청은 TTFT에 0을 넣지 않는다. 기존 finished 판정으로 늦은 콜백을 무시하고 메트릭도 중복 종료를 방지한다.

측정 범위는 실행 수락된 채팅이다. Controller 이전 인증·검증 시간과 실행 전 거절은 이 메트릭에 포함하지 않는다. 시간에는 시작 트랜잭션 처리가 포함되지만 active gauge는 커밋 이후 시작한다. TTFT는 실제 FE 수신 시간이 아니며 전체 처리 시간은 마지막 SSE 전송 시간을 포함하지 않는다. FE 수신 지표는 부하 발생기에서 따로 측정한다.

기존 `begin()` 경로에는 외부 트랜잭션이 없고 저장은 별도 Spring bean의 트랜잭션 프록시를 통한다. 호출 구조 변경 시 성공 기록의 커밋 경계를 재검토한다. 메트릭은 프로세스 장애 시 누락될 수 있으므로 정확한 저장 건수는 DB로 보완한다.

예시 쿼리(환경·서비스 label은 실제 수집값에 맞춰 필터):

```promql
sum(chat_active_runs)
sum by (outcome) (rate(chat_runs_total[5m]))
histogram_quantile(0.95, sum by (le) (rate(chat_ttft_seconds_bucket[5m])))
histogram_quantile(0.95, sum by (le) (rate(chat_duration_seconds_bucket{outcome="success"}[5m])))
```

이번 변경은 로컬 실험 브랜치의 계측이며 AWS 배포·Grafana 패널 추가는 별도 작업이다. 사용자·논문·요청 ID와 본문은 label로 기록하지 않는다.
