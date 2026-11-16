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

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class FinancialIntegrationTest {

	private static final MajorAccountResponse EMPTY = new MajorAccountResponse("013", "조회된 데이타가 없습니다.", List.of());

	@Autowired
	FinancialCollector collector;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MockMvc mvc;

	@Autowired
	java.time.Clock clock;

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
		given(dart.listMajorAccounts(anyString(), anyInt(), anyString())).willReturn(EMPTY);
	}

	/** 다른 통합 테스트가 company를 비울 때 참조 제약에 걸리지 않게 치운다. */
	@AfterEach
	void tearDown() {
		jdbc.sql("DELETE FROM financial").update();
	}

	@Test
	void 회사_한_곳의_재무를_받아_저장하고_다시_받아도_변경이_없다() throws IOException {
		given(dart.listMajorAccounts("00126380", 2025, "11014")).willReturn(FinancialCollectorTest.realResponse());

		FinancialCollector.CollectResult first = collector.collect(companyId, 2025, 2025);
		FinancialCollector.CollectResult second = collector.collect(companyId, 2025, 2025);

		// 보고서 네 종류를 한 번씩 조회한다
		verify(dart, times(2)).listMajorAccounts("00126380", 2025, "11013");
		verify(dart, times(2)).listMajorAccounts("00126380", 2025, "11012");
		verify(dart, times(2)).listMajorAccounts("00126380", 2025, "11014");
		verify(dart, times(2)).listMajorAccounts("00126380", 2025, "11011");
		assertThat(first).isEqualTo(new FinancialCollector.CollectResult(false, 1, 0, 14, 6));
		assertThat(second.saved()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM financial").query(Long.class).single()).isEqualTo(6);

		Map<String, Object> revenue = jdbc.sql("""
			SELECT * FROM financial
			 WHERE company_id = :id AND fiscal_year = 2025 AND report_type = 'Q3'
			   AND statement_type = 'CONSOLIDATED' AND account = 'REVENUE'
			""").param("id", companyId).query().singleRow();
		assertThat(revenue.get("amount").toString()).isEqualTo("86061747000000.00");
		assertThat(revenue.get("cumulative_amount").toString()).isEqualTo("239768567000000.00");
		assertThat(revenue.get("prior_amount").toString()).isEqualTo("79098731000000.00");
		assertThat(revenue.get("prior_cumulative_amount").toString()).isEqualTo("225082634000000.00");
		assertThat(revenue.get("currency")).isEqualTo("KRW");
		assertThat(revenue.get("source")).isEqualTo("DART");
		assertThat(revenue.get("source_key")).isEqualTo("20251114002447");

		assertThat(jdbc.sql("SELECT status, fetched, saved FROM collection_run WHERE source = :s ORDER BY id")
			.param("s", FinancialCollector.JOB).query().listOfRows())
			.extracting(r -> r.get("status") + ":" + r.get("fetched") + ":" + r.get("saved"))
			.containsExactly("SUCCESS:14:6", "SUCCESS:14:0");
	}

	@Test
	void 출처의_값이_바뀌면_다시_받을_때_그_행만_갱신한다() {
		given(dart.listMajorAccounts("00126380", 2025, "11013")).willReturn(response(
			line("20250515000001", "매출액", "CFS", "1,000", "1"),
			line("20250515000001", "영업이익", "CFS", "100", "2")));
		collector.collect(companyId, 2025, 2025);

		// 정정 보고서가 나와 매출액과 근거 접수번호가 바뀐 경우
		given(dart.listMajorAccounts("00126380", 2025, "11013")).willReturn(response(
			line("20250601000009", "매출액", "CFS", "1,200", "1"),
			line("20250515000001", "영업이익", "CFS", "100", "2")));
		FinancialCollector.CollectResult second = collector.collect(companyId, 2025, 2025);

		assertThat(second.saved()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT amount || ' ' || source_key FROM financial WHERE account = 'REVENUE'")
			.query(String.class).single()).isEqualTo("1200.00 20250601000009");
		assertThat(jdbc.sql("SELECT count(*) FROM financial").query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void 사업연도_범위의_모든_보고서를_조회한다() {
		collector.collect(companyId, 2024, 2026);

		verify(dart, times(12)).listMajorAccounts(eq("00126380"), anyInt(), anyString());
		verify(dart).listMajorAccounts("00126380", 2024, "11011");
		verify(dart).listMajorAccounts("00126380", 2026, "11013");
	}

	@Test
	void 호출이_실패하면_그때까지_받은_보고서는_남고_실패가_기록된다() {
		given(dart.listMajorAccounts("00126380", 2025, "11013")).willReturn(response(
			line("20250515000001", "매출액", "CFS", "1,000", "1")));
		given(dart.listMajorAccounts("00126380", 2025, "11012"))
			.willThrow(ExternalApiException.of("DART", ErrorKind.QUOTA_EXCEEDED, "020", "요청 제한을 초과하였습니다."));

		assertThatThrownBy(() -> collector.collect(companyId, 2025, 2025)).isInstanceOf(ExternalApiException.class);

		assertThat(jdbc.sql("SELECT count(*) FROM financial").query(Long.class).single()).isEqualTo(1);
		Map<String, Object> run = jdbc.sql("SELECT status, error_kind, error_code, saved FROM collection_run").query()
			.singleRow();
		assertThat(run.get("status")).isEqualTo("FAILED");
		assertThat(run.get("error_kind")).isEqualTo("QUOTA_EXCEEDED");
		assertThat(run.get("error_code")).isEqualTo("020");
		assertThat(run.get("saved")).isEqualTo(1);
		verify(dart, never()).listMajorAccounts("00126380", 2025, "11014");
	}

	@Test
	void 보고서_한_건의_응답만_잘못되면_그_보고서만_건너뛰고_계속_받는다() {
		given(dart.listMajorAccounts("00126380", 2025, "11013"))
			.willThrow(ExternalApiException.of("DART", ErrorKind.PARSE, "convert", "응답 처리 실패"));
		given(dart.listMajorAccounts("00126380", 2025, "11012")).willReturn(response(
			line("20250814000001", "매출액", "CFS", "1,000", "1")));

		FinancialCollector.CollectResult result = collector.collect(companyId, 2025, 2025);

		assertThat(result).isEqualTo(new FinancialCollector.CollectResult(false, 1, 1, 1, 1));
		verify(dart).listMajorAccounts("00126380", 2025, "11011");
		assertThat(jdbc.sql("SELECT status FROM collection_run").query(String.class).single()).isEqualTo("SUCCESS");
	}

	@Test
	void 종목코드로_지정한_회사를_올해부터_거슬러_받고_없는_종목코드는_건너뛴다() {
		int thisYear = java.time.LocalDate.now(clock).getYear();

		collector.collectByStockCodes(List.of("999999", "005930"), 2);

		verify(dart, times(8)).listMajorAccounts(eq("00126380"), anyInt(), anyString());
		verify(dart).listMajorAccounts("00126380", thisYear - 1, "11013");
		verify(dart).listMajorAccounts("00126380", thisYear, "11011");
	}

	@Test
	void 종목코드가_같은_회사가_여럿이면_받지_않고_한_회사가_실패해도_다음_회사는_받는다() {
		jdbc.sql("""
			INSERT INTO company (country, source, source_key, stock_code, name) VALUES
			('KR', 'DART', '00000001', '111111', '옛 회사'), ('KR', 'DART', '00000002', '111111', '새 회사'),
			('KR', 'DART', '00164779', '000660', 'SK하이닉스')
			""").update();
		given(dart.listMajorAccounts(eq("00126380"), anyInt(), anyString()))
			.willThrow(ExternalApiException.of("DART", ErrorKind.TRANSIENT, "IO", "연결 실패"));

		collector.collectByStockCodes(List.of("111111", "005930", "000660"), 1);

		verify(dart, never()).listMajorAccounts(eq("00000001"), anyInt(), anyString());
		verify(dart, never()).listMajorAccounts(eq("00000002"), anyInt(), anyString());
		verify(dart, times(4)).listMajorAccounts(eq("00164779"), anyInt(), anyString());
	}

	@Test
	void 키가_없거나_DART가_정지_중이면_호출하지_않는다() {
		given(dart.isConfigured()).willReturn(false);
		assertThat(collector.collect(companyId, 2025, 2025).skipped()).isTrue();

		given(dart.isConfigured()).willReturn(true);
		given(pauses.pausedNow(DartClient.SOURCE)).willReturn(Optional.of(org.mockito.Mockito.mock(SourcePauseRegistry.Pause.class)));
		assertThat(collector.collect(companyId, 2025, 2025).skipped()).isTrue();

		verify(dart, never()).listMajorAccounts(anyString(), anyInt(), anyString());
		assertThat(jdbc.sql("SELECT count(*) FROM collection_run").query(Long.class).single()).isZero();
	}

	@Test
	void 없는_회사나_틀린_연도_범위는_거절한다() {
		assertThatThrownBy(() -> collector.collect(companyId + 999, 2025, 2025)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> collector.collect(companyId, 2014, 2025)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> collector.collect(companyId, 2026, 2025)).isInstanceOf(IllegalArgumentException.class);
		verify(dart, never()).listMajorAccounts(anyString(), anyInt(), anyString());
	}

	@Test
	void 실적_API는_저장된_재무로_증감을_계산해_준다() throws Exception {
		given(dart.listMajorAccounts("00126380", 2025, "11014")).willReturn(FinancialCollectorTest.realResponse());
		collector.collect(companyId, 2025, 2025);

		// 기대값은 삼성전자 2025년 3분기보고서의 실제 금액으로 손으로 계산한 값 (PerformanceCalculatorTest 참고)
		mvc.perform(get("/api/companies/{id}/financials", companyId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].fiscalYear").value(2025))
			.andExpect(jsonPath("$[0].period").value("Q3"))
			.andExpect(jsonPath("$[0].statementType").value("CONSOLIDATED"))
			.andExpect(jsonPath("$[0].currency").value("KRW"))
			.andExpect(jsonPath("$[0].revenue.amount").value(86061747000000.00))
			.andExpect(jsonPath("$[0].revenue.changeRate").value(8.8))
			.andExpect(jsonPath("$[0].revenue.change").value("INCREASED"))
			.andExpect(jsonPath("$[0].operatingIncome.changeRate").value(32.5))
			.andExpect(jsonPath("$[0].netIncome.changeRate").value(21.0))
			.andExpect(jsonPath("$[0].operatingMargin").value(14.1))
			.andExpect(jsonPath("$[0].operatingMarginChange").value(2.5))
			.andExpect(jsonPath("$[0].documentUrl").value("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20251114002447"));
	}

	@Test
	void 재무가_없는_회사는_빈_배열이고_없는_회사는_404() throws Exception {
		mvc.perform(get("/api/companies/{id}/financials", companyId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
		mvc.perform(get("/api/companies/{id}/financials", companyId + 999)).andExpect(status().isNotFound());
	}

	private static MajorAccountResponse response(Item... items) {
		return new MajorAccountResponse("000", "정상", List.of(items));
	}

	private static Item line(String receiptNo, String accountName, String statementDiv, String amount, String order) {
		return new Item(receiptNo, accountName, statementDiv, "IS", amount, amount, null, null, order, "KRW");
	}
}
