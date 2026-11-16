package com.companyinsight.web;

import com.companyinsight.disclosure.DisclosureSearchCondition;
import com.companyinsight.disclosure.ToneFilter;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 공시 목록 화면 안에서 쓰는 링크 주소를 만든다.
 * 지금 조건을 유지한 채 판정이나 페이지만 바꾼 주소가 필요하고, 비어 있는 조건은 주소에 넣지 않는다.
 *
 * @param condition 지금 화면의 조건
 */
public record DisclosureListLinks(DisclosureSearchCondition condition) {

	/**
	 * 판정만 바꾼 주소. 조건이 달라지므로 첫 페이지로 간다.
	 *
	 * @param tone 고를 판정. null이면 전체
	 */
	public String tone(ToneFilter tone) {
		return build(tone, 1);
	}

	/** 지금 조건에서 페이지만 바꾼 주소. */
	public String page(int page) {
		return build(condition.tone(), page);
	}

	/** 고른 회사를 풀고 나머지 조건은 유지한 주소. */
	public String allCompanies() {
		return new DisclosureListLinks(
			new DisclosureSearchCondition(condition.tone(), condition.kind(), condition.keyword(), null)).page(1);
	}

	private String build(ToneFilter tone, int page) {
		UriComponentsBuilder uri = UriComponentsBuilder.fromPath(DisclosurePageController.PATH);
		if (tone != null) {
			uri.queryParam("tone", tone.name());
		}
		if (condition.kind() != null) {
			uri.queryParam("kind", condition.kind().name());
		}
		if (condition.keyword() != null) {
			uri.queryParam("q", "{q}");
		}
		if (condition.corpCode() != null) {
			// 숫자 8자리만 통과한 값이라 인코딩할 글자가 없다
			uri.queryParam("corp", condition.corpCode());
		}
		if (page > 1) {
			uri.queryParam("page", page);
		}
		// 검색어는 변수로 넣어야 &, +, 공백, 한글까지 모두 인코딩된다. 값을 그대로 붙이면 &와 +가 남는다
		return uri.encode().buildAndExpand(condition.keyword() == null ? "" : condition.keyword()).toUriString();
	}
}
