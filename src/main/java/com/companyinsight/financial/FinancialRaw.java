package com.companyinsight.financial;

import com.companyinsight.common.source.Source;

/**
 * 출처에서 받은 재무 응답의 원본. financial_raw 테이블의 한 행이다.
 *
 * @param companyId  회사 ID
 * @param source     응답을 받은 출처
 * @param fiscalYear 조회한 사업연도
 * @param reportType 조회한 보고서 종류
 * @param payload    출처가 준 응답 전체(JSON)
 */
public record FinancialRaw(long companyId, Source source, int fiscalYear, ReportType reportType, String payload) {
}
