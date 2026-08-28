package com.ymc.plan.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.ymc.plan.domain.PlanCode;
import com.ymc.plan.domain.PolicyMode;
import com.ymc.plan.domain.UsageType;
import com.ymc.support.LocalStackTestConfiguration;
import com.ymc.support.TestcontainersConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import({TestcontainersConfiguration.class, LocalStackTestConfiguration.class})
class PlanPropertiesTest {

    @Autowired
    private PlanProperties props;

    @Test
    @DisplayName("application.yml 기본값 — FREE.AI_QUERY는 MONTHLY 100")
    void freeAiQueryPolicy() {
        var policy = props.policyOf(PlanCode.FREE, UsageType.AI_QUERY);
        assertThat(policy.mode()).isEqualTo(PolicyMode.MONTHLY);
        assertThat(policy.limit()).isEqualTo(100);
    }

    @Test
    @DisplayName("application.yml 기본값 — PRO.PAPER_REGISTRATION은 MONTHLY 100")
    void proRegistrationPolicy() {
        var policy = props.policyOf(PlanCode.PRO, UsageType.PAPER_REGISTRATION);
        assertThat(policy.mode()).isEqualTo(PolicyMode.MONTHLY);
        assertThat(policy.limit()).isEqualTo(100);
    }

    @Test
    @DisplayName("application.yml 기본값 — cleanup.interval은 10분")
    void cleanupInterval() {
        assertThat(props.cleanup().interval()).isEqualTo(Duration.ofMinutes(10));
    }
}
