package com.companyinsight.financial;

import com.companyinsight.company.CompanySearchService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 저장된 재무 응답으로 회사의 기간별 실적을 조회한다. */
@Service
@Transactional(readOnly = true)
public class FinancialPerformanceService {

	private static final Logger log = LoggerFactory.getLogger(FinancialPerformanceService.class);

	private final CompanySearchService companies;
	private final FinancialRepository repository;

	public FinancialPerformanceService(CompanySearchService companies, FinancialRepository repository) {
		this.companies = companies;
		this.repository = repository;
	}

	/**
	 * 회사 한 곳의 기간별 실적. 저장된 응답에서 계정의 금액을 읽어({@link FinancialExtractor})
	 * 분기·연간 실적과 증감을 계산한다({@link PerformanceCalculator}).
	 *
	 * @param companyId 회사 ID
	 * @return 최근 기간이 먼저. 수집된 재무가 없으면 빈 목록. 회사가 없으면 비어 있음
	 */
	public Optional<List<PerformanceSummary>> find(long companyId) {
		return companies.find(companyId).map(company -> PerformanceCalculator.calculate(records(companyId)));
	}

	/** 저장된 응답 한 건을 읽지 못해도 나머지 보고서로 실적을 만든다. */
	private List<FinancialRecord> records(long companyId) {
		List<FinancialRecord> records = new ArrayList<>();
		for (FinancialReport report : repository.findByCompany(companyId)) {
			try {
				records.addAll(FinancialExtractor.extract(report).records());
			}
			catch (RuntimeException e) {
				log.warn("재무 응답을 읽지 못해 건너뜁니다(회사 {} {}년 {}): {}", companyId, report.fiscalYear(),
					report.reportType(), e.getClass().getSimpleName());
			}
		}
		return records;
	}
}
