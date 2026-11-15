package com.companyinsight.common.dart;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenDART API 설정.
 *
 * @param apiKey         인증키. 비어 있으면 DART 호출을 건너뛴다.
 * @param baseUrl        API 주소
 * @param connectTimeout 연결 타임아웃
 * @param readTimeout    응답 타임아웃. 고유번호 zip은 수 MB라 넉넉하게 둔다.
 */
@ConfigurationProperties("app.dart")
public record DartProperties(String apiKey, String baseUrl, Duration connectTimeout, Duration readTimeout) {

	public DartProperties {
		if (baseUrl == null || baseUrl.isBlank()) {
			baseUrl = "https://opendart.fss.or.kr";
		}
		if (connectTimeout == null) {
			connectTimeout = Duration.ofSeconds(5);
		}
		if (readTimeout == null) {
			readTimeout = Duration.ofSeconds(60);
		}
	}

	public boolean hasApiKey() {
		return apiKey != null && !apiKey.isBlank();
	}
}
