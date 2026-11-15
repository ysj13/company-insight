package com.companyinsight;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CompanyInsightApplicationTests {

	@org.springframework.beans.factory.annotation.Autowired
	com.companyinsight.common.dart.DartClient dartClient;

	@Test
	void contextLoads() {
	}

	/** 프록시가 걸려 있어야 {@code @Retryable}이 동작한다. 재시도 설정이 빠지면 이 테스트가 실패한다. */
	@Test
	void DartClient에_재시도_프록시가_적용된다() {
		org.assertj.core.api.Assertions.assertThat(org.springframework.aop.support.AopUtils.isAopProxy(dartClient)).isTrue();
	}

}
