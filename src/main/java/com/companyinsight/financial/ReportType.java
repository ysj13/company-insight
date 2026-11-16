package com.companyinsight.financial;

/**
 * 정기보고서 종류. DB의 {@code financial.report_type}에 상수 이름을 저장한다.
 *
 * <p>나열 순서는 한 사업연도 안에서 보고서가 나오는 순서다.
 */
public enum ReportType {

	/** 1분기보고서 */
	Q1("11013"),

	/** 반기보고서 */
	HALF("11012"),

	/** 3분기보고서 */
	Q3("11014"),

	/** 사업보고서 */
	ANNUAL("11011");

	private final String dartCode;

	ReportType(String dartCode) {
		this.dartCode = dartCode;
	}

	/** DART 보고서 코드 (reprt_code). */
	public String dartCode() {
		return dartCode;
	}
}
