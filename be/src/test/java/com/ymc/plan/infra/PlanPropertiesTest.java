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

    @Test
    @DisplayName("정책 mode가 null이면 거부한다")
    void nullPolicyModeRejected() {
        assertThatThrownBy(() -> new PlanProperties.Policy(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode");
    }

    @Test
    @DisplayName("UNLIMITED 정책에 limit이 있으면 거부한다")
    void unlimitedPolicyWithLimitRejected() {
        assertThatThrownBy(() -> new PlanProperties.Policy(PolicyMode.UNLIMITED, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("UNLIMITED 정책은 limit 없이 생성할 수 있다")
    void unlimitedPolicyWithoutLimitAccepted() {
        var policy = new PlanProperties.Policy(PolicyMode.UNLIMITED, null);

        assertThat(policy.mode()).isEqualTo(PolicyMode.UNLIMITED);
        assertThat(policy.limit()).isNull();
    }

    @Test
    @DisplayName("플랜 정책 전체가 없으면 거부한다")
    void nullPolicyRejected() {
        assertThatThrownBy(() -> new PlanProperties(null, cleanup()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("플랜 정책");
    }

    @Test
    @DisplayName("플랜 하나의 정책이 누락되면 거부한다")
    void missingPlanPolicyRejected() {
        var policy = Map.of(
                PlanCode.FREE, policiesByUsageType(100, 3));

        assertThatThrownBy(() -> new PlanProperties(policy, cleanup()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRO");
    }

    @Test
    @DisplayName("사용 유형 하나의 정책이 누락되면 거부한다")
    void missingUsageTypePolicyRejected() {
        var policy = Map.of(
                PlanCode.FREE, Map.of(
                        UsageType.AI_QUERY,
                        new PlanProperties.Policy(PolicyMode.MONTHLY, 100)),
                PlanCode.PRO, policiesByUsageType(1000, 100));

        assertThatThrownBy(() -> new PlanProperties(policy, cleanup()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FREE/PAPER_REGISTRATION");
    }

    @Test
    @DisplayName("cleanup 설정 전체가 없으면 거부한다")
    void nullCleanupRejected() {
        assertThatThrownBy(() -> new PlanProperties(policies(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cleanup");
    }

    @Test
    @DisplayName("cleanup 시간은 모두 양수여야 한다")
    void nonPositiveCleanupDurationRejected() {
        assertThatThrownBy(() -> new PlanProperties.Cleanup(
                Duration.ZERO, Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interval");
        assertThatThrownBy(() -> new PlanProperties.Cleanup(
                Duration.ofMinutes(10), null, Duration.ofHours(1), Duration.ofHours(3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chat-generating-deadline");
        assertThatThrownBy(() -> new PlanProperties.Cleanup(
                Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofSeconds(-1), Duration.ofHours(3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("upload-pending-deadline");
        assertThatThrownBy(() -> new PlanProperties.Cleanup(
                Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofHours(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processing-deadline");
    }

    private PlanProperties props() {
        return new PlanProperties(policies(), cleanup());
    }

    private Map<PlanCode, Map<UsageType, PlanProperties.Policy>> policies() {
        return Map.of(
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
    }

    private Map<UsageType, PlanProperties.Policy> policiesByUsageType(int aiQueryLimit, int paperLimit) {
        return Map.of(
                UsageType.AI_QUERY,
                new PlanProperties.Policy(PolicyMode.MONTHLY, aiQueryLimit),
                UsageType.PAPER_REGISTRATION,
                new PlanProperties.Policy(PolicyMode.MONTHLY, paperLimit));
    }

    private PlanProperties.Cleanup cleanup() {
        return new PlanProperties.Cleanup(
                Duration.ofMinutes(10),
                Duration.ofMinutes(30),
                Duration.ofHours(1),
                Duration.ofHours(3));
    }
}
