package com.companyinsight.company;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 기업 검색. */
@Service
@Transactional(readOnly = true)
public class CompanySearchService {

	private final CompanyRepository repository;

	public CompanySearchService(CompanyRepository repository) {
		this.repository = repository;
	}

	public List<CompanySummary> search(String keyword) {
		return repository.search(keyword.strip());
	}
}
