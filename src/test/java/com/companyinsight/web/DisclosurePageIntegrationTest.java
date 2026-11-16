package com.companyinsight.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
			.andExpect(content().string(containsString("출처: 금융감독원 전자공시시스템(DART)")))
			.andExpect(content().string(containsString("AI 요약은 틀릴 수 있으며 투자 권유가 아닙니다")))
			// 5건뿐이라 페이지 이동이 없다
			.andExpect(content().string(not(containsString("class=\"pager\""))));
	}

	// 아래 표시 검증은 종류로 걸러 한 줄만 나오게 한 뒤 마크업으로 확인한다. 도움말에도 같은 낱말이 있어 글자만 찾으면 항상 통과한다.

	@Test
	void 호재_공시는_호재_배지와_영향을_보여주고_후속_철회_표시는_없다() throws Exception {
		mvc.perform(get("/disclosures").param("kind", "MAJOR"))
			.andExpect(content().string(containsString("class=\"pill tone-positive\"")))
			.andExpect(content().string(containsString("<span class=\"impact\">영향 보통</span>")))
			.andExpect(content().string(not(containsString("<span class=\"tag\">"))))
			.andExpect(content().string(not(containsString("title withdrawn"))));
	}

	@Test
	void 판정하지_않은_공시는_미판정_배지만_있고_영향은_없다() throws Exception {
		mvc.perform(get("/disclosures").param("kind", "EARNINGS"))
			.andExpect(content().string(containsString("class=\"pill tone-undecided\"")))
			.andExpect(content().string(not(containsString("class=\"impact\""))));
	}

	@Test
	void 머리말이_붙은_공시에는_후속_공시_표시가_붙는다() throws Exception {
		mvc.perform(get("/disclosures").param("kind", "PERIODIC"))
			.andExpect(content().string(containsString("[기재정정]분기보고서 (2026.09)")))
			.andExpect(content().string(containsString("<span class=\"tag\">후속 공시</span>")))
			.andExpect(content().string(not(containsString("<span class=\"tag\">철회</span>"))));
	}

	@Test
	void 철회된_공시는_철회_표시와_함께_제목에_취소선을_긋는다() throws Exception {
		mvc.perform(get("/disclosures").param("kind", "OWNERSHIP"))
			.andExpect(content().string(containsString("<span class=\"tag\">철회</span>")))
			.andExpect(content().string(containsString("class=\"title withdrawn\"")))
			.andExpect(content().string(containsString("class=\"pill tone-neutral\"")))
			.andExpect(content().string(not(containsString("<span class=\"tag\">후속 공시</span>"))));
	}

	@Test
	void 다음_페이지_링크는_조건을_유지한다() throws Exception {
		for (int i = 0; i < 60; i++) {
			disclosure("202611100%05d".formatted(i), "00126380", "기업설명회(IR)개최(안내공시)", "2026-11-10", "IR", "NEUTRAL",
				null, false, false);
		}

		mvc.perform(get("/disclosures").param("kind", "IR").param("q", "삼성"))
			.andExpect(content().string(containsString("class=\"pager\"")))
			.andExpect(content().string(containsString("/disclosures?kind=IR&amp;q=%EC%82%BC%EC%84%B1&amp;page=2")));
	}

	@Test
	void 공시_제목과_검색어의_HTML_문자도_바꿔서_보여준다() throws Exception {
		disclosure("20261116000999", "00126380", "<script>alert(1)</script>", "2026-11-16", "ETC", null, null, false, false);

		mvc.perform(get("/disclosures").param("kind", "ETC"))
			.andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
			.andExpect(content().string(not(containsString("<script>"))));
		mvc.perform(get("/disclosures").param("q", "\"><i>"))
			.andExpect(content().string(containsString("value=\"&quot;&gt;&lt;i&gt;\"")));
	}

	@Test
	void 검색어의_제어_문자는_지우고_찾는다() throws Exception {
		mvc.perform(get("/disclosures").param("q", "\u0000삼성\u0000전자"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("주요사항보고서(자기주식취득결정)")));
		assertThat(new DisclosureSearchCondition(null, null, "\u0000 \t").keyword()).isNull();
		assertThat(new DisclosureSearchCondition(null, null, "😀".repeat(60)).keyword()).isEqualTo("😀".repeat(50));
		// QueryDSL이 LIKE 이스케이프에 쓰는 글자도 그대로 찾는다
		assertThat(items(service.search(new DisclosureSearchCondition(null, null, "!"), 1))).isEmpty();
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
	void 빈_조건은_전체로_보고_모르는_판정_값과_숫자가_아닌_페이지는_400() throws Exception {
		mvc.perform(get("/disclosures").param("tone", "").param("kind", "").param("q", ""))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("임원ㆍ주요주주특정증권등거래계획보고서")));
		mvc.perform(get("/disclosures").param("tone", "GOOD")).andExpect(status().isBadRequest());
		mvc.perform(get("/disclosures").param("page", "abc")).andExpect(status().isBadRequest());
	}

	@Test
	void 회사를_고르면_이름이_비슷한_회사는_빼고_그_회사_공시만_보여준다() {
		company("00126371", "009150", "삼성전기");
		disclosure("20261116000555", "00126371", "기업설명회(IR)개최(안내공시)", "2026-11-16", "IR", "NEUTRAL", null, false, false);

		DisclosurePage byName = service.search(new DisclosureSearchCondition(null, null, "삼성전"), 1);
		DisclosurePage picked = service.search(new DisclosureSearchCondition(null, null, null, "00126380"), 1);

		assertThat(items(byName)).extracting(DisclosureSummary::companyName)
			.containsExactlyInAnyOrder("삼성전자", "삼성전자", "삼성전기");
		assertThat(items(picked)).extracting(DisclosureSummary::receiptNo)
			.containsExactly("20261116000301", "20261112000077");
		assertThat(picked.total()).isEqualTo(2);
		assertThat(picked.toneCounts()).containsEntry(ToneFilter.POSITIVE, 1L).containsEntry(ToneFilter.NEUTRAL, 1L)
			.containsEntry(ToneFilter.NEGATIVE, 0L).containsEntry(ToneFilter.UNDECIDED, 0L);
		assertThat(picked.company().name()).isEqualTo("삼성전자");
		assertThat(items(service.search(new DisclosureSearchCondition(ToneFilter.NEUTRAL, DisclosureKind.OWNERSHIP, null, "00126380"), 1)))
			.extracting(DisclosureSummary::receiptNo).containsExactly("20261112000077");
	}

	@Test
	void 고른_회사를_화면에_보여주고_링크는_고른_회사를_유지한다() throws Exception {
		jdbc.sql("UPDATE company SET market = 'Y' WHERE corp_code = '00126380'").update();

		mvc.perform(get("/disclosures").param("corp", "00126380").param("kind", "MAJOR"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("<b>삼성전자</b>")))
			.andExpect(content().string(containsString("<span class=\"market\">KOSPI</span>")))
			.andExpect(content().string(containsString("의 공시만 보고 있습니다.")))
			.andExpect(content().string(containsString("name=\"corp\" value=\"00126380\"")))
			.andExpect(content().string(containsString("href=\"/disclosures?tone=POSITIVE&amp;kind=MAJOR&amp;corp=00126380\"")))
			// 모든 회사 보기: 종류는 유지하고 회사만 푼다
			.andExpect(content().string(containsString("href=\"/disclosures?kind=MAJOR\"")));
	}

	@Test
	void 회사를_고른_채로_검색어를_넣으면_검색어가_우선한다() throws Exception {
		mvc.perform(get("/disclosures").param("corp", "00126380").param("q", "하이닉스"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("연결재무제표기준영업(잠정)실적(공정공시)")))
			.andExpect(content().string(not(containsString("의 공시만 보고 있습니다."))))
			.andExpect(content().string(not(containsString("name=\"corp\""))));
	}

	@Test
	void 형식이_틀린_고유번호는_무시하고_없는_회사는_안내한다() throws Exception {
		// 따옴표, 7자리, 9자리, 전각 숫자, 앞뒤 공백. 모두 고르지 않은 것으로 보고 전체를 보여준다
		for (String wrong : List.of("1' OR '1'='1", "0012638", "001263800", "００１２６３８０", " 00126380")) {
			mvc.perform(get("/disclosures").param("corp", wrong))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("연결재무제표기준영업(잠정)실적(공정공시)")))
				.andExpect(content().string(not(containsString("class=\"picked\""))));
		}
		mvc.perform(get("/disclosures").param("corp", "99999999"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("고른 회사를 찾을 수 없습니다.")))
			.andExpect(content().string(containsString("조건에 맞는 공시가 없습니다.")));
	}

	@Test
	void 공백이나_제어_문자뿐인_검색어는_고른_회사를_풀지_않는다() throws Exception {
		mvc.perform(get("/disclosures").param("corp", "00126380").param("q", "\u0001 "))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("의 공시만 보고 있습니다.")))
			.andExpect(content().string(not(containsString("연결재무제표기준영업(잠정)실적(공정공시)"))));
		assertThat(new DisclosureSearchCondition(null, null, "삼성", "00126380").corpCode()).isNull();
	}

	@Test
	void 회사를_고른_채로_페이지를_넘겨도_고른_회사를_유지한다() throws Exception {
		for (int i = 0; i < 60; i++) {
			disclosure("202611100%05d".formatted(i), "00126380", "기업설명회(IR)개최(안내공시)", "2026-11-10", "IR", "NEUTRAL",
				null, false, false);
		}

		mvc.perform(get("/disclosures").param("corp", "00126380"))
			.andExpect(content().string(containsString("href=\"/disclosures?corp=00126380&amp;page=2\"")));
	}

	@Test
	void 회사_검색_API는_시장_구분을_함께_준다() throws Exception {
		company("01344336", "226330", "신테카바이오");
		company("00999002", "654321", "기타법인테스트");
		jdbc.sql("UPDATE company SET market = 'K' WHERE corp_code = '01344336'").update();
		jdbc.sql("UPDATE company SET market = 'E' WHERE corp_code = '00999002'").update();

		mvc.perform(get("/api/companies").param("q", "신테카바이오"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].corpCode").value("01344336"))
			.andExpect(jsonPath("$[0].market").value("KOSDAQ"));
		// 공시에서 시장 구분을 아직 받지 못한 회사
		mvc.perform(get("/api/companies").param("q", "삼성전자"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].name").value("삼성전자"))
			.andExpect(jsonPath("$[0].market").value(org.hamcrest.Matchers.nullValue()));
		// 상장 시장이 아닌 법인 구분(E)
		mvc.perform(get("/api/companies").param("q", "기타법인테스트"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].market").value(org.hamcrest.Matchers.nullValue()));
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
