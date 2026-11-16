package com.companyinsight.company;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 기업 조회 API. */
@RestController
@RequestMapping("/api/companies")
public class CompanyController {

	private final CompanySearchService searchService;

	public CompanyController(CompanySearchService searchService) {
		this.searchService = searchService;
	}

	/**
	 * 기업 검색. 기업명 일부 또는 종목코드 앞부분으로 찾는다.
	 *
	 * @param q 기업명 일부 또는 종목코드 앞부분. 비어 있을 수 없고 50자까지
	 * @return 최대 20건. 정확히 일치, 앞부분 일치, 포함 순
	 */
	@GetMapping
	public List<CompanySummary> search(@RequestParam @NotBlank @Size(max = 50) String q) {
		return searchService.search(q);
	}
}
