package com.companyinsight.analysis.table;

import static org.assertj.core.api.Assertions.assertThat;

import com.companyinsight.TestcontainersConfiguration;
import com.companyinsight.analysis.body.DisclosureBodyRepository;
import com.companyinsight.analysis.table.CorrectionBundles.BundleState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 정정 공시를 앞 공시와 잇는 것을 실제 PostgreSQL 위에서 확인한다. 제목·날짜의 모양은 실제 공시에서 본 것을 따른다
 * (거래소 서식은 바로 앞 판의 제출일, 금융위원회 서식은 원래 공시의 최초 제출일을 적는다).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CorrectionBundleIntegrationTest {

	private static final String CONTRACT = "단일판매ㆍ공급계약체결";

	private static final String RIGHTS = "주요사항보고서(유상증자결정)";

	private static final LocalDate D0 = LocalDate.of(2025, 8, 29);

	private static final LocalDate D1 = LocalDate.of(2025, 12, 30);

	private static final LocalDate D2 = LocalDate.of(2026, 6, 30);

	@Autowired
	CorrectionBundles bundles;

	@Autowired
	DisclosureBodyRepository bodies;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate transaction;

	private long companyId;

	private long otherCompanyId;

	private int receipt;

	@BeforeEach
	void setUp() {
		clean();
		companyId = company("00111111", "111111");
		otherCompanyId = company("00222222", "222222");
	}

	@AfterEach
	void clean() {
		jdbc.sql("DELETE FROM disclosure_body").update();
		jdbc.sql("UPDATE disclosure SET superseded_by = NULL, bundle_id = NULL, corrects_id = NULL, correction_link = NULL").update();
		jdbc.sql("DELETE FROM disclosure").update();
		jdbc.sql("DELETE FROM company").update();
	}

	@Test
	void 거래소_서식은_바로_앞_판의_날을_따라_사슬로_잇고_가장_늦은_정정이_나머지를_대체한다() {
		long original = disclosure(companyId, CONTRACT, D0);
		long first = disclosure(companyId, "[기재정정]" + CONTRACT, D1);
		long second = disclosure(companyId, "[기재정정] " + CONTRACT, D2);

		link(first, D0);
		link(second, D1);

		assertThat(state(original)).isEqualTo(new BundleState(null, null, null, original, second, D0));
		assertThat(state(first)).isEqualTo(new BundleState(D0, original, "LINKED", original, second, D0));
		assertThat(state(second)).isEqualTo(new BundleState(D1, first, "LINKED", original, null, D0));
	}

	@Test
	void 금융위_서식은_정정마다_원래_공시의_날을_적어도_한_묶음이다() {
		long original = disclosure(companyId, RIGHTS, D0);
		long first = disclosure(companyId, "[기재정정]" + RIGHTS, D1);
		long second = disclosure(companyId, "[기재정정]" + RIGHTS, D2);

		linkFirstFiling(first, D0);
		linkFirstFiling(second, D0);

		assertThat(state(second)).isEqualTo(new BundleState(D0, original, "LINKED", original, null, D0));
		assertThat(state(first).supersededBy()).isEqualTo(second);
		assertThat(state(original).supersededBy()).isEqualTo(second);
	}

	@Test
	void 늦은_정정을_먼저_읽어도_결과가_같다() {
		long original = disclosure(companyId, CONTRACT, D0);
		long first = disclosure(companyId, "[기재정정]" + CONTRACT, D1);
		long second = disclosure(companyId, "[기재정정]" + CONTRACT, D2);

		link(second, D1);
		link(first, D0);
		link(first, D0);

		assertThat(state(original).supersededBy()).isEqualTo(second);
		assertThat(state(first).supersededBy()).isEqualTo(second);
		assertThat(state(second)).isEqualTo(new BundleState(D1, first, "LINKED", original, null, D0));
	}

	@Test
	void 원래_공시가_저장돼_있지_않으면_맨_앞_정정이_고친_날을_최초_제출일로_쓴다() {
		LocalDate old = LocalDate.of(2023, 9, 15);
		long first = disclosure(companyId, "[기재정정]" + CONTRACT, D1);
		long second = disclosure(companyId, "[기재정정]" + CONTRACT, D2);

		link(first, old);
		link(second, D1);

		assertThat(state(first)).isEqualTo(new BundleState(old, null, "NO_PREDECESSOR", first, second, old));
		assertThat(state(second)).isEqualTo(new BundleState(D1, first, "LINKED", first, null, old));
	}

	@Test
	void 정정_한_건뿐이고_앞_공시가_없어도_최초_제출일은_적는다() {
		long only = disclosure(companyId, "[기재정정]" + CONTRACT, D1);

		link(only, LocalDate.of(2021, 9, 3));

		assertThat(state(only)).isEqualTo(new BundleState(LocalDate.of(2021, 9, 3), null, "NO_PREDECESSOR", null, null,
				LocalDate.of(2021, 9, 3)));
	}

	@Test
	void 그날_앞_공시가_없으면_다음_날_낸_공시와_잇는다() {
		// 금융위 서식의 최초 제출일이 접수일보다 하루 앞서 적힌 것 (주요사항보고서 789건 중 124건)
		long original = disclosure(companyId, RIGHTS, D0.plusDays(1));
		long correction = disclosure(companyId, "[기재정정]" + RIGHTS, D1);

		linkFirstFiling(correction, D0);

		assertThat(state(correction)).isEqualTo(new BundleState(D0, original, "LINKED", original, null, D0.plusDays(1)));
	}

	@Test
	void 제목이_같은_공시가_없으면_유형과_꼬리를_뗀_제목으로_찾되_정정_전_값이_맞아야_잇는다() {
		long original = disclosure(companyId, "주주총회소집결의", D0, "E9", "주주총회소집결의");
		body(original, "<table><tr><td>1. 일시</td><td>2026-01-15 오전 10시</td></tr></table>");
		long correction = disclosure(companyId, "[기재정정]주주총회소집결의 (임시주주총회)", D1, "E9", "주주총회소집결의");

		link(correction, D0, "2026-01-15 오전 10시");

		assertThat(state(correction).correctsId()).isEqualTo(original);
	}

	@Test
	void 같은_날_같은_제목의_다른_사건이_여럿이면_정정_전_값이_든_본문을_고른다() {
		long a = disclosure(companyId, CONTRACT, D0);
		long b = disclosure(companyId, CONTRACT, D0);
		body(a, "<table><tr><td>계약금액(원)</td><td>95,437,300,000</td></tr><tr><td>매출액대비(%)</td><td>34.25</td></tr>"
				+ "<tr><td>종료일</td><td>2025-12-31</td></tr></table>");
		body(b, "<table><tr><td>계약금액(원)</td><td>13,116,947,800</td></tr><tr><td>매출액대비(%)</td><td>14.45</td></tr>"
				+ "<tr><td>종료일</td><td>2025-12-31</td></tr></table>");
		long correction = disclosure(companyId, "[기재정정]" + CONTRACT, D1);

		link(correction, D0, "95,437,300,000 34.25", "2025-12-31");

		assertThat(state(correction).correctsId()).isEqualTo(a);
		assertThat(state(a).supersededBy()).isEqualTo(correction);
		assertThat(state(b).supersededBy()).isNull();
	}

	@Test
	void 정정_전_값으로도_고르지_못하면_잇지_않는다() {
		long a = disclosure(companyId, CONTRACT, D0);
		long b = disclosure(companyId, CONTRACT, D0);
		body(a, "<table><tr><td>계약기간 종료일</td><td>2025-12-31</td></tr></table>");
		body(b, "<table><tr><td>계약기간 종료일</td><td>2025-12-31</td></tr></table>");
		long correction = disclosure(companyId, "[기재정정]" + CONTRACT, D1);

		link(correction, D0, "2025-12-31");

		assertThat(state(correction)).isEqualTo(new BundleState(D0, null, "AMBIGUOUS", null, null, D0));
		assertThat(state(a).supersededBy()).isNull();
		assertThat(state(b).supersededBy()).isNull();
	}

	@Test
	void 후보가_여럿이어도_한_묶음이면_가장_늦은_것과_잇는다() {
		// 같은 날 원래 공시와 그 정정이 함께 나왔다
		long original = disclosure(companyId, CONTRACT, D0);
		long sameDay = disclosure(companyId, "[기재정정]" + CONTRACT, D0);
		link(sameDay, D0);
		long later = disclosure(companyId, "[기재정정]" + CONTRACT, D1);

		link(later, D0);

		assertThat(state(later).correctsId()).isEqualTo(sameDay);
		assertThat(state(original).supersededBy()).isEqualTo(later);
	}

	@Test
	void 제출일을_읽지_못하거나_접수일보다_늦으면_잇지_않는다() {
		long original = disclosure(companyId, CONTRACT, D0);
		long unread = disclosure(companyId, "[기재정정]" + CONTRACT, D1);
		long future = disclosure(companyId, "[기재정정]" + CONTRACT, D1);

		link(unread, null);
		link(future, LocalDate.of(2026, 12, 1));

		assertThat(state(unread)).isEqualTo(new BundleState(null, null, "NO_DATE", null, null, null));
		assertThat(state(future)).isEqualTo(new BundleState(null, null, "NO_DATE", null, null, null));
		assertThat(state(original)).isEqualTo(new BundleState(null, null, null, null, null, null));
	}

	@Test
	void 다시_읽어_앞_공시가_바뀌면_예전_묶음을_풀고_새_묶음에_잇는다() {
		long before = disclosure(companyId, CONTRACT, D0);
		long after = disclosure(companyId, CONTRACT, LocalDate.of(2025, 9, 1));
		long correction = disclosure(companyId, "[기재정정]" + CONTRACT, D1);
		link(correction, D0);

		link(correction, LocalDate.of(2025, 9, 1));

		assertThat(state(before)).isEqualTo(new BundleState(null, null, null, null, null, null));
		assertThat(state(after)).isEqualTo(new BundleState(null, null, null, after, correction, LocalDate.of(2025, 9, 1)));
		assertThat(state(correction).correctsId()).isEqualTo(after);
	}

	@Test
	void 다른_회사_첨부정정_철회된_공시는_앞_공시가_되지_않는다() {
		disclosure(otherCompanyId, CONTRACT, D0);
		disclosure(companyId, "[첨부정정]" + CONTRACT, D0);
		long withdrawn = disclosure(companyId, CONTRACT, D0);
		jdbc.sql("UPDATE disclosure SET is_withdrawn = true WHERE id = :id").param("id", withdrawn).update();
		long correction = disclosure(companyId, "[기재정정]" + CONTRACT, D1);

		link(correction, D0);

		assertThat(state(correction).correctionLink()).isEqualTo("NO_PREDECESSOR");
	}

	@Test
	void 당국이_원래_공시에_뒤에_붙인_머리말은_원래_공시로_본다() {
		// 슈프리마에이치큐: 원래 공시의 제목이 뒤에 "[정정명령부과]주요사항보고서(자기주식처분결정)"으로 바뀌었다
		String title = "주요사항보고서(자기주식처분결정)";
		long original = disclosure(companyId, "[정정명령부과]" + title, LocalDate.of(2026, 1, 16));
		long correction = disclosure(companyId, "[정정명령부과][기재정정]" + title, LocalDate.of(2026, 1, 20));

		link(correction, LocalDate.of(2026, 1, 16));

		assertThat(state(original).supersededBy()).isEqualTo(correction);
		assertThat(state(original).firstFiledOn()).isEqualTo(LocalDate.of(2026, 1, 16));
	}

	@Test
	void 제목의_띄어쓰기와_가운뎃점이_달라도_같은_제목으로_본다() {
		long original = disclosure(companyId, "단일판매ㆍ공급계약 체결", D0);
		long correction = disclosure(companyId, "[기재정정]단일판매·공급계약체결", D1);

		link(correction, D0);

		assertThat(state(original).supersededBy()).isEqualTo(correction);
	}

	@Test
	void 회사_판단에는_묶음의_마지막_하나를_최초_제출일로_세고_첨부정정과_잇지_못한_정정은_세지_않는다() {
		long original = disclosure(companyId, CONTRACT, D0);
		long first = disclosure(companyId, "[기재정정]" + CONTRACT, D1);
		long second = disclosure(companyId, "[기재정정]" + CONTRACT, D2);
		long attachment = disclosure(companyId, "[첨부정정]" + CONTRACT, D2);
		link(first, D0);
		link(second, D1);
		// 같은 날 같은 제목의 결정 둘과, 어느 쪽을 고쳤는지 모르는 정정
		long a = disclosure(companyId, RIGHTS, D1);
		long b = disclosure(companyId, RIGHTS, D1);
		long ambiguous = disclosure(companyId, "[기재정정]" + RIGHTS, D2);
		link(ambiguous, D1);
		long unread = disclosure(companyId, "[기재정정]" + RIGHTS, D2);
		link(unread, null);
		long plain = disclosure(companyId, "주식소각결정", D2);
		// 앞 공시가 저장돼 있지 않은 정정은 센다
		long orphan = disclosure(companyId, "[기재정정]타인에대한채무보증결정", D2);
		link(orphan, LocalDate.of(2024, 3, 2));

		List<java.util.Map<String, Object>> counted = jdbc.sql("SELECT id, event_date FROM disclosure_counted ORDER BY id")
			.query()
			.listOfRows();

		assertThat(counted).extracting(row -> ((Number) row.get("id")).longValue())
			.containsExactly(second, a, b, plain, orphan);
		assertThat(counted).extracting(row -> row.get("event_date").toString())
			.containsExactly(D0.toString(), D1.toString(), D1.toString(), D2.toString(), "2024-03-02");
		assertThat(List.of(original, first, attachment, ambiguous, unread)).doesNotContainAnyElementsOf(
				counted.stream().map(row -> ((Number) row.get("id")).longValue()).toList());
	}

	@Test
	void 꼬리를_뗀_제목으로_찾은_후보는_정정_전_값이_맞지_않으면_잇지_않는다() {
		long original = disclosure(companyId, "주주총회소집결의", D0, "E9", "주주총회소집결의");
		body(original, "<table><tr><td>1. 일시</td><td>2026-03-20 오전 9시</td></tr></table>");
		long correction = disclosure(companyId, "[기재정정]주주총회소집결의 (임시주주총회)", D1, "E9", "주주총회소집결의");

		link(correction, D0, "2026-01-15 오전 10시");

		assertThat(state(correction).correctionLink()).isEqualTo("AMBIGUOUS");
		assertThat(counted()).containsExactly(original);
	}

	@Test
	void 거래소_서식은_다음_날의_다른_공시와_잇지_않는다() {
		// 원래 공시가 수집 기간 밖이고, 다음 날 같은 제목의 다른 계약이 있다
		long other = disclosure(companyId, CONTRACT, D0.plusDays(1));
		long correction = disclosure(companyId, "[기재정정]" + CONTRACT, D1);

		link(correction, D0);

		assertThat(state(correction).correctionLink()).isEqualTo("NO_PREDECESSOR");
		assertThat(counted()).containsExactly(other, correction);
	}

	@Test
	void 후보_가운데_본문이_없는_것이_있으면_고르지_않는다() {
		long a = disclosure(companyId, CONTRACT, D0);
		long b = disclosure(companyId, CONTRACT, D0);
		body(b, "<table><tr><td>종료일</td><td>2025-12-31</td></tr></table>");
		long correction = disclosure(companyId, "[기재정정]" + CONTRACT, D1);

		link(correction, D0, "2025-12-31");

		assertThat(state(correction).correctionLink()).isEqualTo("AMBIGUOUS");
		assertThat(counted()).containsExactly(a, b);
	}

	@Test
	void 고르지_못한_정정에_뒤_정정이_이어져도_세지_않고_원래_공시를_센다() {
		long a = disclosure(companyId, CONTRACT, D0);
		long b = disclosure(companyId, CONTRACT, D0);
		long first = disclosure(companyId, "[기재정정]" + CONTRACT, D1);
		link(first, D0);
		long second = disclosure(companyId, "[기재정정]" + CONTRACT, D2);

		link(second, D1);

		assertThat(state(second).correctsId()).isEqualTo(first);
		assertThat(counted()).containsExactly(a, b);
	}

	@Test
	void 아직_읽지_않은_정정과_그_뒤_정정은_세지_않는다() {
		long original = disclosure(companyId, CONTRACT, D0);
		long unread = disclosure(companyId, "[기재정정]" + CONTRACT, D1);
		long later = disclosure(companyId, "[기재정정]" + CONTRACT, D2);

		link(later, D1);

		assertThat(state(later).correctsId()).isEqualTo(unread);
		assertThat(counted()).containsExactly(original);
		// 앞 정정을 읽으면 셋이 한 묶음이 되어 마지막 하나를 원래 공시의 날로 센다
		link(unread, D0);
		assertThat(counted()).containsExactly(later);
		assertThat(state(later).firstFiledOn()).isEqualTo(D0);
	}

	@Test
	void 먼저_읽은_정정이_고르지_못했어도_앞_정정이_이어지면_다시_잇는다() {
		// 금융위 서식: 원래 공시와 첫 정정이 같은 날 나왔고, 둘째 정정(최초 제출일 = 원래 공시의 날)을 먼저 읽었다
		long original = disclosure(companyId, RIGHTS, D0);
		long first = disclosure(companyId, "[기재정정]" + RIGHTS, D0);
		long second = disclosure(companyId, "[기재정정]" + RIGHTS, D1);
		linkFirstFiling(second, D0);
		assertThat(state(second).correctionLink()).isEqualTo("AMBIGUOUS");

		linkFirstFiling(first, D0);

		assertThat(state(second).correctionLink()).isEqualTo("LINKED");
		assertThat(state(original).supersededBy()).isEqualTo(second);
		assertThat(counted()).containsExactly(second);
	}

	@Test
	void 본문을_고친_정정에_첨부추가가_함께_붙어도_묶음의_마지막이면_센다() {
		long original = disclosure(companyId, CONTRACT, D0);
		long correction = disclosure(companyId, "[기재정정][첨부추가]" + CONTRACT, D1);

		link(correction, D0);

		assertThat(state(original).supersededBy()).isEqualTo(correction);
		assertThat(counted()).containsExactly(correction);
	}

	@Test
	void 정정_전_값에서_숫자가_든_네_글자_이상만_견준다() {
		assertThat(CorrectionBundles.tokens(List.of("95,437,300,000 34.25", "- 종료일 : 2026-02-18", "-", "주1) 정정 전", "8.5",
				"8.22% 1,295,482주 343,860,000,000원", "181,139,…")))
			.containsExactly("95,437,300,000", "34.25", "2026-02-18", "8.22", "1,295,482", "343,860,000,000");
	}

	/** 거래소 서식("정정관련 공시서류제출일")으로 읽은 정정을 잇는다. */
	private void link(long id, LocalDate date, String... beforeValues) {
		transaction.executeWithoutResult(status -> bundles.link(id, date, false, List.of(beforeValues)));
	}

	/** 금융위원회 서식("최초제출일")으로 읽은 정정을 잇는다. */
	private void linkFirstFiling(long id, LocalDate date, String... beforeValues) {
		transaction.executeWithoutResult(status -> bundles.link(id, date, true, List.of(beforeValues)));
	}

	private List<Long> counted() {
		return jdbc.sql("SELECT id FROM disclosure_counted ORDER BY id").query(Long.class).list();
	}

	private BundleState state(long id) {
		return bundles.find(id).orElseThrow();
	}

	private void body(long id, String html) {
		bodies.save(id, TestZips.single("body.html", "<html><body>" + html + "</body></html>"), 1, Instant.now());
	}

	private long company(String corpCode, String stockCode) {
		return jdbc.sql("""
				INSERT INTO company (source, source_key, stock_code, name, country)
				VALUES ('DART', :corpCode, :stockCode, :corpCode, 'KR') RETURNING id
				""")
			.param("corpCode", corpCode)
			.param("stockCode", stockCode)
			.query(Long.class)
			.single();
	}

	private long disclosure(long company, String reportName, LocalDate receivedOn) {
		return disclosure(company, reportName, receivedOn, null, null);
	}

	private long disclosure(long company, String reportName, LocalDate receivedOn, String typeCode, String normalizedTitle) {
		receipt++;
		return jdbc.sql("""
				INSERT INTO disclosure (source, source_key, company_id, report_name, rcept_date, type_code, normalized_title)
				VALUES ('DART', :key, :company, :name, :date, :typeCode, :normalizedTitle) RETURNING id
				""")
			.param("key", receivedOn.toString().replace("-", "") + String.format("%06d", receipt))
			.param("company", company)
			.param("name", reportName)
			.param("date", receivedOn)
			.param("typeCode", typeCode)
			.param("normalizedTitle", normalizedTitle)
			.query(Long.class)
			.single();
	}
}
