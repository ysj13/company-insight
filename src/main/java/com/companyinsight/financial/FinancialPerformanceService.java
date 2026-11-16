package com.companyinsight.financial;

import com.companyinsight.company.CompanySearchService;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 저장된 재무로 회사의 기간별 실적을 조회한다. */
@Service
@Transactional(readOnly = true)
public class FinancialPerformanceService {

	private final CompanySearchService companies;
	private final FinancialRepository repository;

	public FinancialPerformanceService(CompanySearchService companies, FinancialRepository repository) {
		this.companies = companies;
		this.repository = repository;
	}

	/**
	 * 회사 한 곳의 기간별 실적.
	 *
	 * @param companyId 회사 ID
	 * @return 최근 기간이 먼저. 수집된 재무가 없으면 빈 목록. 회사가 없으면 비어 있음
	 */
	public Optional<List<PeriodPerformance>> find(long companyId) {
		return companies.find(companyId)
			.map(company -> PerformanceCalculator.calculate(repository.findByCompany(companyId)));
	}
}
