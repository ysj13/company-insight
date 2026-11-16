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
	 * @param tone 판정 조건. 없으면 전체
	 * @param kind 공시 종류 조건. 없으면 전체
	 * @param q    회사명 일부 또는 종목코드 앞부분
	 * @param page 페이지 번호(1부터)
	 */
	@GetMapping(PATH)
	public String list(@RequestParam(required = false) ToneFilter tone, @RequestParam(required = false) DisclosureKind kind,
			@RequestParam(required = false) String q, @RequestParam(defaultValue = "1") int page, Model model) {
		DisclosureSearchCondition condition = new DisclosureSearchCondition(tone, kind, q);
		model.addAttribute("condition", condition);
		model.addAttribute("result", queryService.search(condition, page));
		model.addAttribute("links", new DisclosureListLinks(condition));
		model.addAttribute("tones", ToneFilter.values());
		model.addAttribute("kinds", DisclosureKind.values());
		return "disclosures/list";
	}
}
