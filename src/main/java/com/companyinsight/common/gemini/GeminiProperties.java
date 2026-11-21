package com.companyinsight.common.gemini;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini API 설정.
 *
 * @param apiKey         인증키. 비어 있으면 AI 단계가 돌지 않는다
 * @param model          모델 이름 (예: gemini-3.5-flash-lite)
 * @param baseUrl        API 주소
 * @param connectTimeout 연결 타임아웃
 * @param readTimeout    응답 타임아웃. 모델이 생각하는 시간까지 든다
 * @param minInterval    호출 사이의 가장 짧은 간격. 무료 구간의 분당 한도(15회)를 넘지 않게 4초보다 길게 둔다
 * @param quotaResetZone 하루 한도가 풀리는 자정의 시간대. 확인하지 못한 값이라 설정으로 둔다 (가정: 미국 태평양 시간)
 */
@ConfigurationProperties("app.gemini")
public record GeminiProperties(String apiKey, String model, String baseUrl, Duration connectTimeout,
		Duration readTimeout, Duration minInterval, ZoneId quotaResetZone) {

	public GeminiProperties {
		if (model == null || model.isBlank()) {
			model = "gemini-3.5-flash-lite";
		}
		if (baseUrl == null || baseUrl.isBlank()) {
			baseUrl = "https://generativelanguage.googleapis.com";
		}
		if (connectTimeout == null) {
			connectTimeout = Duration.ofSeconds(10);
		}
		if (readTimeout == null) {
			readTimeout = Duration.ofSeconds(120);
		}
		if (minInterval == null || minInterval.isNegative()) {
			minInterval = Duration.ofMillis(4500);
		}
		if (quotaResetZone == null) {
			quotaResetZone = ZoneId.of("America/Los_Angeles");
		}
	}

	/** 인증키가 비어 있지 않은지. */
	public boolean hasApiKey() {
		return apiKey != null && !apiKey.isBlank();
	}
}
