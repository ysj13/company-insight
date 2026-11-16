package com.companyinsight.financial;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 재무 수집 설정.
 *
 * @param collectOnStartup 앱이 뜬 직후 재무를 받을 회사의 종목코드. 비어 있으면 받지 않는다
 * @param collectYears     받을 사업연도 수. 올해를 포함해 거슬러 올라간다 (3이면 올해, 작년, 재작년)
 * @param reextractOnStartup 앱이 뜬 직후 저장된 원본에서 재무 행을 다시 뽑을지. 뽑는 규칙을 고친 뒤 한 번 켠다
 */
@ConfigurationProperties("app.financial")
public record FinancialProperties(List<String> collectOnStartup, Integer collectYears, boolean reextractOnStartup) {

	public FinancialProperties {
		collectOnStartup = collectOnStartup == null ? List.of()
				: collectOnStartup.stream().filter(code -> code != null && !code.isBlank()).map(String::strip).toList();
		if (collectYears == null || collectYears < 1) {
			collectYears = 3;
		}
	}
}
