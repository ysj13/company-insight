package com.companyinsight.disclosure;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 회사를 지정한 공시 수집 설정.
 *
 * @param collectOnStartup 앱이 뜬 직후 공시 목록을 받을 회사의 종목코드. 비어 있으면 받지 않는다
 * @param collectMonths    받을 기간(개월). 오늘까지 거슬러 올라간다 (12면 최근 1년)
 */
@ConfigurationProperties("app.disclosure")
public record DisclosureCollectProperties(List<String> collectOnStartup, Integer collectMonths) {

	/** 기간을 적지 않았거나 1보다 작을 때 쓰는 값. */
	static final int DEFAULT_MONTHS = 12;

	public DisclosureCollectProperties {
		collectOnStartup = collectOnStartup == null ? List.of()
				: collectOnStartup.stream().filter(code -> code != null && !code.isBlank()).map(String::strip).toList();
		if (collectMonths == null || collectMonths < 1) {
			collectMonths = DEFAULT_MONTHS;
		}
	}
}
