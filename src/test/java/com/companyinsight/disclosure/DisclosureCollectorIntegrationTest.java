package com.companyinsight.disclosure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.companyinsight.TestcontainersConfiguration;
import com.companyinsight.common.dart.DartClient;
import com.companyinsight.common.dart.DisclosureListResponse;
import com.companyinsight.common.dart.DisclosureListResponse.Item;
import com.companyinsight.common.external.ErrorKind;
import com.companyinsight.common.external.ExternalApiException;
import com.companyinsight.common.external.SourcePauseRegistry;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DisclosureCollectorIntegrationTest {

	private static final LocalDate DAY = LocalDate.of(2026, 11, 13);

	/** 회사 지정 수집의 기간 (1년). */
	private static final LocalDate FROM = LocalDate.of(2025, 11, 21);
	private static final LocalDate TO = LocalDate.of(2026, 11, 20);

	@Autowired
	DisclosureCollector collector;

	@Autowired
	DisclosureClassificationService classification;

	@Autowired
	JdbcClient jdbc;

	@MockitoBean
	DartClient dart;

	@MockitoBean
	SourcePauseRegistry pauses;

	@BeforeEach
	void setUp() {
		jdbc.sql("DELETE FROM disclosure").update();
		jdbc.sql("DELETE FROM company").update();
		jdbc.sql("DELETE FROM collection_run").update();
		given(dart.isConfigured()).willReturn(true);
		given(pauses.pausedNow(any())).willReturn(java.util.Optional.empty());
	}

	@Test
	void 상장사_공시만_저장하고_회사_시장구분을_갱신한다() {
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 1,
			item("00126380", "005930", "Y", "20261113000003", "주요사항보고서(자기주식취득결정)    "),
			item("00999999", " ", "E", "20261113000002", "비상장사 공시"),
			item("00164779", "000660", "K", "20261113000001", "기업설명회(IR)개최")));

		DisclosureCollector.CollectResult result = collector.reconcile(DAY, DAY);

		assertThat(result.fetched()).isEqualTo(3);
		assertThat(result.saved()).isEqualTo(2);
		assertThat(jdbc.sql("SELECT report_name FROM disclosure WHERE source_key = '20261113000003'")
			.query(String.class).single()).isEqualTo("주요사항보고서(자기주식취득결정)");
		Map<String, Object> company = jdbc.sql("SELECT country, source, market, last_disclosed_on FROM company WHERE source_key = '00126380'")
			.query().singleRow();
		assertThat(company.get("country")).isEqualTo("KR");
		assertThat(company.get("source")).isEqualTo("DART");
		assertThat(company.get("market")).isEqualTo("Y");
		assertThat(company.get("last_disclosed_on").toString()).isEqualTo("2026-11-13");
	}

	@Test
	void 같은_공시가_다시_와도_한_번만_저장된다() {
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 1,
			item("00126380", "005930", "Y", "20261113000001", "공시")));

		collector.reconcile(DAY, DAY);
		DisclosureCollector.CollectResult second = collector.reconcile(DAY, DAY);

		assertThat(second.saved()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM disclosure").query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM company").query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void 공시는_출처의_번호가_아니라_회사의_내부_번호를_참조한다() {
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 1,
			item("00126380", "005930", "Y", "20261113000002", "주요사항보고서(자기주식취득결정)"),
			item("00164779", "000660", "K", "20261113000001", "기업설명회(IR)개최")));

		collector.reconcile(DAY, DAY);

		assertThat(jdbc.sql("""
			SELECT d.source || ':' || d.source_key || ' -> ' || c.source || ':' || c.source_key
			  FROM disclosure d JOIN company c ON c.id = d.company_id
			 ORDER BY d.source_key
			""").query(String.class).list())
			.containsExactly("DART:20261113000001 -> DART:00164779", "DART:20261113000002 -> DART:00126380");
	}

	@Test
	void 같은_번호라도_출처가_다르면_다른_회사와_다른_공시로_저장된다() {
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 1,
			item("00126380", "005930", "Y", "20261113000001", "공시")));
		collector.reconcile(DAY, DAY);

		// 아직 없는 출처를 직접 넣는다. 다른 나라의 회사 번호와 공시 번호가 DART의 값과 우연히 같은 경우다
		long other = jdbc.sql("""
			INSERT INTO company (country, source, source_key, stock_code, name)
			VALUES ('US', 'OTHER', '00126380', 'ABC', 'Other Corp') RETURNING id
			""").query(Long.class).single();
		jdbc.sql("""
			INSERT INTO disclosure (source, source_key, company_id, report_name, rcept_date)
			VALUES ('OTHER', '20261113000001', :company, 'Annual report', DATE '2026-11-13')
			""").param("company", other).update();
		// DART를 다시 받아도 다른 출처의 행을 건드리지 않고, DART 공시는 여전히 한 번만 있다
		DisclosureCollector.CollectResult again = collector.reconcile(DAY, DAY);

		assertThat(again.saved()).isZero();
		assertThat(jdbc.sql("SELECT source FROM disclosure ORDER BY source").query(String.class).list())
			.containsExactly("DART", "OTHER");
		assertThat(jdbc.sql("SELECT source FROM company ORDER BY source").query(String.class).list())
			.containsExactly("DART", "OTHER");
	}

	@Test
	void 출처와_번호가_같은_공시와_회사는_DB가_막는다() {
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 1,
			item("00126380", "005930", "Y", "20261113000001", "공시")));
		collector.reconcile(DAY, DAY);

		assertThatThrownBy(() -> jdbc.sql("""
			INSERT INTO disclosure (source, source_key, company_id, report_name, rcept_date)
			SELECT 'DART', '20261113000001', id, '중복', DATE '2026-11-13' FROM company
			""").update()).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
		assertThatThrownBy(() -> jdbc.sql("""
			INSERT INTO company (country, source, source_key, stock_code, name)
			VALUES ('KR', 'DART', '00126380', '005930', '중복')
			""").update()).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
	}

	@Test
	void 보정_수집은_모든_페이지를_읽는다() {
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 2, item("1", "000001", "Y", "20261113000002", "a")));
		given(dart.listDisclosures(DAY, DAY, 2)).willReturn(page(2, 2, item("2", "000002", "K", "20261113000001", "b")));

		DisclosureCollector.CollectResult result = collector.reconcile(DAY, DAY);

		assertThat(result.pages()).isEqualTo(2);
		assertThat(result.saved()).isEqualTo(2);
	}

	@Test
	void 폴링은_새_공시가_없는_페이지에서_멈춘다() {
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 3, item("1", "000001", "Y", "20261113000003", "a")));
		given(dart.listDisclosures(DAY, DAY, 2)).willReturn(page(2, 3, item("2", "000002", "K", "20261113000002", "b")));
		given(dart.listDisclosures(DAY, DAY, 3)).willReturn(page(3, 3));
		collector.reconcile(DAY, DAY);
		clearInvocations(dart);
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 3,
			item("3", "000003", "Y", "20261113000004", "new"),
			item("1", "000001", "Y", "20261113000003", "a")));

		DisclosureCollector.CollectResult result = pollAt(DAY);

		assertThat(result.saved()).isEqualTo(1);
		assertThat(result.pages()).isEqualTo(2);
		verify(dart, never()).listDisclosures(eq(DAY), eq(DAY), eq(3));
	}

	@Test
	void 직전_폴링이_실패했으면_멈추지_않고_끝까지_읽는다() {
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 2, item("1", "000001", "Y", "20261113000002", "a")));
		given(dart.listDisclosures(DAY, DAY, 2))
			.willThrow(ExternalApiException.of(DartClient.SOURCE, ErrorKind.TRANSIENT, "HTTP503", "서버 오류"))
			.willReturn(page(2, 2, item("2", "000002", "K", "20261113000001", "b")));
		assertThatThrownBy(() -> pollAt(DAY)).isInstanceOf(ExternalApiException.class); // 1페이지만 저장됨

		DisclosureCollector.CollectResult result = pollAt(DAY); // 1페이지에 새 공시가 없어도 2페이지까지 읽어야 한다

		assertThat(result.pages()).isEqualTo(2);
		assertThat(result.saved()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM disclosure").query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void 직전_폴링이_중단된_채_RUNNING이면_끝까지_읽고_성공_뒤에는_다시_일찍_멈춘다() {
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 2, item("1", "000001", "Y", "20261113000002", "a")));
		given(dart.listDisclosures(DAY, DAY, 2)).willReturn(page(2, 2, item("2", "000002", "K", "20261113000001", "b")));
		collector.reconcile(DAY, DAY);
		// 시작 시각을 분명한 과거로 넣는다. DB의 now()로 넣으면 앱이 자기 시계로 남기는 기록과 시계가 섞여,
		// DB 시계가 조금만 앞서도 이 행이 계속 "가장 최근 실행"으로 잡혀 두 번째 폴링이 끝까지 읽는다
		jdbc.sql("INSERT INTO collection_run (source, started_at, status) VALUES (:s, now() - interval '1 hour', 'RUNNING')")
			.param("s", DisclosureCollector.SOURCE_POLL).update();

		DisclosureCollector.CollectResult afterCrash = pollAt(DAY);
		DisclosureCollector.CollectResult afterSuccess = pollAt(DAY);

		assertThat(afterCrash.pages()).isEqualTo(2);
		assertThat(afterSuccess.pages()).isEqualTo(1);
	}

	@Test
	void 너무_긴_값은_잘라서_저장하고_형식이_틀린_공시는_건너뛴다() {
		String longName = "가".repeat(400);
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 1,
			item("1", "000001", "Y", "20261113000003", longName),
			new Item("2", "회사2", "000002", "K", "날짜가 틀린 공시", "20261113000002", "제출인", "2026-11-13", ""),
			item("3", "000003", "Y", "20261113000001", "정상")));

		DisclosureCollector.CollectResult result = collector.reconcile(DAY, DAY);

		assertThat(result.saved()).isEqualTo(2);
		assertThat(jdbc.sql("SELECT length(report_name) FROM disclosure WHERE source_key = '20261113000003'")
			.query(Integer.class).single()).isEqualTo(300);
	}

	@Test
	void 저장할_때_제목_규칙으로_분류한다() {
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 1,
			item("1", "000001", "Y", "20261113000004", "주요사항보고서(자기주식취득결정)    "),
			item("2", "000002", "K", "20261113000003", "[기재정정]분기보고서 (2026.09)"),
			item("3", "000003", "Y", "20261113000002", "연결재무제표기준영업(잠정)실적(공정공시)"),
			itemWithRemark("4", "000004", "K", "20261113000001", "임원ㆍ주요주주특정증권등거래계획보고서", "철")));

		collector.reconcile(DAY, DAY);

		assertThat(classificationOf("20261113000004")).containsEntry("type_code", "E2").containsEntry("tone", "POSITIVE")
			.containsEntry("impact", null).containsEntry("classified_by", "RULE")
			.containsEntry("title_rule_version", 1).containsEntry("normalized_title", "주요사항보고서(자기주식취득결정)")
			.containsEntry("attributes", "")
			.containsEntry("is_correction", false).containsEntry("is_withdrawn", false);
		assertThat(classificationOf("20261113000004").get("title_rule_id")).isNotNull();
		assertThat(classificationOf("20261113000003")).containsEntry("type_code", "A1").containsEntry("tone", "NEUTRAL")
			.containsEntry("normalized_title", "분기보고서").containsEntry("is_correction", true);
		assertThat(classificationOf("20261113000002")).containsEntry("type_code", "B1").containsEntry("tone", null)
			.containsEntry("classified_by", null).containsEntry("attributes", "FAIR");
		assertThat(classificationOf("20261113000001")).containsEntry("type_code", "A3").containsEntry("tone", "NEUTRAL")
			.containsEntry("is_withdrawn", true);
	}

	@Test
	void 나중에_철회된_공시는_다시_수집할_때_중립으로_돌린다() {
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 1,
			itemWithRemark("1", "000001", "Y", "20261113000001", "주식소각결정", "유")));
		collector.reconcile(DAY, DAY);
		assertThat(classificationOf("20261113000001")).containsEntry("tone", "POSITIVE");

		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 1,
			itemWithRemark("1", "000001", "Y", "20261113000001", "주식소각결정", "유철")));
		DisclosureCollector.CollectResult second = collector.reconcile(DAY, DAY);

		assertThat(second.saved()).isZero();
		assertThat(classificationOf("20261113000001")).containsEntry("remark", "유철").containsEntry("is_withdrawn", true)
			.containsEntry("tone", "NEUTRAL").containsEntry("impact", null).containsEntry("classified_by", "RULE");
	}

	@Test
	void LLM이_판정한_공시도_철회되면_중립으로_돌린다() {
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 1,
			itemWithRemark("1", "000001", "Y", "20261113000001", "단일판매ㆍ공급계약체결", "유")));
		collector.reconcile(DAY, DAY);
		jdbc.sql("UPDATE disclosure SET tone = 'POSITIVE', impact = 2, classified_by = 'LLM'").update();

		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 1,
			itemWithRemark("1", "000001", "Y", "20261113000001", "단일판매ㆍ공급계약체결", "유철")));
		collector.reconcile(DAY, DAY);

		assertThat(classificationOf("20261113000001")).containsEntry("is_withdrawn", true).containsEntry("tone", "NEUTRAL")
			.containsEntry("impact", null).containsEntry("classified_by", "RULE");
	}

	@Test
	void 판정이_없는_공시에는_영향도를_넣을_수_없다() {
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 1,
			item("1", "000001", "Y", "20261113000001", "연결재무제표기준영업(잠정)실적(공정공시)")));
		collector.reconcile(DAY, DAY);

		assertThatThrownBy(() -> jdbc.sql("UPDATE disclosure SET impact = 2").update())
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("UPDATE disclosure SET tone = 'NEUTRAL', classified_by = 'RULE', impact = 1").update())
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}

	@Test
	void 비고에_정정_표시만_붙으면_비고만_바꾸고_판정은_그대로_둔다() {
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 1,
			itemWithRemark("1", "000001", "Y", "20261113000001", "연결재무제표기준영업(잠정)실적(공정공시)", "유")));
		collector.reconcile(DAY, DAY);
		// LLM이 판정한 뒤라고 가정한다
		jdbc.sql("UPDATE disclosure SET tone = 'POSITIVE', impact = 2, classified_by = 'LLM'").update();

		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 1,
			itemWithRemark("1", "000001", "Y", "20261113000001", "연결재무제표기준영업(잠정)실적(공정공시)", "유정")));
		collector.reconcile(DAY, DAY);

		assertThat(classificationOf("20261113000001")).containsEntry("remark", "유정").containsEntry("tone", "POSITIVE")
			.containsEntry("impact", 2).containsEntry("classified_by", "LLM").containsEntry("is_withdrawn", false);
	}

	@Test
	void 옛_대응표_버전의_공시를_다시_분류하고_LLM이_판정한_공시는_그대로_둔다() {
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 1,
			item("1", "000001", "Y", "20261113000003", "주식소각결정"),
			item("2", "000002", "K", "20261113000002", "단일판매ㆍ공급계약체결"),
			item("3", "000003", "Y", "20261113000001", "[기재정정]생산중단")));
		collector.reconcile(DAY, DAY);
		// 대응표가 생기기 전에 저장된 행처럼 되돌린다. 한 건은 그 사이 LLM이 판정했다.
		jdbc.sql("""
			UPDATE disclosure SET type_code = NULL, title_rule_id = NULL, normalized_title = NULL, tone = NULL, impact = NULL,
			       classified_by = NULL, title_rule_version = 0, is_correction = false
			""").update();
		jdbc.sql("""
			UPDATE disclosure SET tone = 'POSITIVE', impact = 3, classified_by = 'LLM'
			 WHERE source_key = '20261113000002'
			""").update();

		int reclassified = classification.reclassifyOutdated();

		assertThat(reclassified).isEqualTo(2);
		assertThat(classificationOf("20261113000003")).containsEntry("type_code", "E1").containsEntry("tone", "POSITIVE")
			.containsEntry("title_rule_version", 1);
		assertThat(classificationOf("20261113000001")).containsEntry("type_code", "I4").containsEntry("tone", "NEGATIVE")
			.containsEntry("is_correction", true);
		assertThat(classificationOf("20261113000002")).containsEntry("classified_by", "LLM").containsEntry("impact", 3)
			.containsEntry("type_code", null).containsEntry("title_rule_version", 0);
		assertThat(classification.reclassifyOutdated()).isZero();
	}

	@Test
	void 외부_오류는_분류와_코드를_기록하고_소스를_정지시킨다() {
		ExternalApiException quota = ExternalApiException.of(DartClient.SOURCE, ErrorKind.QUOTA_EXCEEDED, "020", "요청 제한을 초과하였습니다.");
		given(dart.listDisclosures(any(), any(), eq(1))).willThrow(quota);

		assertThatThrownBy(() -> collector.reconcile(DAY, DAY)).isSameAs(quota);

		Map<String, Object> run = jdbc.sql("SELECT source, status, error_kind, error_code, finished_at FROM collection_run")
			.query().singleRow();
		assertThat(run.get("source")).isEqualTo(DisclosureCollector.SOURCE_RECONCILE);
		assertThat(run.get("status")).isEqualTo("FAILED");
		assertThat(run.get("error_kind")).isEqualTo("QUOTA_EXCEEDED");
		assertThat(run.get("error_code")).isEqualTo("020");
		assertThat(run.get("finished_at")).isNotNull();
		verify(pauses).pauseFor(quota);
	}

	@Test
	void 정지된_소스는_호출하지_않는다() {
		given(pauses.pausedNow(DartClient.SOURCE)).willReturn(java.util.Optional.of(
			new SourcePauseRegistry.Pause(java.time.Instant.MAX, ErrorKind.QUOTA_EXCEEDED, "020")));

		assertThat(collector.reconcile(DAY, DAY).skipped()).isTrue();
		verify(dart, never()).listDisclosures(any(), any(), org.mockito.ArgumentMatchers.anyInt());
		assertThat(jdbc.sql("SELECT count(*) FROM collection_run").query(Long.class).single()).isZero();
	}

	@Test
	void 키가_없으면_건너뛴다() {
		given(dart.isConfigured()).willReturn(false);

		assertThat(collector.poll().skipped()).isTrue();
		assertThat(jdbc.sql("SELECT count(*) FROM collection_run").query(Long.class).single()).isZero();
	}

	@Test
	void 종목코드로_지정한_회사의_공시를_고유번호로_조회해_모든_페이지를_저장한다() {
		insertCompany("00126380", "005930", "삼성전자");
		given(dart.listDisclosures("00126380", FROM, TO, 1)).willReturn(page(1, 3,
			itemOn("00126380", "005930", "Y", "20261030000101", "연결재무제표기준영업(잠정)실적(공정공시)", "20261030"),
			itemOn("00126380", "005930", "Y", "20260716000123", "임원ㆍ주요주주특정증권등소유상황보고서", "20260716")));
		given(dart.listDisclosures("00126380", FROM, TO, 2)).willReturn(page(2, 3,
			itemOn("00126380", "005930", "Y", "20260310000456", "사업보고서 (2025.12)", "20260310")));
		given(dart.listDisclosures("00126380", FROM, TO, 3)).willReturn(page(3, 3,
			itemOn("00126380", "005930", "Y", "20251121000789", "주식등의대량보유상황보고서(일반)", "20251121")));

		DisclosureCollector.CompanyCollectResult result = collector.collectByStockCodes(List.of("005930"), FROM, TO);

		assertThat(result).isEqualTo(new DisclosureCollector.CompanyCollectResult(1, 1, 4, 4));
		assertThat(jdbc.sql("SELECT source_key FROM disclosure ORDER BY source_key").query(String.class).list())
			.containsExactly("20251121000789", "20260310000456", "20260716000123", "20261030000101");
		// 기간이 여러 날이어도 공시마다 자기 접수일로 저장된다
		assertThat(jdbc.sql("SELECT rcept_date::text FROM disclosure WHERE source_key = '20260310000456'")
			.query(String.class).single()).isEqualTo("2026-03-10");
		// 회사 지정 수집은 폴링·보정과 다른 작업 이름으로 남는다
		Map<String, Object> run = jdbc
			.sql("SELECT source, status, fetched::int AS fetched, saved::int AS saved, pages::int AS pages FROM collection_run")
			.query().singleRow();
		assertThat(run).containsEntry("source", "DART_DISCLOSURE_COMPANY").containsEntry("status", "SUCCESS")
			.containsEntry("fetched", 4).containsEntry("saved", 4).containsEntry("pages", 3);
		// 회사를 지정하지 않는 조회는 부르지 않는다
		verify(dart, never()).listDisclosures(any(), any(), anyInt());
	}

	@Test
	void 회사_지정_수집을_두_번_돌려도_이미_있던_공시와_겹쳐도_한_번만_저장된다() {
		insertCompany("00164779", "000660", "SK하이닉스");
		// 정기 수집이 먼저 받아 둔 공시
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 1,
			item("00164779", "000660", "Y", "20261113000001", "기업설명회(IR)개최")));
		collector.reconcile(DAY, DAY);
		given(dart.listDisclosures("00164779", FROM, TO, 1)).willReturn(page(1, 1,
			item("00164779", "000660", "Y", "20261113000001", "기업설명회(IR)개최"),
			itemOn("00164779", "000660", "Y", "20260123000002", "현금ㆍ현물배당결정", "20260123")));

		DisclosureCollector.CompanyCollectResult first = collector.collectByStockCodes(List.of("000660"), FROM, TO);
		DisclosureCollector.CompanyCollectResult second = collector.collectByStockCodes(List.of("000660"), FROM, TO);

		assertThat(first.fetched()).isEqualTo(2);
		assertThat(first.saved()).isEqualTo(1);
		assertThat(second.fetched()).isEqualTo(2);
		assertThat(second.saved()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM disclosure").query(Long.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("SELECT count(*) FROM company").query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void 없는_종목코드와_회사가_여럿인_종목코드는_건너뛰고_나머지_회사를_받는다() {
		insertCompany("00126380", "005930", "삼성전자");
		insertCompany("00000001", "111111", "옛 회사");
		insertCompany("00000002", "111111", "새 회사");
		given(dart.listDisclosures("00126380", FROM, TO, 1)).willReturn(page(1, 1,
			itemOn("00126380", "005930", "Y", "20260129000001", "현금ㆍ현물배당결정", "20260129")));

		DisclosureCollector.CompanyCollectResult result = collector
			.collectByStockCodes(List.of("999999", "111111", "005930"), FROM, TO);

		assertThat(result).isEqualTo(new DisclosureCollector.CompanyCollectResult(3, 1, 1, 1));
		verify(dart, never()).listDisclosures(eq("00000001"), any(), any(), anyInt());
		verify(dart, never()).listDisclosures(eq("00000002"), any(), any(), anyInt());
		assertThat(jdbc.sql("SELECT count(*) FROM collection_run").query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void 한_회사가_실패해도_다음_회사는_받고_실패는_기록에_남는다() {
		insertCompany("00126380", "005930", "삼성전자");
		insertCompany("00164742", "005380", "현대자동차");
		given(dart.listDisclosures("00126380", FROM, TO, 1))
			.willThrow(ExternalApiException.of(DartClient.SOURCE, ErrorKind.TRANSIENT, "IO", "연결 실패"));
		given(dart.listDisclosures("00164742", FROM, TO, 1)).willReturn(page(1, 1,
			itemOn("00164742", "005380", "Y", "20260423000003", "연결재무제표기준영업(잠정)실적(공정공시)", "20260423")));

		DisclosureCollector.CompanyCollectResult result = collector.collectByStockCodes(List.of("005930", "005380"), FROM,
			TO);

		assertThat(result).isEqualTo(new DisclosureCollector.CompanyCollectResult(2, 1, 1, 1));
		assertThat(jdbc.sql("SELECT status FROM collection_run WHERE source = 'DART_DISCLOSURE_COMPANY' ORDER BY id")
			.query(String.class).list()).containsExactly("FAILED", "SUCCESS");
	}

	@Test
	void 한도_초과로_소스가_정지되면_다음_회사는_호출하지_않는다() {
		insertCompany("00126380", "005930", "삼성전자");
		insertCompany("00164742", "005380", "현대자동차");
		ExternalApiException quota = ExternalApiException.of(DartClient.SOURCE, ErrorKind.QUOTA_EXCEEDED, "020",
			"요청 제한을 초과하였습니다.");
		given(dart.listDisclosures("00126380", FROM, TO, 1)).willReturn(page(1, 2,
			itemOn("00126380", "005930", "Y", "20261030000101", "연결재무제표기준영업(잠정)실적(공정공시)", "20261030")));
		given(dart.listDisclosures("00126380", FROM, TO, 2)).willThrow(quota);
		// 실패를 기록하면서 소스가 정지된다. 첫 회사는 정지 전, 둘째 회사는 정지 뒤다
		given(pauses.pausedNow(DartClient.SOURCE)).willReturn(java.util.Optional.empty(), java.util.Optional.of(
			new SourcePauseRegistry.Pause(java.time.Instant.MAX, ErrorKind.QUOTA_EXCEEDED, "020")));

		DisclosureCollector.CompanyCollectResult result = collector.collectByStockCodes(List.of("005930", "005380"), FROM,
			TO);

		// 실패한 회사가 그 전까지 받은 1건은 결과에서 빠지지만 DB와 기록에는 남는다
		assertThat(result).isEqualTo(new DisclosureCollector.CompanyCollectResult(2, 0, 0, 0));
		verify(pauses).pauseFor(quota);
		verify(dart, never()).listDisclosures(eq("00164742"), any(), any(), anyInt());
		assertThat(jdbc.sql("SELECT count(*) FROM disclosure").query(Long.class).single()).isEqualTo(1);
		Map<String, Object> run = jdbc
			.sql("SELECT status, error_kind, fetched::int AS fetched, saved::int AS saved FROM collection_run").query()
			.singleRow();
		assertThat(run).containsEntry("status", "FAILED").containsEntry("error_kind", "QUOTA_EXCEEDED")
			.containsEntry("fetched", 1).containsEntry("saved", 1);
	}

	@Test
	void 오래된_페이지를_나중에_읽어도_마지막_공시일과_시장_구분이_뒤로_가지_않는다() {
		insertCompany("00126380", "005930", "삼성전자");
		// 최신 공시는 유가증권(Y), 1년 전 공시는 코스닥(K)으로 실려 온 경우
		given(dart.listDisclosures("00126380", FROM, TO, 1)).willReturn(page(1, 2,
			itemOn("00126380", "005930", "Y", "20261030000101", "기업설명회(IR)개최", "20261030")));
		given(dart.listDisclosures("00126380", FROM, TO, 2)).willReturn(page(2, 2,
			itemOn("00126380", "005930", "K", "20251121000789", "기업설명회(IR)개최", "20251121")));

		collector.collectByStockCodes(List.of("005930"), FROM, TO);
		collector.collectByStockCodes(List.of("005930"), FROM, TO);

		Map<String, Object> company = jdbc
			.sql("SELECT market, last_disclosed_on::text AS last FROM company WHERE source_key = '00126380'").query()
			.singleRow();
		assertThat(company).containsEntry("market", "Y").containsEntry("last", "2026-10-30");
	}

	@Test
	void 회사_지정_수집으로_다시_받으면_나중에_철회된_공시를_중립으로_돌린다() {
		insertCompany("00126380", "005930", "삼성전자");
		given(dart.listDisclosures("00126380", FROM, TO, 1)).willReturn(page(1, 1,
			new Item("00126380", "삼성전자", "005930", "Y", "주식소각결정", "20260129000001", "삼성전자", "20260129", "유")));
		collector.collectByStockCodes(List.of("005930"), FROM, TO);
		assertThat(classificationOf("20260129000001")).containsEntry("tone", "POSITIVE");

		given(dart.listDisclosures("00126380", FROM, TO, 1)).willReturn(page(1, 1,
			new Item("00126380", "삼성전자", "005930", "Y", "주식소각결정", "20260129000001", "삼성전자", "20260129", "유철")));
		collector.collectByStockCodes(List.of("005930"), FROM, TO);

		assertThat(classificationOf("20260129000001")).containsEntry("remark", "유철").containsEntry("is_withdrawn", true)
			.containsEntry("tone", "NEUTRAL");
	}

	@Test
	void 기간에_공시가_없는_회사는_0건으로_끝난다() {
		insertCompany("00164742", "005380", "현대자동차");
		given(dart.listDisclosures("00164742", FROM, TO, 1))
			.willReturn(new DisclosureListResponse("013", "조회된 데이타가 없습니다.", 1, 0, 0, List.of()));

		DisclosureCollector.CompanyCollectResult result = collector.collectByStockCodes(List.of("005380"), FROM, TO);

		assertThat(result).isEqualTo(new DisclosureCollector.CompanyCollectResult(1, 1, 0, 0));
		assertThat(jdbc.sql("SELECT status FROM collection_run").query(String.class).single()).isEqualTo("SUCCESS");
	}

	@Test
	void 종목코드_목록이_비어_있으면_아무것도_부르지_않고_같은_종목코드는_한_번만_받는다() {
		insertCompany("00126380", "005930", "삼성전자");
		given(dart.listDisclosures("00126380", FROM, TO, 1)).willReturn(page(1, 1));

		DisclosureCollector.CompanyCollectResult empty = collector.collectByStockCodes(List.of(), FROM, TO);

		assertThat(empty).isEqualTo(new DisclosureCollector.CompanyCollectResult(0, 0, 0, 0));
		assertThat(jdbc.sql("SELECT count(*) FROM collection_run").query(Long.class).single()).isZero();

		DisclosureCollector.CompanyCollectResult twice = collector.collectByStockCodes(List.of("005930", "005930"), FROM,
			TO);

		assertThat(twice.requested()).isEqualTo(1);
		verify(dart, times(1)).listDisclosures("00126380", FROM, TO, 1);
	}

	@Test
	void 소스가_정지_중이면_회사_지정_수집도_호출하지_않는다() {
		insertCompany("00126380", "005930", "삼성전자");
		given(pauses.pausedNow(DartClient.SOURCE)).willReturn(java.util.Optional.of(
			new SourcePauseRegistry.Pause(java.time.Instant.MAX, ErrorKind.QUOTA_EXCEEDED, "020")));

		DisclosureCollector.CompanyCollectResult result = collector.collectByStockCodes(List.of("005930"), FROM, TO);

		assertThat(result).isEqualTo(new DisclosureCollector.CompanyCollectResult(1, 0, 0, 0));
		verify(dart, never()).listDisclosures(any(), any(), any(), anyInt());
	}

	/** poll()은 Clock 기준 "오늘"을 쓰므로, 테스트 날짜로 reconcile과 같은 경로를 태우기 위해 폴링 모드를 직접 호출한다. */
	private DisclosureCollector.CollectResult pollAt(LocalDate day) {
		return collector.pollDay(day);
	}

	private static DisclosureListResponse page(int pageNo, int totalPage, Item... items) {
		return new DisclosureListResponse("000", "정상", pageNo, items.length, totalPage, List.of(items));
	}

	private static Item item(String corpCode, String stockCode, String cls, String rceptNo, String name) {
		return itemWithRemark(corpCode, stockCode, cls, rceptNo, name, "");
	}

	/** 접수일을 지정한 공시. */
	private static Item itemOn(String corpCode, String stockCode, String cls, String rceptNo, String name, String day) {
		return new Item(corpCode, "회사" + corpCode, stockCode, cls, name, rceptNo, "제출인", day, "");
	}

	private void insertCompany(String corpCode, String stockCode, String name) {
		jdbc.sql("""
			INSERT INTO company (country, source, source_key, stock_code, name)
			VALUES ('KR', 'DART', :corpCode, :stockCode, :name)
			""").param("corpCode", corpCode).param("stockCode", stockCode).param("name", name).update();
	}

	private static Item itemWithRemark(String corpCode, String stockCode, String cls, String rceptNo, String name,
			String remark) {
		return new Item(corpCode, "회사" + corpCode, stockCode, cls, name, rceptNo, "제출인", "20261113", remark);
	}

	private Map<String, Object> classificationOf(String rceptNo) {
		return jdbc.sql("""
			SELECT type_code, title_rule_id, normalized_title, array_to_string(title_attributes, ',') AS attributes,
			       tone, impact::int AS impact, classified_by, title_rule_version, is_correction, is_withdrawn, remark
			  FROM disclosure WHERE source = 'DART' AND source_key = :no
			""").param("no", rceptNo).query().singleRow();
	}
}
