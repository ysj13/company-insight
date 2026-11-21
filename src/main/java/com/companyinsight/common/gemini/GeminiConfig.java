package com.companyinsight.common.gemini;

import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Gemini 호출에 쓰는 빈 설정. 타임아웃을 건 HTTP 클라이언트로 {@link GeminiClient}를 만든다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GeminiProperties.class)
public class GeminiConfig {

	/** 연결·응답 타임아웃과 호출 간격이 적용된 Gemini 클라이언트. */
	@Bean
	GeminiClient geminiClient(GeminiProperties properties, Clock clock) {
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
		return new GeminiClient(restClient, properties, clock);
	}
}
