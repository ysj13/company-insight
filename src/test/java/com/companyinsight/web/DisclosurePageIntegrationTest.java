package com.companyinsight.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.companyinsight.TestcontainersConfiguration;
import com.companyinsight.common.dart.DartClient;
import com.companyinsight.disclosure.DisclosureKind;
import com.companyinsight.disclosure.DisclosurePage;
import com.companyinsight.disclosure.DisclosureQueryService;
import com.companyinsight.disclosure.DisclosureSearchCondition;
import com.companyinsight.disclosure.DisclosureSummary;
import com.companyinsight.disclosure.ToneFilter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 공시 목록 화면과 조회 쿼리. 제목과 분류 값은 실제 DART 공시에서 가져왔고,
 * 분류 컬럼은 규칙을 거치지 않고 직접 넣어 조회만 검증한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DisclosurePageIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	DisclosureQueryService service;

	@Autowired
	JdbcClient jdbc;

	@MockitoBean
	DartClient dart;

	@BeforeEach
	void setUp() {
		jdbc.sql("DELETE FROM disclosure").update();
		jdbc.sql("DELETE FROM company").update();
		company("00126380", "005930", "삼성전자");
		company("00164779", "000660", "SK하이닉스");
		company("00999001", "123456", "100%_테스트&<b>");

		disclosure("20261116000301", "00126380", "주요사항보고서(자기주식취득결정)", "2026-11-16", "MAJOR", "POSITIVE", 2, false, false);
		disclosure("20261116000205", "00164779", "연결재무제표기준영업(잠정)실적(공정공시)", "2026-11-16", "EARNINGS", null, null, false, false);
		disclosure("20261113000410", "00164779", "[기재정정]분기보고서 (2026.09)", "2026-11-13", "PERIODIC", "NEUTRAL", null, true, false);
		disclosure("20261113000122", "00999001", "투자유의안내 (관리종목지정 우려 예고)", "2026-11-13", "MARKET", "NEGATIVE", 2, false, false);
		disclosure("20261112000077", "00126380", "임원ㆍ주요주주특정증권등거래계획보고서", "2026-11-12", "OWNERSHIP", "NEUTRAL", null, false, true);
	}

	@Test
	void 최근_접수_순으로_날짜별로_묶어_보여준다() {
		DisclosurePage page = service.search(new DisclosureSearchCondition(null, null, null), 1);

		assertThat(page.days()).extracting(DisclosurePage.Day::label)
			.containsExactly("2026년 11월 16일 (월)", "2026년 11월 13일 (금)", "2026년 11월 12일 (목)");
		assertThat(page.days().getFirst().items()).extracting(DisclosureSummary::receiptNo)
			.containsExactly("20261116000301", "20261116000205");
		assertThat(page.total()).isEqualTo(5);
		assertThat(page.toneCounts()).containsEntry(ToneFilter.POSITIVE, 1L).containsEntry(ToneFilter.NEGATIVE, 1L)
			.containsEntry(ToneFilter.NEUTRAL, 2L).containsEntry(ToneFilter.UNDECIDED, 1L);
	}

	@Test
	void 판정으로_거르고_판정별_건수는_전체_기준으로_유지한다() {
		DisclosurePage undecided = service.search(new DisclosureSearchCondition(ToneFilter.UNDECIDED, null, null), 1);
		DisclosurePage neutral = service.search(new DisclosureSearchCondition(ToneFilter.NEUTRAL, null, null), 1);

		assertThat(items(undecided)).extracting(DisclosureSummary::receiptNo).containsExactly("20261116000205");
		assertThat(undecided.matched()).isEqualTo(1);
		assertThat(undecided.total()).isEqualTo(5);
		assertThat(undecided.toneCounts()).containsEntry(ToneFilter.NEUTRAL, 2L);
		assertThat(items(neutral)).extracting(DisclosureSummary::receiptNo)
			.containsExactly("20261113000410", "20261112000077");
	}

	@Test
	void 종류와_회사로_거른다() {
		assertThat(items(service.search(new DisclosureSearchCondition(null, DisclosureKind.MARKET, null), 1)))
			.extracting(DisclosureSummary::receiptNo).containsExactly("20261113000122");
		assertThat(items(service.search(new DisclosureSearchCondition(null, null, " sk하이 "), 1)))
			.extracting(DisclosureSummary::companyName).containsExactly("SK하이닉스", "SK하이닉스");
		assertThat(items(service.search(new DisclosureSearchCondition(null, null, "0059"), 1)))
			.extracting(DisclosureSummary::stockCode).containsExactly("005930", "005930");
		assertThat(items(service.search(new DisclosureSearchCondition(ToneFilter.POSITIVE, DisclosureKind.MAJOR, "삼성"), 1)))
			.extracting(DisclosureSummary::receiptNo).containsExactly("20261116000301");
	}

	@Test
	void 검색어의_LIKE_특수문자는_글자_그대로_찾는다() {
		assertThat(items(service.search(new DisclosureSearchCondition(null, null, "%"), 1)))
			.extracting(DisclosureSummary::receiptNo).containsExactly("20261113000122");
		assertThat(items(service.search(new DisclosureSearchCondition(null, null, "_"), 1)))
			.extracting(DisclosureSummary::receiptNo).containsExactly("20261113000122");
		assertThat(items(service.search(new DisclosureSearchCondition(null, null, "없는회사"), 1))).isEmpty();
	}

	@Test
	void 한_페이지는_50건이고_범위를_벗어난_페이지는_끝_페이지로_맞춘다() {
		for (int i = 0; i < 100; i++) {
			disclosure("202611100%05d".formatted(i), "00126380", "기업설명회(IR)개최(안내공시)", "2026-11-10", "IR", "NEUTRAL",
				null, false, false);
		}

		DisclosurePage first = service.search(new DisclosureSearchCondition(null, null, null), 0);
		DisclosurePage last = service.search(new DisclosureSearchCondition(null, null, null), 99);

		assertThat(first.page()).isEqualTo(1);
		assertThat(first.totalPages()).isEqualTo(3);
		assertThat(items(first)).hasSize(50);
		assertThat(first.hasPrevious()).isFalse();
		assertThat(last.page()).isEqualTo(3);
		assertThat(items(last)).hasSize(5);
		assertThat(last.hasNext()).isFalse();
		// 페이지를 넘겨도 같은 공시가 두 번 나오거나 빠지지 않는다
		DisclosurePage second = service.search(new DisclosureSearchCondition(null, null, null), 2);
		assertThat(List.of(first, second, last).stream().flatMap(p -> items(p).stream())
			.map(DisclosureSummary::receiptNo).distinct()).hasSize(105);
	}

	@Test
	void 화면에_제목과_분류와_원문_링크와_고지가_나온다() throws Exception {
		mvc.perform(get("/disclosures"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("주요사항보고서(자기주식취득결정)")))
			.andExpect(content().string(containsString("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261116000301")))
			.andExpect(content().string(containsString("영향 보통")))
			.andExpect(content().string(containsString("미판정")))
			.andExpect(content().string(containsString("정정")))
			.andExpect(content().string(containsString("철회")))
			.andExpect(content().string(containsString("출처: 금융감독원 전자공시시스템(DART)")))
			.andExpect(content().string(containsString("AI 요약은 틀릴 수 있으며 투자 권유가 아닙니다")));
	}

	@Test
	void 회사명의_HTML_문자는_그대로_실행되지_않게_바꿔서_보여준다() throws Exception {
		mvc.perform(get("/disclosures").param("kind", "MARKET"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("100%_테스트&amp;&lt;b&gt;")))
			.andExpect(content().string(not(containsString("테스트&<b>"))));
	}

	@Test
	void 조건_링크는_다른_조건을_유지하고_검색어를_인코딩한다() throws Exception {
		mvc.perform(get("/disclosures").param("q", "테스트&").param("kind", "MARKET").param("tone", "NEGATIVE"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("투자유의안내 (관리종목지정 우려 예고)")))
			.andExpect(content().string(containsString("/disclosures?tone=POSITIVE&amp;kind=MARKET&amp;q=%ED%85%8C%EC%8A%A4%ED%8A%B8%26")));
	}

	@Test
	void 조건에_맞는_공시가_없으면_안내를_보여준다() throws Exception {
		mvc.perform(get("/disclosures").param("q", "없는회사"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("조건에 맞는 공시가 없습니다.")));
	}

	@Test
	void 빈_조건은_전체로_보고_모르는_판정_값은_400() throws Exception {
		mvc.perform(get("/disclosures").param("tone", "").param("kind", "").param("q", ""))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("임원ㆍ주요주주특정증권등거래계획보고서")));
		mvc.perform(get("/disclosures").param("tone", "GOOD")).andExpect(status().isBadRequest());
	}

	private static List<DisclosureSummary> items(DisclosurePage page) {
		return page.days().stream().flatMap(day -> day.items().stream()).toList();
	}

	private void company(String corpCode, String stockCode, String name) {
		jdbc.sql("INSERT INTO company (corp_code, stock_code, name) VALUES (:c, :s, :n)")
			.param("c", corpCode).param("s", stockCode).param("n", name).update();
	}

	private void disclosure(String rceptNo, String corpCode, String name, String date, String kind, String tone,
			Integer impact, boolean correction, boolean withdrawn) {
		jdbc.sql("""
			INSERT INTO disclosure (rcept_no, corp_code, report_name, rcept_date, kind, tone, impact, classified_by,
			                        rule_version, is_correction, is_withdrawn)
			VALUES (:no, :corp, :name, CAST(:date AS date), :kind, :tone, :impact, :by, 1, :correction, :withdrawn)
			""")
			.param("no", rceptNo).param("corp", corpCode).param("name", name).param("date", date).param("kind", kind)
			.param("tone", tone, java.sql.Types.VARCHAR).param("impact", impact, java.sql.Types.SMALLINT)
			.param("by", tone == null ? null : "RULE", java.sql.Types.VARCHAR)
			.param("correction", correction).param("withdrawn", withdrawn).update();
	}
}
