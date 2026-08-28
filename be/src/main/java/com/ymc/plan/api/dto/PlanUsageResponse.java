package com.ymc.plan.api.dto;

import java.time.Instant;

import com.ymc.plan.domain.PlanCode;
import com.ymc.plan.domain.PolicyMode;

public record PlanUsageResponse(PlanCode plan, Instant planExpiresAt, PlanFeatureUsage usage) {

    public record PlanFeatureUsage(UsageLimit aiQuery, UsageLimit paperRegistration) {
    }

    /** MONTHLY는 네 필드 모두 채우고, UNLIMITED는 mode 외 전부 null (계약 UsageLimit). */
    public record UsageLimit(PolicyMode mode, Integer limit, Long used, Long remaining,
            Instant resetAt) {

        public static UsageLimit unlimited() {
            return new UsageLimit(PolicyMode.UNLIMITED, null, null, null, null);
        }
    }
}
