package com.companyinsight.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.companyinsight.TestcontainersConfiguration;
import com.companyinsight.common.dart.DartClient;
import com.companyinsight.common.dart.MajorAccountResponse;
import com.companyinsight.common.dart.MajorAccountResponse.Item;
import com.companyinsight.common.external.ErrorKind;
import com.companyinsight.common.external.ExternalApiException;
import com.companyinsight.common.external.SourcePauseRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class FinancialIntegrationTest {

	@Autowired
	FinancialCollector collector;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MockMvc mvc;

	@Autowired
	Clock clock;

	@MockitoBean
	DartClient dart;

	@MockitoBean
	SourcePauseRegistry pauses;

	long companyId;

	@BeforeEach
	void setUp() {
		jdbc.sql("DELETE FROM financial").update();
		jdbc.sql("DELETE FROM disclosure").update();
		jdbc.sql("DELETE FROM company").update();
		jdbc.sql("DELETE FROM collection_run").update();
		companyId = jdbc.sql("""
			INSERT INTO company (country, source, source_key, stock_code, name)
			VALUES ('KR', 'DART', '00126380', '005930', '삼성전자') RETURNING id
			""").query(Long.class).single();
		given(dart.isConfigured()).willReturn(true);
		given(pauses.pausedNow(any())).willReturn(Optional.empty());
		given(dart.fetchMajorAccounts(anyString(), anyInt(), anyString())).willReturn(Optional.empty());
	}

	/** 다른 통합 테스트가 company를 비울 때 참조 제약에 걸리지 않게 치운다. */
	@AfterEach
	void tearDown() {
		jdbc.sql("DELETE FROM financial").update();
	}

	@Test
	void 회사_한_곳의_응답을_받아_그대로_저장하고_다시_받아도_변경이_없다() throws IOException {
		given(dart.fetchMajorAccounts("00126380", 2025, "11014")).willReturn(Optional.of(FinancialExtractorTest.realJson()));

		FinancialCollector.CollectResult first = collector.collect(companyId, 2025, 2025);
		FinancialCollector.CollectResult second = collector.collect(companyId, 2025, 2025);

		// 보고서 네 종류를 한 번씩 조회한다
		verify(dart, times(2)).fetchMajorAccounts("00126380", 2025, "11013");
		verify(dart, times(2)).fetchMajorAccounts("00126380", 2025, "11012");
		verify(dart, times(2)).fetchMajorAccounts("00126380", 2025, "11014");
		verify(dart, times(2)).fetchMajorAccounts("00126380", 2025, "11011");
		// 응답 1건을 받아 1건을 새로 저장. 다시 받으면 내용이 같아 새로 저장·변경 0건
		assertThat(first).isEqualTo(new FinancialCollector.CollectResult(false, 1, 0, 1, 1));
		assertThat(second).isEqualTo(new FinancialCollector.CollectResult(false, 1, 0, 1, 0));

		// 아직 나오지 않은 보고서(응답 없음)는 행이 없다
		assertThat(jdbc.sql("SELECT count(*) FROM financial").query(Long.class).single()).isEqualTo(1);
		Map<String, Object> row = jdbc.sql("""
			SELECT source, fiscal_year, report_type, jsonb_array_length(payload -> 'list') AS lines,
			       (SELECT e ->> 'thstrm_dt' FROM jsonb_array_elements(payload -> 'list') e
			         WHERE e ->> 'fs_div' = 'CFS' AND e ->> 'account_nm' = '매출액') AS period,
			       (SELECT e ->> 'thstrm_amount' FROM jsonb_array_elements(payload -> 'list') e
			         WHERE e ->> 'fs_div' = 'CFS' AND e ->> 'account_nm' = '자산총계') AS total_assets
			  FROM financial WHERE company_id = :id
			""").param("id", companyId).query().singleRow();
		assertThat(row.get("source")).isEqualTo("DART");
		assertThat(row.get("fiscal_year")).isEqualTo(2025);
		assertThat(row.get("report_type")).isEqualTo("Q3");
		// 화면에 쓰지 않는 계정과 필드도 응답 그대로 들어 있다
		assertThat(row.get("lines")).isEqualTo(14);
		assertThat(row.get("period")).isEqualTo("2025.01.01 ~ 2025.09.30");
		assertThat(row.get("total_assets")).isEqualTo("523,659,586,000,000");

		assertThat(jdbc.sql("SELECT status, fetched, saved FROM collection_run WHERE source = :s ORDER BY id")
			.param("s", FinancialCollector.JOB).query().listOfRows())
			.extracting(r -> r.get("status") + ":" + r.get("fetched") + ":" + r.get("saved"))
			.containsExactly("SUCCESS:1:1", "SUCCESS:1:0");
	}

	@Test
	void 다시_받으면_받은_시각을_고치고_내용이_바뀐_경우에만_수정_시각을_바꾼다() {
		given(dart.fetchMajorAccounts("00126380", 2025, "11013")).willReturn(response(
			line("20250515000001", "매출액", "CFS", "1,000", "1")));
		collector.collect(companyId, 2025, 2025);
		// 시각을 분명한 과거로 돌려 놓고 본다. 시계의 해상도에 기대지 않기 위해서다
		String rewind = "UPDATE financial SET created_at = now() - interval '1 day', "
				+ "updated_at = now() - interval '1 day', fetched_at = now() - interval '1 day'";
		String recent = "SELECT (fetched_at > now() - interval '1 hour') || ' ' || (updated_at > now() - interval '1 hour')"
				+ " || ' ' || (created_at > now() - interval '1 hour') FROM financial";
		jdbc.sql(rewind).update();

		FinancialCollector.CollectResult same = collector.collect(companyId, 2025, 2025);
		assertThat(same.saved()).isZero();
		assertThat(jdbc.sql(recent).query(String.class).single()).isEqualTo("true false false");

		// 정정 보고서가 나와 매출액과 근거 접수번호가 바뀐 경우
		jdbc.sql(rewind).update();
		given(dart.fetchMajorAccounts("00126380", 2025, "11013")).willReturn(response(
			line("20250601000009", "매출액", "CFS", "1,200", "1")));
		FinancialCollector.CollectResult changed = collector.collect(companyId, 2025, 2025);
		assertThat(changed.saved()).isEqualTo(1);
		assertThat(jdbc.sql(recent).query(String.class).single()).isEqualTo("true true false");
		assertThat(jdbc.sql("SELECT count(*) FROM financial").query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT payload -> 'list' -> 0 ->> 'thstrm_amount' FROM financial")
			.query(String.class).single()).isEqualTo("1,200");
	}

	@Test
	void 공백과_키_순서만_다른_응답은_같은_내용으로_본다() {
		given(dart.fetchMajorAccounts("00126380", 2025, "11013")).willReturn(Optional.of(
			"{\"status\":\"000\",\"message\":\"정상\",\"list\":[{\"account_nm\":\"매출액\",\"thstrm_amount\":\"1,000\"}]}"));
		collector.collect(companyId, 2025, 2025);

		given(dart.fetchMajorAccounts("00126380", 2025, "11013")).willReturn(Optional.of(
			"{ \"list\": [ { \"thstrm_amount\": \"1,000\", \"account_nm\": \"매출액\" } ], \"message\": \"정상\", \"status\": \"000\" }"));

		assertThat(collector.collect(companyId, 2025, 2025).saved()).isZero();
	}

	@Test
	void 정상_응답인데_계정이_없으면_저장된_응답을_덮어쓰지_않는다() throws Exception {
		given(dart.fetchMajorAccounts("00126380", 2025, "11014")).willReturn(Optional.of(FinancialExtractorTest.realJson()));
		collector.collect(companyId, 2025, 2025);

		given(dart.fetchMajorAccounts("00126380", 2025, "11014"))
			.willReturn(Optional.of("{\"status\":\"000\",\"message\":\"정상\"}"));
		FinancialCollector.CollectResult noList = collector.collect(companyId, 2025, 2025);
		given(dart.fetchMajorAccounts("00126380", 2025, "11014"))
			.willReturn(Optional.of("{\"status\":\"000\",\"message\":\"정상\",\"list\":[]}"));
		FinancialCollector.CollectResult emptyList = collector.collect(companyId, 2025, 2025);

		assertThat(noList).isEqualTo(new FinancialCollector.CollectResult(false, 0, 1, 1, 0));
		assertThat(emptyList).isEqualTo(new FinancialCollector.CollectResult(false, 0, 1, 1, 0));
		assertThat(jdbc.sql("SELECT jsonb_array_length(payload -> 'list') FROM financial").query(Integer.class).single())
			.isEqualTo(14);
		mvc.perform(get("/api/companies/{id}/financials", companyId))
			.andExpect(jsonPath("$[0].revenue.changeRate").value(8.8));
	}

	@Test
	void 읽을_수_있는_계정이_없는_응답은_이미_저장된_쓸_수_있는_응답을_덮어쓰지_않는다() throws Exception {
		given(dart.fetchMajorAccounts("00126380", 2025, "11014")).willReturn(Optional.of(FinancialExtractorTest.realJson()));
		collector.collect(companyId, 2025, 2025);

		// 줄은 있지만 금액을 읽을 수 없는 응답
		given(dart.fetchMajorAccounts("00126380", 2025, "11014")).willReturn(response(
			line("20251114002447", "매출액", "CFS", "-", "23"),
			line("20251114002447", "영업이익", "CFS", "", "25")));
		FinancialCollector.CollectResult result = collector.collect(companyId, 2025, 2025);

		assertThat(result).isEqualTo(new FinancialCollector.CollectResult(false, 0, 1, 1, 0));
		assertThat(jdbc.sql("SELECT jsonb_array_length(payload -> 'list') FROM financial").query(Integer.class).single())
			.isEqualTo(14);
		mvc.perform(get("/api/companies/{id}/financials", companyId))
			.andExpect(jsonPath("$[0].revenue.changeRate").value(8.8));
	}

	@Test
	void 처음_받는_응답은_읽을_수_있는_계정이_없어도_저장한다() throws Exception {
		// 계정명이 다른 업종처럼, 지금 규칙으로는 읽지 못하는 응답. 규칙을 고치면 읽을 수 있게 그대로 둔다
		given(dart.fetchMajorAccounts("00126380", 2025, "11013")).willReturn(response(
			line("20250515000001", "영업수익", "CFS", "1,000", "1")));

		FinancialCollector.CollectResult result = collector.collect(companyId, 2025, 2025);

		assertThat(result).isEqualTo(new FinancialCollector.CollectResult(false, 1, 0, 1, 1));
		assertThat(jdbc.sql("SELECT payload -> 'list' -> 0 ->> 'account_nm' FROM financial").query(String.class).single())
			.isEqualTo("영업수익");
		mvc.perform(get("/api/companies/{id}/financials", companyId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void 계정_줄에_null이_섞여_있어도_수집이_멈추지_않는다() {
		given(dart.fetchMajorAccounts("00126380", 2025, "11013")).willReturn(Optional.of(
			"{\"status\":\"000\",\"message\":\"정상\",\"list\":[null,{\"rcept_no\":\"20250515000001\","
					+ "\"account_nm\":\"매출액\",\"fs_div\":\"CFS\",\"sj_div\":\"IS\",\"thstrm_amount\":\"1,000\","
					+ "\"ord\":\"1\",\"currency\":\"KRW\"}]}"));
		given(dart.fetchMajorAccounts("00126380", 2025, "11012")).willReturn(response(
			line("20250814000001", "매출액", "CFS", "2,000", "1")));

		FinancialCollector.CollectResult result = collector.collect(companyId, 2025, 2025);

		assertThat(result).isEqualTo(new FinancialCollector.CollectResult(false, 2, 0, 2, 2));
	}

	@Test
	void 응답_본문을_읽지_못하면_그_보고서만_건너뛴다() {
		given(dart.fetchMajorAccounts("00126380", 2025, "11013")).willReturn(Optional.of("<html>점검 중</html>"));
		given(dart.fetchMajorAccounts("00126380", 2025, "11012")).willReturn(response(
			line("20250814000001", "매출액", "CFS", "2,000", "1")));

		FinancialCollector.CollectResult result = collector.collect(companyId, 2025, 2025);

		assertThat(result).isEqualTo(new FinancialCollector.CollectResult(false, 1, 1, 2, 1));
		assertThat(jdbc.sql("SELECT report_type FROM financial").query(String.class).list()).containsExactly("HALF");
	}

	@Test
	void 수집_중에_회사가_없어진_것_같은_다른_저장_오류는_건너뛰지_않고_실패로_남긴다() {
		given(dart.fetchMajorAccounts("00126380", 2025, "11013")).willAnswer(invocation -> {
			// 응답을 받는 사이에 회사 행이 지워진 상황
			jdbc.sql("DELETE FROM company WHERE id = :id").param("id", companyId).update();
			return response(line("20250515000001", "매출액", "CFS", "1,000", "1"));
		});

		assertThatThrownBy(() -> collector.collect(companyId, 2025, 2025))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

		assertThat(jdbc.sql("SELECT status || ' ' || error_kind FROM collection_run").query(String.class).single())
			.isEqualTo("FAILED INTERNAL");
	}

	@Test
	void DB가_받지_못하는_응답_한_건은_건너뛰고_나머지_보고서를_계속_받는다() {
		// NUL 문자 이스케이프는 JSON으로는 유효하지만 PostgreSQL의 jsonb는 받지 않는다
		given(dart.fetchMajorAccounts("00126380", 2025, "11013")).willReturn(Optional.of(
			"{\"status\":\"000\",\"message\":\"정상\",\"list\":[{\"rcept_no\":\"20250515000001\","
					+ "\"account_nm\":\"매출액\",\"fs_div\":\"CFS\",\"sj_div\":\"IS\",\"thstrm_amount\":\"1,000\","
					+ "\"thstrm_nm\":\"제 1 기\\u0000\",\"ord\":\"1\",\"currency\":\"KRW\"}]}"));
		given(dart.fetchMajorAccounts("00126380", 2025, "11012")).willReturn(response(
			line("20250814000001", "매출액", "CFS", "2,000", "1")));

		FinancialCollector.CollectResult result = collector.collect(companyId, 2025, 2025);

		assertThat(result).isEqualTo(new FinancialCollector.CollectResult(false, 1, 1, 2, 1));
		assertThat(jdbc.sql("SELECT report_type FROM financial").query(String.class).list()).containsExactly("HALF");
		assertThat(jdbc.sql("SELECT status FROM collection_run").query(String.class).single()).isEqualTo("SUCCESS");
	}

	@Test
	void 사업연도_범위의_모든_보고서를_조회한다() {
		collector.collect(companyId, 2024, 2026);

		verify(dart, times(12)).fetchMajorAccounts(eq("00126380"), anyInt(), anyString());
		verify(dart).fetchMajorAccounts("00126380", 2024, "11011");
		verify(dart).fetchMajorAccounts("00126380", 2026, "11013");
	}

	@Test
	void 호출이_실패하면_그때까지_받은_보고서는_남고_실패가_기록된다() {
		given(dart.fetchMajorAccounts("00126380", 2025, "11013")).willReturn(response(
			line("20250515000001", "매출액", "CFS", "1,000", "1")));
		given(dart.fetchMajorAccounts("00126380", 2025, "11012"))
			.willThrow(ExternalApiException.of("DART", ErrorKind.QUOTA_EXCEEDED, "020", "요청 제한을 초과하였습니다."));

		assertThatThrownBy(() -> collector.collect(companyId, 2025, 2025)).isInstanceOf(ExternalApiException.class);

		assertThat(jdbc.sql("SELECT count(*) FROM financial").query(Long.class).single()).isEqualTo(1);
		Map<String, Object> run = jdbc.sql("SELECT status, error_kind, error_code, fetched, saved FROM collection_run")
			.query().singleRow();
		assertThat(run.get("status")).isEqualTo("FAILED");
		assertThat(run.get("error_kind")).isEqualTo("QUOTA_EXCEEDED");
		assertThat(run.get("error_code")).isEqualTo("020");
		assertThat(run.get("fetched")).isEqualTo(1);
		assertThat(run.get("saved")).isEqualTo(1);
		verify(dart, never()).fetchMajorAccounts("00126380", 2025, "11014");
	}

	@Test
	void 보고서_한_건의_응답만_잘못되면_그_보고서만_건너뛰고_계속_받는다() {
		given(dart.fetchMajorAccounts("00126380", 2025, "11013"))
			.willThrow(ExternalApiException.of("DART", ErrorKind.PARSE, "convert", "응답 처리 실패"));
		given(dart.fetchMajorAccounts("00126380", 2025, "11012")).willReturn(response(
			line("20250814000001", "매출액", "CFS", "1,000", "1")));

		FinancialCollector.CollectResult result = collector.collect(companyId, 2025, 2025);

		assertThat(result).isEqualTo(new FinancialCollector.CollectResult(false, 1, 1, 1, 1));
		verify(dart).fetchMajorAccounts("00126380", 2025, "11011");
		assertThat(jdbc.sql("SELECT status FROM collection_run").query(String.class).single()).isEqualTo("SUCCESS");
	}

	@Test
	void 종목코드로_지정한_회사를_올해부터_거슬러_받고_없는_종목코드는_건너뛴다() {
		int thisYear = LocalDate.now(clock).getYear();

		collector.collectByStockCodes(List.of("999999", "005930"), 2);

		verify(dart, times(8)).fetchMajorAccounts(eq("00126380"), anyInt(), anyString());
		verify(dart).fetchMajorAccounts("00126380", thisYear - 1, "11013");
		verify(dart).fetchMajorAccounts("00126380", thisYear, "11011");
	}

	@Test
	void 종목코드가_같은_회사가_여럿이면_받지_않고_한_회사가_실패해도_다음_회사는_받는다() {
		jdbc.sql("""
			INSERT INTO company (country, source, source_key, stock_code, name) VALUES
			('KR', 'DART', '00000001', '111111', '옛 회사'), ('KR', 'DART', '00000002', '111111', '새 회사'),
			('KR', 'DART', '00164779', '000660', 'SK하이닉스')
			""").update();
		given(dart.fetchMajorAccounts(eq("00126380"), anyInt(), anyString()))
			.willThrow(ExternalApiException.of("DART", ErrorKind.TRANSIENT, "IO", "연결 실패"));

		collector.collectByStockCodes(List.of("111111", "005930", "000660"), 1);

		verify(dart, never()).fetchMajorAccounts(eq("00000001"), anyInt(), anyString());
		verify(dart, never()).fetchMajorAccounts(eq("00000002"), anyInt(), anyString());
		verify(dart, times(4)).fetchMajorAccounts(eq("00164779"), anyInt(), anyString());
	}

	@Test
	void 키가_없거나_DART가_정지_중이면_호출하지_않는다() {
		given(dart.isConfigured()).willReturn(false);
		assertThat(collector.collect(companyId, 2025, 2025).skipped()).isTrue();

		given(dart.isConfigured()).willReturn(true);
		given(pauses.pausedNow(DartClient.SOURCE)).willReturn(Optional.of(org.mockito.Mockito.mock(SourcePauseRegistry.Pause.class)));
		assertThat(collector.collect(companyId, 2025, 2025).skipped()).isTrue();

		verify(dart, never()).fetchMajorAccounts(anyString(), anyInt(), anyString());
		assertThat(jdbc.sql("SELECT count(*) FROM collection_run").query(Long.class).single()).isZero();
	}

	@Test
	void 없는_회사나_틀린_연도_범위는_거절한다() {
		assertThatThrownBy(() -> collector.collect(companyId + 999, 2025, 2025)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> collector.collect(companyId, 2014, 2025)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> collector.collect(companyId, 2026, 2025)).isInstanceOf(IllegalArgumentException.class);
		verify(dart, never()).fetchMajorAccounts(anyString(), anyInt(), anyString());
	}

	@Test
	void 실적_API는_저장된_응답에서_금액을_읽어_증감을_계산해_준다() throws Exception {
		given(dart.fetchMajorAccounts("00126380", 2025, "11014")).willReturn(Optional.of(FinancialExtractorTest.realJson()));
		collector.collect(companyId, 2025, 2025);

		// 기대값은 삼성전자 2025년 3분기보고서의 실제 금액으로 손으로 계산한 값 (PerformanceCalculatorTest 참고)
		mvc.perform(get("/api/companies/{id}/financials", companyId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].fiscalYear").value(2025))
			.andExpect(jsonPath("$[0].period").value("Q3"))
			.andExpect(jsonPath("$[0].statementType").value("CONSOLIDATED"))
			.andExpect(jsonPath("$[0].currency").value("KRW"))
			.andExpect(jsonPath("$[0].revenue.amount").value(86061747000000L))
			.andExpect(jsonPath("$[0].revenue.changeRate").value(8.8))
			.andExpect(jsonPath("$[0].revenue.change").value("INCREASED"))
			.andExpect(jsonPath("$[0].operatingIncome.changeRate").value(32.5))
			.andExpect(jsonPath("$[0].netIncome.changeRate").value(21.0))
			.andExpect(jsonPath("$[0].operatingMargin").value(14.1))
			.andExpect(jsonPath("$[0].operatingMarginChange").value(2.5))
			.andExpect(jsonPath("$[0].documentUrl").value("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20251114002447"));
	}

	@Test
	void 여러_보고서가_저장되어_있으면_4분기를_연간에서_3분기_누적을_빼서_준다() throws Exception {
		// 삼성전자 2024년 연결 매출액(백만원): 3분기보고서 79,098,731 / 누적 225,082,634 / 전년 67,404,652 / 전년 누적 191,155,556
		// 사업보고서 300,870,903 / 전년 258,935,494
		given(dart.fetchMajorAccounts("00126380", 2024, "11014")).willReturn(response(new Item("20241114002642", "매출액",
			"CFS", "IS", "79,098,731", "225,082,634", "67,404,652", "191,155,556", "23", "KRW")));
		given(dart.fetchMajorAccounts("00126380", 2024, "11011")).willReturn(response(new Item("20250311001085", "매출액",
			"CFS", "IS", "300,870,903", null, "258,935,494", null, "23", "KRW")));
		collector.collect(companyId, 2024, 2024);

		// 4분기 = 300,870,903 − 225,082,634 = 75,788,269. 전년 4분기 = 258,935,494 − 191,155,556 = 67,779,938 → +11.8%
		mvc.perform(get("/api/companies/{id}/financials", companyId))
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].period").value("YEAR"))
			.andExpect(jsonPath("$[0].revenue.changeRate").value(16.2))
			.andExpect(jsonPath("$[1].period").value("Q4"))
			.andExpect(jsonPath("$[1].revenue.amount").value(75788269))
			.andExpect(jsonPath("$[1].revenue.priorAmount").value(67779938))
			.andExpect(jsonPath("$[1].revenue.changeRate").value(11.8))
			.andExpect(jsonPath("$[1].documentUrl").value("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20250311001085"))
			.andExpect(jsonPath("$[2].period").value("Q3"));
	}

	@Test
	void 저장된_응답_한_건을_읽지_못해도_나머지_보고서로_실적을_준다() throws Exception {
		given(dart.fetchMajorAccounts("00126380", 2025, "11014")).willReturn(Optional.of(FinancialExtractorTest.realJson()));
		collector.collect(companyId, 2025, 2025);
		// 주요계정 응답 형식이 아닌 내용, JSON null이 저장된 경우
		jdbc.sql("""
			INSERT INTO financial (company_id, source, fiscal_year, report_type, payload)
			VALUES (:id, 'DART', 2025, 'Q1', '[1, 2]'::jsonb), (:id, 'DART', 2025, 'HALF', 'null'::jsonb)
			""").param("id", companyId).update();

		mvc.perform(get("/api/companies/{id}/financials", companyId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].period").value("Q3"));
	}

	@Test
	void 재무가_없는_회사는_빈_배열이고_없는_회사는_404() throws Exception {
		mvc.perform(get("/api/companies/{id}/financials", companyId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
		mvc.perform(get("/api/companies/{id}/financials", companyId + 999)).andExpect(status().isNotFound());
	}

	/** 주요계정 응답 본문(JSON). */
	private static Optional<String> response(Item... items) {
		return Optional.of(JsonMapper.builder().build()
			.writeValueAsString(new MajorAccountResponse("000", "정상", List.of(items))));
	}

	private static Item line(String receiptNo, String accountName, String statementDiv, String amount, String order) {
		return new Item(receiptNo, accountName, statementDiv, "IS", amount, amount, null, null, order, "KRW");
	}
}
