package com.ymc.plan.service;

import java.time.Instant;
import java.time.ZoneId;

/** KST 달력월 경계 계산. 예약·조회가 같은 계산을 쓰도록 여기에만 둔다. */
public final class BucketPeriod {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private BucketPeriod() {
    }

    /** at이 속한 KST 달력월 1일 00:00의 UTC 시각. */
    public static Instant startOf(Instant at) {
        return at.atZone(KST).toLocalDate().withDayOfMonth(1).atStartOfDay(KST).toInstant();
    }

    /** 다음 KST 달력월 1일 00:00의 UTC 시각 — 계약 resetAt. */
    public static Instant nextResetAfter(Instant at) {
        return at.atZone(KST).toLocalDate().withDayOfMonth(1).plusMonths(1)
                .atStartOfDay(KST).toInstant();
    }
}
