package com.companyinsight.common.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** "오늘"은 한국 시간 기준. 테스트에서 시각을 고정할 수 있도록 Clock을 빈으로 둔다. */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

	public static final ZoneId KST = ZoneId.of("Asia/Seoul");

	@Bean
	Clock clock() {
		return Clock.system(KST);
	}
}
