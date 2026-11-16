package com.companyinsight.financial;

import static org.assertj.core.api.Assertions.assertThat;

import com.companyinsight.common.dart.MajorAccountResponse;
import com.companyinsight.common.dart.MajorAccountResponse.Item;
import com.companyinsight.common.source.Source;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 주요계정 응답 → 저장할 행. 응답은 삼성전자 2025년 3분기보고서의 실제 값이다. */
class FinancialCollectorTest {

	@Test
	void 손익계산서의_세_계정만_연결과_별도로_남긴다() throws IOException {
		List<FinancialRecord> records = FinancialCollector.toRecords(2025, ReportType.Q3, realResponse().items());

		assertThat(records).hasSize(6);
		assertThat(records).extracting(r -> r.statementType() + " " + r.account())
			.containsExactlyInAnyOrder("CONSOLIDATED REVENUE", "CONSOLIDATED OPERATING_INCOME", "CONSOLIDATED NET_INCOME",
				"SEPARATE REVENUE", "SEPARATE OPERATING_INCOME", "SEPARATE NET_INCOME");
		assertThat(records).filteredOn(r -> r.statementType() == StatementType.CONSOLIDATED
				&& r.account() == AccountType.REVENUE)
			.singleElement()
			.satisfies(revenue -> {
				assertThat(revenue.fiscalYear()).isEqualTo(2025);
				assertThat(revenue.reportType()).isEqualTo(ReportType.Q3);
				assertThat(revenue.amount()).isEqualByComparingTo("86061747000000");
				assertThat(revenue.cumulativeAmount()).isEqualByComparingTo("239768567000000");
				assertThat(revenue.priorAmount()).isEqualByComparingTo("79098731000000");
				assertThat(revenue.priorCumulativeAmount()).isEqualByComparingTo("225082634000000");
				assertThat(revenue.currency()).isEqualTo("KRW");
				assertThat(revenue.source()).isEqualTo(Source.DART);
				assertThat(revenue.sourceKey()).isEqualTo("20251114002447");
			});
		// 당기순이익(손실)은 응답에 두 줄(ord 29, 61) 오지만 한 행만 남는다
		assertThat(records).filteredOn(r -> r.statementType() == StatementType.CONSOLIDATED
				&& r.account() == AccountType.NET_INCOME)
			.singleElement()
			.satisfies(net -> assertThat(net.amount()).isEqualByComparingTo("12225747000000"));
	}

	@Test
	void 사업보고서처럼_누적_금액이_없으면_null로_둔다() {
		// 삼성전자 2025년 사업보고서의 연결 매출액 줄. 누적 필드가 응답에 없다
		Item annual = new Item("20260310002820", "매출액", "CFS", "IS", "333,605,938,000,000", null,
			"300,870,903,000,000", null, "23", "KRW");

		List<FinancialRecord> records = FinancialCollector.toRecords(2025, ReportType.ANNUAL, List.of(annual));

		assertThat(records).singleElement().satisfies(r -> {
			assertThat(r.amount()).isEqualByComparingTo("333605938000000");
			assertThat(r.cumulativeAmount()).isNull();
			assertThat(r.priorAmount()).isEqualByComparingTo("300870903000000");
			assertThat(r.priorCumulativeAmount()).isNull();
		});
	}

	@Test
	void 같은_계정이_두_줄이면_표시_순서가_앞선_줄을_쓴다() {
		Item later = item("당기순이익(손실)", "CFS", "IS", "999", "61", "KRW");
		Item earlier = item("당기순이익(손실)", "CFS", "IS", "100", "29", "KRW");

		List<FinancialRecord> records = FinancialCollector.toRecords(2025, ReportType.Q1, List.of(later, earlier));

		assertThat(records).singleElement().satisfies(r -> assertThat(r.amount()).isEqualByComparingTo("100"));
	}

	@Test
	void 읽을_수_없는_줄은_건너뛴다() {
		List<Item> items = List.of(
			item("매출액", "CFS", "IS", "-", "1", "KRW"),           // 금액이 숫자가 아님
			item("매출액", "OFS", "IS", "", "2", "KRW"),            // 금액이 비어 있음
			item("영업이익", "CFS", "IS", "100", "3", " "),          // 통화가 없음
			item("영업이익", "XXX", "IS", "100", "4", "KRW"),        // 모르는 재무제표 구분
			item("자산총계", "CFS", "BS", "100", "5", "KRW"),        // 재무상태표
			item("총포괄손익", "CFS", "IS", "100", "6", "KRW"),      // 수집하지 않는 계정
			item(null, "CFS", "IS", "100", "7", "KRW"),
			new Item(null, "영업이익", "OFS", "IS", "100", null, null, null, "8", "KRW"), // 접수번호가 없음
			item("당기순이익", "OFS", "IS", "1,000", null, "KRW")); // 표시 순서가 없어도 저장한다

		List<FinancialRecord> records = FinancialCollector.toRecords(2025, ReportType.Q1, items);

		assertThat(records).singleElement().satisfies(r -> {
			assertThat(r.account()).isEqualTo(AccountType.NET_INCOME);
			assertThat(r.statementType()).isEqualTo(StatementType.SEPARATE);
			assertThat(r.amount()).isEqualByComparingTo("1000");
		});
	}

	@Test
	void 계정명은_공백을_없애고_비교한다() {
		assertThat(AccountType.fromDartName("당기순이익(손실)")).contains(AccountType.NET_INCOME);
		assertThat(AccountType.fromDartName(" 당기순이익 (손실) ")).contains(AccountType.NET_INCOME);
		assertThat(AccountType.fromDartName("매출액")).contains(AccountType.REVENUE);
		assertThat(AccountType.fromDartName("영업이익")).contains(AccountType.OPERATING_INCOME);
		// 실제 응답에 함께 오는 다른 계정
		assertThat(AccountType.fromDartName("법인세차감전 순이익")).isEmpty();
		assertThat(AccountType.fromDartName("총포괄손익")).isEmpty();
		assertThat(AccountType.fromDartName(null)).isEmpty();
	}

	@Test
	void 응답이_비어_있으면_저장할_행이_없다() {
		assertThat(FinancialCollector.toRecords(2026, ReportType.ANNUAL, List.of())).isEmpty();
	}

	private static Item item(String accountName, String statementDiv, String sheetDiv, String amount, String order,
			String currency) {
		return new Item("20250101000001", accountName, statementDiv, sheetDiv, amount, null, null, null, order, currency);
	}

	static MajorAccountResponse realResponse() throws IOException {
		return MajorAccountResponse.fromJson(realJson());
	}

	/** 삼성전자 2025년 3분기보고서의 실제 응답 본문(손익계산서 계정과 자산총계만 남긴 것). */
	static String realJson() throws IOException {
		try (InputStream in = FinancialCollectorTest.class
			.getResourceAsStream("/dart/major-accounts-00126380-2025-q3.json")) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
