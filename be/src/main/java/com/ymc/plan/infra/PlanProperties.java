package com.ymc.plan.infra;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.ymc.plan.domain.PlanCode;
import com.ymc.plan.domain.PolicyMode;
import com.ymc.plan.domain.UsageType;

/** 플랜·기능별 사용량 정책과 정리 주기. 값은 배포 설정으로 덮는다. */
@ConfigurationProperties(prefix = "plan")
public record PlanProperties(Map<PlanCode, Map<UsageType, Policy>> policy, Cleanup cleanup) {

    public record Policy(PolicyMode mode, Integer limit) {
        public Policy {
            if (mode == PolicyMode.MONTHLY && (limit == null || limit < 0)) {
                throw new IllegalArgumentException("MONTHLY 정책에는 0 이상의 limit이 필요합니다.");
            }
        }
    }

    public record Cleanup(Duration interval, Duration chatGeneratingDeadline,
            Duration uploadPendingDeadline, Duration processingDeadline) {
    }

    public Policy policyOf(PlanCode plan, UsageType usageType) {
        Policy found = policy.getOrDefault(plan, Map.of()).get(usageType);
        if (found == null) {
            throw new IllegalStateException("정책 누락: " + plan + "/" + usageType);
        }
        return found;
    }
}
