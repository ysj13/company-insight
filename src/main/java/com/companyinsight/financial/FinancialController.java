package com.companyinsight.financial;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 기업 실적 조회 API. */
@RestController
@RequestMapping("/api/companies/{companyId}/financials")
public class FinancialController {

	private final FinancialPerformanceService performanceService;

	public FinancialController(FinancialPerformanceService performanceService) {
		this.performanceService = performanceService;
	}

	/**
	 * 기업의 기간별 실적. 매출액, 영업이익, 당기순이익과 전년 같은 기간 대비 증감, 영업이익률을 분기와 연간 단위로 준다.
	 * 출처는 금융감독원 전자공시시스템(DART)이고, 기간마다 근거 보고서의 원문 주소가 들어 있다.
	 *
	 * @param companyId 회사 ID. 기업 검색 결과의 id
	 * @return 최근 기간이 먼저. 같은 사업연도에서는 연간, 4분기, 3분기, 2분기, 1분기 순. 수집된 재무가 없으면 빈 배열
	 */
	@GetMapping
	public List<PeriodPerformance> list(@PathVariable long companyId) {
		return performanceService.find(companyId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "회사를 찾을 수 없습니다"));
	}
}
