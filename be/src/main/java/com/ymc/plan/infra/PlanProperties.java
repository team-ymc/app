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

    public PlanProperties {
        if (policy == null) {
            throw new IllegalArgumentException("플랜 정책이 필요합니다.");
        }
        if (cleanup == null) {
            throw new IllegalArgumentException("cleanup 설정이 필요합니다.");
        }

        for (PlanCode plan : PlanCode.values()) {
            Map<UsageType, Policy> policiesByUsageType = policy.get(plan);
            if (policiesByUsageType == null) {
                throw new IllegalArgumentException("플랜 정책 누락: " + plan);
            }
            for (UsageType usageType : UsageType.values()) {
                if (policiesByUsageType.get(usageType) == null) {
                    throw new IllegalArgumentException("정책 누락: " + plan + "/" + usageType);
                }
            }
        }
    }

    public record Policy(PolicyMode mode, Integer limit) {
        public Policy {
            if (mode == null) {
                throw new IllegalArgumentException("정책 mode가 필요합니다.");
            }
            if (mode == PolicyMode.MONTHLY && (limit == null || limit < 0)) {
                throw new IllegalArgumentException("MONTHLY 정책에는 0 이상의 limit이 필요합니다.");
            }
            if (mode == PolicyMode.UNLIMITED && limit != null) {
                throw new IllegalArgumentException("UNLIMITED 정책에는 limit을 설정할 수 없습니다.");
            }
        }
    }

    public record Cleanup(Duration interval, Duration chatGeneratingDeadline,
            Duration uploadPendingDeadline, Duration processingDeadline) {
        public Cleanup {
            requirePositive("interval", interval);
            requirePositive("chat-generating-deadline", chatGeneratingDeadline);
            requirePositive("upload-pending-deadline", uploadPendingDeadline);
            requirePositive("processing-deadline", processingDeadline);
        }

        private static void requirePositive(String name, Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("cleanup." + name + "은 양수여야 합니다.");
            }
        }
    }

    public Policy policyOf(PlanCode plan, UsageType usageType) {
        Policy found = policy.getOrDefault(plan, Map.of()).get(usageType);
        if (found == null) {
            throw new IllegalStateException("정책 누락: " + plan + "/" + usageType);
        }
        return found;
    }
}
