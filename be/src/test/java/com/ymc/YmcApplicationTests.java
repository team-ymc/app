package com.ymc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.ymc.support.LocalStackTestConfiguration;
import com.ymc.support.TestcontainersConfiguration;

/**
 * 컨텍스트 기동 스모크.
 *
 * <p>parse-results 리스너(@SqsListener)가 기동과 함께 큐에 붙으므로 PostgreSQL만으로는 컨텍스트가
 * 뜨지 않는다 — LocalStack(S3·SQS)도 함께 띄운다. 이 조합은 통합 테스트들과 같아서 컨텍스트를 공유한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"ai.fake-stream=true",
		"management.server.port=0",
		"management.endpoints.web.exposure.include=health,prometheus"
})
@AutoConfigureMockMvc
@ActiveProfiles({"local", "observability"})
@Import({TestcontainersConfiguration.class, LocalStackTestConfiguration.class})
class YmcApplicationTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	TestRestTemplate restTemplate;

	@LocalManagementPort
	int managementPort;

	@DisplayName("ECS health check용 /livez를 애플리케이션 포트에 노출한다")
	@Test
	void exposesLivenessOnTheApplicationPort() throws Exception {
		// ECS container health check가 호출할 endpoint를 검증한다.
		mockMvc.perform(get("/livez"))
				.andExpect(status().isOk());
	}

	@DisplayName("ALB health check용 /readyz를 애플리케이션 포트에 노출한다")
	@Test
	void exposesReadinessOnTheApplicationPort() throws Exception {
		// ALB target group health check가 호출할 endpoint를 검증한다.
		mockMvc.perform(get("/readyz"))
				.andExpect(status().isOk());
	}

	@DisplayName("Collector scrape용 Prometheus metric을 management 포트에 노출한다")
	@Test
	void exposesPrometheusMetricsOnTheManagementPort() {
		// OTel Collector sidecar가 scrape할 endpoint와 metric 출력을 검증한다.
		ResponseEntity<String> response = restTemplate.getForEntity(
				"http://127.0.0.1:%d/actuator/prometheus".formatted(managementPort),
				String.class);

		assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
		assertThat(response.getBody()).contains("jvm_");
	}

}
