package com.companyinsight.financial;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 저장된 재무 행으로 기간별 실적과 전년 같은 기간 대비 증감을 계산한다.
 *
 * <ul>
 * <li>1~3분기: 분기·반기보고서의 당기 금액이 그 분기 3개월 금액이라 그대로 쓴다.</li>
 * <li>4분기: 보고서가 따로 없다. 사업보고서의 연간 금액에서 3분기보고서의 누적 금액을 뺀다.</li>
 * <li>연간: 사업보고서의 당기 금액.</li>
 * <li>전년 금액: 같은 보고서에 실린 전기 금액을 쓴다. 회사가 그 보고서에서 비교한 값과 같다.</li>
 * <li>연결재무제표가 있는 사업연도는 연결을, 없으면 별도를 쓴다.</li>
 * </ul>
 */
public final class PerformanceCalculator {

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	/** 증감률과 영업이익률의 소수 자릿수. */
	private static final int RATE_SCALE = 1;

	private static final Comparator<PeriodPerformance> LATEST_FIRST = Comparator
		.comparingInt(PeriodPerformance::fiscalYear)
		.thenComparing(PeriodPerformance::period)
		.reversed();

	private PerformanceCalculator() {
	}

	/**
	 * 회사 한 곳의 재무 행으로 기간별 실적을 만든다.
	 *
	 * @param records 회사 한 곳의 재무 행. 연결과 별도, 여러 사업연도가 섞여 있어도 된다
	 * @return 최근 기간이 먼저. 같은 사업연도에서는 연간, 4분기, 3분기, 2분기, 1분기 순. 금액이 하나도 없는 기간은 빠진다
	 */
	public static List<PeriodPerformance> calculate(List<FinancialRecord> records) {
		Map<Integer, List<FinancialRecord>> byYear = new TreeMap<>();
		for (FinancialRecord record : records) {
			byYear.computeIfAbsent(record.fiscalYear(), year -> new ArrayList<>()).add(record);
		}
		List<PeriodPerformance> result = new ArrayList<>();
		byYear.forEach((year, yearRecords) -> result.addAll(calculateYear(year, yearRecords)));
		result.sort(LATEST_FIRST);
		return result;
	}

	private static List<PeriodPerformance> calculateYear(int fiscalYear, List<FinancialRecord> records) {
		boolean hasConsolidated = records.stream().anyMatch(r -> r.statementType() == StatementType.CONSOLIDATED);
		StatementType statementType = hasConsolidated ? StatementType.CONSOLIDATED : StatementType.SEPARATE;

		Map<ReportType, Map<AccountType, FinancialRecord>> reports = new EnumMap<>(ReportType.class);
		for (FinancialRecord record : records) {
			if (record.statementType() == statementType) {
				reports.computeIfAbsent(record.reportType(), type -> new EnumMap<>(AccountType.class))
					.put(record.account(), record);
			}
		}

		List<PeriodPerformance> periods = new ArrayList<>();
		for (FiscalPeriod period : FiscalPeriod.values()) {
			Map<AccountType, FinancialRecord> main = reports.getOrDefault(period.reportType(), Map.of());
			Map<AccountType, FinancialRecord> thirdQuarter = reports.getOrDefault(ReportType.Q3, Map.of());
			Map<AccountType, Amounts> amounts = new EnumMap<>(AccountType.class);
			for (AccountType account : AccountType.values()) {
				FinancialRecord record = main.get(account);
				if (record == null) {
					continue;
				}
				Amounts value = period == FiscalPeriod.Q4 ? fourthQuarter(record, thirdQuarter.get(account))
						: new Amounts(record.amount(), record.priorAmount());
				if (value != null) {
					amounts.put(account, value);
				}
			}
			if (amounts.isEmpty()) {
				continue;
			}
			FinancialRecord any = main.values().iterator().next();
			periods.add(toPerformance(fiscalYear, period, statementType, any, amounts));
		}
		return periods;
	}

	/**
	 * 4분기 = 연간 − 3분기 누적. 3분기보고서가 없거나 누적 금액이 없거나 통화가 다르면 계산하지 않는다.
	 * 전년 4분기도 같은 방식으로 두 보고서의 전기 금액에서 구한다.
	 */
	private static Amounts fourthQuarter(FinancialRecord annual, FinancialRecord thirdQuarter) {
		if (thirdQuarter == null || thirdQuarter.cumulativeAmount() == null
				|| !annual.currency().equals(thirdQuarter.currency())) {
			return null;
		}
		BigDecimal current = annual.amount().subtract(thirdQuarter.cumulativeAmount());
		BigDecimal prior = annual.priorAmount() == null || thirdQuarter.priorCumulativeAmount() == null ? null
				: annual.priorAmount().subtract(thirdQuarter.priorCumulativeAmount());
		return new Amounts(current, prior);
	}

	private static PeriodPerformance toPerformance(int fiscalYear, FiscalPeriod period, StatementType statementType,
			FinancialRecord report, Map<AccountType, Amounts> amounts) {
		Amounts revenue = amounts.get(AccountType.REVENUE);
		Amounts operatingIncome = amounts.get(AccountType.OPERATING_INCOME);
		BigDecimal margin = revenue == null || operatingIncome == null ? null
				: margin(operatingIncome.current(), revenue.current());
		BigDecimal priorMargin = revenue == null || operatingIncome == null ? null
				: margin(operatingIncome.prior(), revenue.prior());
		BigDecimal marginChange = margin == null || priorMargin == null ? null : margin.subtract(priorMargin);
		return new PeriodPerformance(fiscalYear, period, statementType, report.currency(), metric(revenue),
			metric(operatingIncome), metric(amounts.get(AccountType.NET_INCOME)), margin, priorMargin, marginChange,
			report.sourceKey(), report.source().documentUrl(report.sourceKey()));
	}

	private static Metric metric(Amounts amounts) {
		return amounts == null ? null : metric(amounts.current(), amounts.prior());
	}

	/**
	 * 금액 하나의 전년 대비 증감.
	 *
	 * <p>증감률은 전년 금액이 양수이고 올해 금액이 0 이상일 때만 계산한다. 전년이 적자이거나 0이면 비율이 뜻을 잃고,
	 * 흑자에서 적자가 된 경우도 비율 대신 "적자 전환"으로 나타낸다.
	 *
	 * @param amount 올해 금액
	 * @param prior  전년 같은 기간의 금액. 없으면 null
	 */
	static Metric metric(BigDecimal amount, BigDecimal prior) {
		if (prior == null) {
			return new Metric(amount, null, null, null);
		}
		int priorSign = prior.signum();
		int sign = amount.signum();
		Change change;
		if (priorSign < 0 && sign > 0) {
			change = Change.TURNED_TO_PROFIT;
		}
		else if (priorSign > 0 && sign < 0) {
			change = Change.TURNED_TO_LOSS;
		}
		else if (priorSign < 0 && sign < 0) {
			change = Change.LOSS_CONTINUED;
		}
		else {
			int compared = amount.compareTo(prior);
			change = compared > 0 ? Change.INCREASED : compared < 0 ? Change.DECREASED : Change.UNCHANGED;
		}
		BigDecimal rate = priorSign > 0 && sign >= 0
				? amount.subtract(prior).multiply(HUNDRED).divide(prior, RATE_SCALE, RoundingMode.HALF_UP)
				: null;
		return new Metric(amount, prior, rate, change);
	}

	/** 영업이익률(%) = 영업이익 ÷ 매출액 × 100. 매출액이 없거나 0 이하면 null. */
	private static BigDecimal margin(BigDecimal operatingIncome, BigDecimal revenue) {
		if (operatingIncome == null || revenue == null || revenue.signum() <= 0) {
			return null;
		}
		return operatingIncome.multiply(HUNDRED).divide(revenue, RATE_SCALE, RoundingMode.HALF_UP);
	}

	/** 한 계정의 올해 금액과 전년 같은 기간 금액. */
	private record Amounts(BigDecimal current, BigDecimal prior) {
	}
}
