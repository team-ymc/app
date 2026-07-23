package com.ymc;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.ymc.support.LocalStackTestConfiguration;
import com.ymc.support.TestcontainersConfiguration;

/**
 * 컨텍스트 기동 스모크.
 *
 * <p>parse-results 리스너(@SqsListener)가 기동과 함께 큐에 붙으므로 PostgreSQL만으로는 컨텍스트가
 * 뜨지 않는다 — LocalStack(S3·SQS)도 함께 띄운다. 이 조합은 통합 테스트들과 같아서 컨텍스트를 공유한다.
 */
@SpringBootTest(properties = "ai.fake-stream=true")
@Import({TestcontainersConfiguration.class, LocalStackTestConfiguration.class})
class YmcApplicationTests {

	@Test
	void contextLoads() {
	}

}
