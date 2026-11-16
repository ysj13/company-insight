package com.companyinsight.financial;

/**
 * 실적을 보여주는 기간. 사업연도 기준이다.
 *
 * <p>나열 순서는 한 사업연도 안에서의 시간 순서이고, 연간이 맨 뒤다.
 */
public enum FiscalPeriod {

	/** 1분기 (3개월) */
	Q1(ReportType.Q1),

	/** 2분기 (3개월) */
	Q2(ReportType.HALF),

	/** 3분기 (3개월) */
	Q3(ReportType.Q3),

	/** 4분기 (3개월). 연간에서 3분기 누적을 빼서 구한다 */
	Q4(ReportType.ANNUAL),

	/** 연간 (사업연도 전체) */
	YEAR(ReportType.ANNUAL);

	private final ReportType reportType;

	FiscalPeriod(ReportType reportType) {
		this.reportType = reportType;
	}

	/** 이 기간의 금액이 실린 보고서 종류. */
	public ReportType reportType() {
		return reportType;
	}
}
