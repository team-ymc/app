package com.ymc.plan.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ymc.plan.domain.PlanCode;
import com.ymc.plan.domain.PolicyMode;
import com.ymc.plan.domain.UsageType;

class PlanPropertiesTest {

    @Test
    @DisplayName("application.yml 기본값 — FREE.AI_QUERY는 MONTHLY 100")
    void freeAiQueryPolicy() {
        PlanProperties props = props();
        var policy = props.policyOf(PlanCode.FREE, UsageType.AI_QUERY);
        assertThat(policy.mode()).isEqualTo(PolicyMode.MONTHLY);
        assertThat(policy.limit()).isEqualTo(100);
    }

    @Test
    @DisplayName("application.yml 기본값 — PRO.PAPER_REGISTRATION은 MONTHLY 100")
    void proRegistrationPolicy() {
        PlanProperties props = props();
        var policy = props.policyOf(PlanCode.PRO, UsageType.PAPER_REGISTRATION);
        assertThat(policy.mode()).isEqualTo(PolicyMode.MONTHLY);
        assertThat(policy.limit()).isEqualTo(100);
    }

    @Test
    @DisplayName("application.yml 기본값 — cleanup.interval은 10분")
    void cleanupInterval() {
        PlanProperties props = props();
        assertThat(props.cleanup().interval()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("MONTHLY 정책에 null limit은 IllegalArgumentException")
    void monthlyPolicyWithNullLimitRejected() {
        assertThatThrownBy(() -> new PlanProperties.Policy(PolicyMode.MONTHLY, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("MONTHLY 정책에 음수 limit은 IllegalArgumentException")
    void monthlyPolicyWithNegativeLimitRejected() {
        assertThatThrownBy(() -> new PlanProperties.Policy(PolicyMode.MONTHLY, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    private PlanProperties props() {
        var policy = Map.of(
                PlanCode.FREE, Map.of(
                        UsageType.AI_QUERY,
                        new PlanProperties.Policy(PolicyMode.MONTHLY, 100),
                        UsageType.PAPER_REGISTRATION,
                        new PlanProperties.Policy(PolicyMode.MONTHLY, 3)),
                PlanCode.PRO, Map.of(
                        UsageType.AI_QUERY,
                        new PlanProperties.Policy(PolicyMode.MONTHLY, 1000),
                        UsageType.PAPER_REGISTRATION,
                        new PlanProperties.Policy(PolicyMode.MONTHLY, 100)));
        var cleanup = new PlanProperties.Cleanup(
                Duration.ofMinutes(10),
                Duration.ofMinutes(30),
                Duration.ofHours(1),
                Duration.ofHours(3));
        return new PlanProperties(policy, cleanup);
    }
}
