package com.companyinsight.financial;

import static org.assertj.core.api.Assertions.assertThat;

import com.companyinsight.common.source.Source;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 입력 금액은 DART 주요계정의 실제 응답 값이다(백만원 단위로 줄여 적었다).
 * 기대값은 그 금액으로 손으로 계산한 값이고, 코드를 돌려 얻은 값이 아니다.
 */
class PerformanceCalculatorTest {

	private static final StatementType CFS = StatementType.CONSOLIDATED;
	private static final StatementType OFS = StatementType.SEPARATE;

	@Test
	void 분기보고서의_당기_금액과_전기_금액으로_증감을_계산한다() {
		// 삼성전자 2025년 3분기보고서 (접수번호 20251114002447), 연결
		List<FinancialRecord> records = List.of(
			row(2025, ReportType.Q3, CFS, AccountType.REVENUE, "86061747", "239768567", "79098731", "225082634", "20251114002447"),
			row(2025, ReportType.Q3, CFS, AccountType.OPERATING_INCOME, "12166062", "23527391", "9183371", "26233258", "20251114002447"),
			row(2025, ReportType.Q3, CFS, AccountType.NET_INCOME, "12225747", "25565060", "10100904", "26696957", "20251114002447"));

		List<PeriodPerformance> result = PerformanceCalculator.calculate(records);

		assertThat(result).singleElement().satisfies(q3 -> {
			assertThat(q3.fiscalYear()).isEqualTo(2025);
			assertThat(q3.period()).isEqualTo(FiscalPeriod.Q3);
			assertThat(q3.statementType()).isEqualTo(CFS);
			assertThat(q3.currency()).isEqualTo("KRW");
			// 누적(239,768,567)이 아니라 3개월 금액
			assertThat(q3.revenue().amount()).isEqualByComparingTo("86061747");
			assertThat(q3.revenue().priorAmount()).isEqualByComparingTo("79098731");
			// (86,061,747 − 79,098,731) ÷ 79,098,731 = 8.80%
			assertThat(q3.revenue().changeRate()).isEqualByComparingTo("8.8");
			assertThat(q3.revenue().change()).isEqualTo(Change.INCREASED);
			// (12,166,062 − 9,183,371) ÷ 9,183,371 = 32.48%
			assertThat(q3.operatingIncome().changeRate()).isEqualByComparingTo("32.5");
			// (12,225,747 − 10,100,904) ÷ 10,100,904 = 21.04%
			assertThat(q3.netIncome().changeRate()).isEqualByComparingTo("21.0");
			// 12,166,062 ÷ 86,061,747 = 14.14%, 전년 9,183,371 ÷ 79,098,731 = 11.61%
			assertThat(q3.operatingMargin()).isEqualByComparingTo("14.1");
			assertThat(q3.priorOperatingMargin()).isEqualByComparingTo("11.6");
			assertThat(q3.operatingMarginChange()).isEqualByComparingTo("2.5");
			assertThat(q3.sourceKey()).isEqualTo("20251114002447");
			assertThat(q3.documentUrl()).isEqualTo("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20251114002447");
		});
	}

	@Test
	void 사분기는_연간에서_3분기_누적을_뺀다() {
		// 삼성전자 2024년: 3분기보고서(20241114002642)와 사업보고서(20250311001085), 연결
		List<FinancialRecord> records = List.of(
			row(2024, ReportType.Q3, CFS, AccountType.REVENUE, "79098731", "225082634", "67404652", "191155556", "20241114002642"),
			row(2024, ReportType.Q3, CFS, AccountType.OPERATING_INCOME, "9183371", "26233258", "2433534", "3742259", "20241114002642"),
			row(2024, ReportType.ANNUAL, CFS, AccountType.REVENUE, "300870903", null, "258935494", null, "20250311001085"),
			row(2024, ReportType.ANNUAL, CFS, AccountType.OPERATING_INCOME, "32725961", null, "6566976", null, "20250311001085"));

		List<PeriodPerformance> result = PerformanceCalculator.calculate(records);

		assertThat(result).extracting(PeriodPerformance::period)
			.containsExactly(FiscalPeriod.YEAR, FiscalPeriod.Q4, FiscalPeriod.Q3);
		PeriodPerformance year = result.get(0);
		assertThat(year.revenue().amount()).isEqualByComparingTo("300870903");
		// (300,870,903 − 258,935,494) ÷ 258,935,494 = 16.20%
		assertThat(year.revenue().changeRate()).isEqualByComparingTo("16.2");
		// (32,725,961 − 6,566,976) ÷ 6,566,976 = 398.34%
		assertThat(year.operatingIncome().changeRate()).isEqualByComparingTo("398.3");

		PeriodPerformance q4 = result.get(1);
		// 300,870,903 − 225,082,634 = 75,788,269. 전년 258,935,494 − 191,155,556 = 67,779,938
		assertThat(q4.revenue().amount()).isEqualByComparingTo("75788269");
		assertThat(q4.revenue().priorAmount()).isEqualByComparingTo("67779938");
		// 8,008,331 ÷ 67,779,938 = 11.82%
		assertThat(q4.revenue().changeRate()).isEqualByComparingTo("11.8");
		// 32,725,961 − 26,233,258 = 6,492,703. 전년 6,566,976 − 3,742,259 = 2,824,717
		assertThat(q4.operatingIncome().amount()).isEqualByComparingTo("6492703");
		assertThat(q4.operatingIncome().priorAmount()).isEqualByComparingTo("2824717");
		// 3,667,986 ÷ 2,824,717 = 129.85%
		assertThat(q4.operatingIncome().changeRate()).isEqualByComparingTo("129.9");
		// 6,492,703 ÷ 75,788,269 = 8.57%
		assertThat(q4.operatingMargin()).isEqualByComparingTo("8.6");
		assertThat(q4.sourceKey()).isEqualTo("20250311001085");
	}

	@Test
	void 전년이_적자였다가_흑자가_되면_증감률_없이_흑자_전환() {
		// SK하이닉스 2024년 1분기보고서 (20240516001638), 연결. 2023년 1분기 영업손실 3조 4,023억원
		List<FinancialRecord> records = List.of(
			row(2024, ReportType.Q1, CFS, AccountType.REVENUE, "12429598", "12429598", "5088111", "5088111", "20240516001638"),
			row(2024, ReportType.Q1, CFS, AccountType.OPERATING_INCOME, "2886029", "2886029", "-3402302", "-3402302", "20240516001638"));

		PeriodPerformance q1 = PerformanceCalculator.calculate(records).getFirst();

		assertThat(q1.operatingIncome().change()).isEqualTo(Change.TURNED_TO_PROFIT);
		assertThat(q1.operatingIncome().changeRate()).isNull();
		assertThat(q1.operatingIncome().priorAmount()).isEqualByComparingTo("-3402302");
		// (12,429,598 − 5,088,111) ÷ 5,088,111 = 144.29%
		assertThat(q1.revenue().changeRate()).isEqualByComparingTo("144.3");
		// 2,886,029 ÷ 12,429,598 = 23.22%. 전년 −3,402,302 ÷ 5,088,111 = −66.87%
		assertThat(q1.operatingMargin()).isEqualByComparingTo("23.2");
		assertThat(q1.priorOperatingMargin()).isEqualByComparingTo("-66.9");
		assertThat(q1.operatingMarginChange()).isEqualByComparingTo("90.1");
	}

	@Test
	void 적자_전환과_적자_지속과_전년_0은_증감률을_계산하지_않는다() {
		assertThat(PerformanceCalculator.metric(amount("-100"), amount("50")))
			.isEqualTo(new Metric(amount("-100"), amount("50"), null, Change.TURNED_TO_LOSS));
		assertThat(PerformanceCalculator.metric(amount("-100"), amount("-300")))
			.isEqualTo(new Metric(amount("-100"), amount("-300"), null, Change.LOSS_CONTINUED));
		assertThat(PerformanceCalculator.metric(amount("100"), amount("0")))
			.isEqualTo(new Metric(amount("100"), amount("0"), null, Change.INCREASED));
		assertThat(PerformanceCalculator.metric(amount("0"), amount("-300")).change()).isEqualTo(Change.INCREASED);
		assertThat(PerformanceCalculator.metric(amount("0"), amount("-300")).changeRate()).isNull();
	}

	@Test
	void 전년과_같거나_줄어든_금액() {
		assertThat(PerformanceCalculator.metric(amount("100"), amount("100")).change()).isEqualTo(Change.UNCHANGED);
		assertThat(PerformanceCalculator.metric(amount("100"), amount("100")).changeRate()).isEqualByComparingTo("0");
		assertThat(PerformanceCalculator.metric(amount("0"), amount("200")).changeRate()).isEqualByComparingTo("-100");
		// 현대자동차 2025년 연결 영업이익 11,467,851 / 전년 14,239,592 → −19.46%
		Metric decreased = PerformanceCalculator.metric(amount("11467851"), amount("14239592"));
		assertThat(decreased.change()).isEqualTo(Change.DECREASED);
		assertThat(decreased.changeRate()).isEqualByComparingTo("-19.5");
	}

	@Test
	void 전년_금액이_없으면_증감_없이_금액만() {
		List<FinancialRecord> records = List.of(
			row(2025, ReportType.Q1, CFS, AccountType.REVENUE, "1000", "1000", null, null, "R1"),
			row(2025, ReportType.Q1, CFS, AccountType.OPERATING_INCOME, "100", "100", null, null, "R1"));

		PeriodPerformance q1 = PerformanceCalculator.calculate(records).getFirst();

		assertThat(q1.revenue()).isEqualTo(new Metric(amount("1000"), null, null, null));
		assertThat(q1.operatingMargin()).isEqualByComparingTo("10.0");
		assertThat(q1.priorOperatingMargin()).isNull();
		assertThat(q1.operatingMarginChange()).isNull();
		assertThat(q1.netIncome()).isNull();
	}

	@Test
	void 삼분기보고서가_없으면_사분기는_만들지_않는다() {
		List<FinancialRecord> records = List.of(
			row(2024, ReportType.ANNUAL, CFS, AccountType.REVENUE, "300870903", null, "258935494", null, "A"));

		assertThat(PerformanceCalculator.calculate(records)).extracting(PeriodPerformance::period)
			.containsExactly(FiscalPeriod.YEAR);
	}

	@Test
	void 사분기의_전년_금액을_구할_수_없으면_금액만_준다() {
		List<FinancialRecord> records = List.of(
			row(2024, ReportType.Q3, CFS, AccountType.REVENUE, "300", "700", null, null, "Q"),
			row(2024, ReportType.ANNUAL, CFS, AccountType.REVENUE, "1000", null, "900", null, "A"));

		PeriodPerformance q4 = PerformanceCalculator.calculate(records).get(1);

		assertThat(q4.period()).isEqualTo(FiscalPeriod.Q4);
		assertThat(q4.revenue()).isEqualTo(new Metric(amount("300"), null, null, null));
	}

	@Test
	void 연결이_있으면_연결을_쓰고_없는_사업연도는_별도를_쓴다() {
		// 삼성전자 2025년 3분기: 연결 매출 86,061,747 / 별도 매출 62,799,068
		List<FinancialRecord> records = new ArrayList<>(List.of(
			row(2025, ReportType.Q3, OFS, AccountType.REVENUE, "62799068", "173099333", "55828922", "160802657", "20251114002447"),
			row(2025, ReportType.Q3, CFS, AccountType.REVENUE, "86061747", "239768567", "79098731", "225082634", "20251114002447"),
			row(2023, ReportType.Q1, OFS, AccountType.REVENUE, "500", "500", "400", "400", "S")));

		List<PeriodPerformance> result = PerformanceCalculator.calculate(records);

		assertThat(result).hasSize(2);
		assertThat(result.get(0).statementType()).isEqualTo(CFS);
		assertThat(result.get(0).revenue().amount()).isEqualByComparingTo("86061747");
		assertThat(result.get(1).fiscalYear()).isEqualTo(2023);
		assertThat(result.get(1).statementType()).isEqualTo(OFS);
		assertThat(result.get(1).revenue().changeRate()).isEqualByComparingTo("25.0");
	}

	@Test
	void 최근_기간이_먼저_나온다() {
		List<FinancialRecord> records = List.of(
			row(2024, ReportType.Q1, CFS, AccountType.REVENUE, "1", "1", null, null, "a"),
			row(2025, ReportType.HALF, CFS, AccountType.REVENUE, "2", "3", null, null, "b"),
			row(2025, ReportType.Q1, CFS, AccountType.REVENUE, "1", "1", null, null, "c"),
			row(2024, ReportType.ANNUAL, CFS, AccountType.REVENUE, "9", null, null, null, "d"));

		assertThat(PerformanceCalculator.calculate(records))
			.extracting(p -> p.fiscalYear() + " " + p.period())
			.containsExactly("2025 Q2", "2025 Q1", "2024 YEAR", "2024 Q1");
	}

	@Test
	void 재무가_없으면_빈_목록() {
		assertThat(PerformanceCalculator.calculate(List.of())).isEmpty();
	}

	private static FinancialRecord row(int year, ReportType reportType, StatementType statementType, AccountType account,
			String amount, String cumulative, String prior, String priorCumulative, String sourceKey) {
		return new FinancialRecord(year, reportType, statementType, account, amount(amount), amount(cumulative),
			amount(prior), amount(priorCumulative), "KRW", Source.DART, sourceKey);
	}

	private static BigDecimal amount(String value) {
		return value == null ? null : new BigDecimal(value);
	}
}
