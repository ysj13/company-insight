package com.companyinsight.company;

import java.util.List;
import java.util.Optional;
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

	/**
	 * @param keyword 회사명 일부 또는 종목코드 앞부분
	 * @return 최대 20건
	 */
	public List<CompanySummary> search(String keyword) {
		return repository.search(keyword.strip());
	}

	/**
	 * 회사 ID로 회사 한 곳을 찾는다.
	 *
	 * @param id 회사 ID
	 * @return 없으면 비어 있음
	 */
	public Optional<CompanySummary> find(long id) {
		return repository.findById(id);
	}
}
