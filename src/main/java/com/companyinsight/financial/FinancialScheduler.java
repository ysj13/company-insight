package com.companyinsight.financial;

import com.companyinsight.common.schedule.ExclusiveJobRunner;
import com.companyinsight.company.CompanyRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 재무 수집 실행. 지금은 설정에 적은 회사만 앱이 뜬 직후 한 번 받는다.
 * 전 종목을 정기적으로 받는 일정은 두지 않았다.
 */
@Component
@EnableConfigurationProperties(FinancialProperties.class)
public class FinancialScheduler {

	private static final Logger log = LoggerFactory.getLogger(FinancialScheduler.class);

	private static final String GROUP = "financial";

	private final FinancialCollector collector;
	private final CompanyRepository companies;
	private final ExclusiveJobRunner jobs;
	private final FinancialProperties properties;
	private final Clock clock;

	public FinancialScheduler(FinancialCollector collector, CompanyRepository companies, ExclusiveJobRunner jobs,
			FinancialProperties properties, Clock clock) {
		this.collector = collector;
		this.companies = companies;
		this.jobs = jobs;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * 설정({@code app.financial.collect-on-startup})에 종목코드가 있으면 그 회사들의 재무를 받는다.
	 * 앱 준비를 늦추지 않게 별도 스레드에서 실행한다.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		if (!properties.collectOnStartup().isEmpty()) {
			Thread.ofVirtual().name("financial-collect-startup")
				.start(() -> jobs.run(GROUP, "재무 수집(시작)", this::collectConfigured));
		}
	}

	/** 설정에 적힌 회사를 차례로 받는다. 한 회사가 실패해도 나머지는 계속한다. */
	void collectConfigured() {
		int toYear = LocalDate.now(clock).getYear();
		int fromYear = Math.max(FinancialCollector.FIRST_YEAR, toYear - properties.collectYears() + 1);
		for (String stockCode : properties.collectOnStartup()) {
			List<Long> ids = companies.findIdsByStockCode(stockCode);
			if (ids.size() != 1) {
				log.warn("재무 수집: 종목코드 {}에 해당하는 회사가 {}곳이라 건너뜁니다", stockCode, ids.size());
				continue;
			}
			try {
				collector.collect(ids.getFirst(), fromYear, toYear);
			}
			catch (RuntimeException e) {
				// 실패 내용은 collection_run에 남는다
				log.warn("재무 수집 실패(종목코드 {}): {}", stockCode, e.getMessage());
			}
		}
	}
}
