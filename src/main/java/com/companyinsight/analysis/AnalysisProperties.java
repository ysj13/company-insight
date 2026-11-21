package com.companyinsight.analysis;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 분석 작업 큐 설정.
 *
 * @param targetStockCodes 분석 대상 회사의 종목코드. 이 회사들의 공시만 큐에 줄 세운다. 비어 있으면 모든 회사
 * @param newDays          접수일이 오늘부터 며칠 전까지인 공시를 새 공시로 보고 먼저 처리할지. 그보다 오래된 공시는 과거분이다
 * @param staleAfter       실행 중인 채로 이만큼 지나면 끝을 보지 못한 실행으로 보고 되돌린다
 * @param retryDelay       실패한 작업을 다시 꺼내기까지 기다리는 시간
 */
@ConfigurationProperties("app.analysis")
public record AnalysisProperties(List<String> targetStockCodes, Integer newDays, Duration staleAfter,
		Duration retryDelay) {

	static final int DEFAULT_NEW_DAYS = 3;
	static final Duration DEFAULT_STALE_AFTER = Duration.ofMinutes(10);
	static final Duration DEFAULT_RETRY_DELAY = Duration.ofMinutes(5);

	public AnalysisProperties {
		targetStockCodes = targetStockCodes == null ? List.of()
				: targetStockCodes.stream()
					.filter(code -> code != null && !code.isBlank())
					.map(code -> code.strip().toUpperCase(Locale.ROOT))
					.distinct()
					.toList();
		if (newDays == null || newDays < 0) {
			newDays = DEFAULT_NEW_DAYS;
		}
		if (staleAfter == null || staleAfter.isNegative() || staleAfter.isZero()) {
			staleAfter = DEFAULT_STALE_AFTER;
		}
		if (retryDelay == null || retryDelay.isNegative()) {
			retryDelay = DEFAULT_RETRY_DELAY;
		}
	}
}
