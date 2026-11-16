package com.companyinsight.common.dart;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** OpenDART 호출에 쓰는 빈 설정. 타임아웃을 건 HTTP 클라이언트와 호출 속도 제한을 묶어 {@link DartClient}를 만든다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DartProperties.class)
public class DartConfig {

	/** 연결·응답 타임아웃과 속도 제한이 적용된 DART 클라이언트. */
	@Bean
	DartClient dartClient(DartProperties properties) {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(properties.connectTimeout())
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.readTimeout());

		RestClient restClient = RestClient.builder()
			.baseUrl(properties.baseUrl())
			.requestFactory(requestFactory)
			.build();
		return new DartClient(restClient, properties, dartRateLimiter(properties));
	}

	/** 초당 호출 수 제한. 허가를 30초 안에 받지 못하면 호출하지 않고 RATE_LIMITED 오류로 처리한다. */
	static RateLimiter dartRateLimiter(DartProperties properties) {
		RateLimiterConfig config = RateLimiterConfig.custom()
			.limitForPeriod(properties.requestsPerSecond())
			.limitRefreshPeriod(Duration.ofSeconds(1))
			.timeoutDuration(Duration.ofSeconds(30))
			.build();
		return RateLimiter.of("dart", config);
	}
}
