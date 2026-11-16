package com.companyinsight.financial;

import com.companyinsight.common.source.Source;
import java.math.BigDecimal;

/**
 * 보고서 한 건에 실린 계정 하나의 금액. financial 테이블의 한 행이다.
 *
 * @param fiscalYear            사업연도
 * @param reportType            보고서 종류
 * @param statementType         연결 또는 별도
 * @param account               계정
 * @param amount                당기 금액. 분기·반기보고서는 그 분기 3개월, 사업보고서는 1년 전체
 * @param cumulativeAmount      당기 누적 금액. 사업보고서에는 없어 null
 * @param priorAmount           같은 보고서에 실린 전년 같은 기간의 금액. 없으면 null
 * @param priorCumulativeAmount 같은 보고서에 실린 전년 같은 기간의 누적 금액. 없으면 null
 * @param currency              통화 (ISO 4217)
 * @param source                금액을 받은 출처
 * @param sourceKey             근거가 된 보고서를 출처에서 가리키는 번호. DART는 접수번호
 */
public record FinancialRecord(int fiscalYear, ReportType reportType, StatementType statementType, AccountType account,
		BigDecimal amount, BigDecimal cumulativeAmount, BigDecimal priorAmount, BigDecimal priorCumulativeAmount,
		String currency, Source source, String sourceKey) {
}
