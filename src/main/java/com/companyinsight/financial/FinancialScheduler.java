package com.companyinsight.financial;

import com.companyinsight.common.schedule.ExclusiveJobRunner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 재무 수집 실행. 지금은 설정에 적은 회사만 앱이 뜬 직후 한 번 받는다.
 * 전 종목을 정기적으로 받는 일정은 두지 않았다.
 */
@Component
public class FinancialScheduler {

	private static final String GROUP = "financial";

	private final FinancialCollector collector;
	private final ExclusiveJobRunner jobs;
	private final FinancialProperties properties;

	public FinancialScheduler(FinancialCollector collector, ExclusiveJobRunner jobs, FinancialProperties properties) {
		this.collector = collector;
		this.jobs = jobs;
		this.properties = properties;
	}

	/**
	 * 설정({@code app.financial.collect-on-startup})에 종목코드가 있으면 그 회사들의 재무를 받는다.
	 * 앱 준비를 늦추지 않게 별도 스레드에서 실행한다.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		if (!properties.collectOnStartup().isEmpty()) {
			Thread.ofVirtual().name("financial-collect-startup")
				.start(() -> jobs.run(GROUP, "재무 수집(시작)",
					() -> collector.collectByStockCodes(properties.collectOnStartup(), properties.collectYears())));
		}
	}
}
