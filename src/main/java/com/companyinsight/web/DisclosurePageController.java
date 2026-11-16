package com.companyinsight.web;

import com.companyinsight.disclosure.DisclosureKind;
import com.companyinsight.disclosure.DisclosureQueryService;
import com.companyinsight.disclosure.DisclosureSearchCondition;
import com.companyinsight.disclosure.ToneFilter;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 공시 목록 화면. 수집한 공시와 분류 결과(종류, 호재·악재, 영향도)를 최근 접수 순으로 보여준다.
 */
@Controller
public class DisclosurePageController {

	static final String PATH = "/disclosures";

	private final DisclosureQueryService queryService;

	public DisclosurePageController(DisclosureQueryService queryService) {
		this.queryService = queryService;
	}

	/**
	 * 조건에 맞는 공시 목록 한 페이지를 보여준다. 판정·종류 값이 정해진 이름이 아니거나 페이지가 숫자가 아니면 400이다.
	 *
	 * @param tone 판정 조건. 없으면 전체
	 * @param kind 공시 종류 조건. 없으면 전체
	 * @param q    회사명 일부 또는 종목코드 앞부분. 이름이 맞는 회사를 모두 보여준다
	 * @param corp 고른 회사의 DART 고유번호. 그 회사의 공시만 보여준다. 검색어를 새로 넣으면 검색어가 우선한다
	 * @param page 페이지 번호(1부터)
	 */
	@GetMapping(PATH)
	public String list(@RequestParam(required = false) ToneFilter tone, @RequestParam(required = false) DisclosureKind kind,
			@RequestParam(required = false) String q, @RequestParam(required = false) String corp,
			@RequestParam(defaultValue = "1") int page, Model model) {
		DisclosureSearchCondition condition = new DisclosureSearchCondition(tone, kind, q, corp);
		model.addAttribute("condition", condition);
		model.addAttribute("result", queryService.search(condition, page));
		model.addAttribute("links", new DisclosureListLinks(condition));
		model.addAttribute("tones", ToneFilter.values());
		model.addAttribute("kinds", DisclosureKind.values());
		return "disclosures/list";
	}
}
