package com.companyinsight.company;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies")
public class CompanyController {

	private final CompanyRepository repository;

	public CompanyController(CompanyRepository repository) {
		this.repository = repository;
	}

	/** 기업명 일부 또는 종목코드 앞부분으로 검색. 최대 20건. */
	@GetMapping
	public List<CompanySummary> search(@RequestParam @NotBlank @Size(max = 50) String q) {
		return repository.search(q.trim());
	}
}
