package com.companyinsight.financial;

import java.math.BigDecimal;

/**
 * 한 기간의 실적.
 *
 * @param fiscalYear                 사업연도
 * @param period                     기간. Q1~Q4는 그 분기 3개월, YEAR는 사업연도 전체
 * @param statementType              금액의 기준. 연결재무제표가 있으면 CONSOLIDATED, 없으면 SEPARATE
 * @param currency                   통화 (ISO 4217)
 * @param revenue                    매출액. 없으면 null
 * @param operatingIncome            영업이익. 없으면 null
 * @param netIncome                  당기순이익. 없으면 null
 * @param operatingMargin            영업이익률(%). 영업이익 ÷ 매출액. 소수 첫째 자리까지. 계산할 수 없으면 null
 * @param priorOperatingMargin       전년 같은 기간의 영업이익률(%). 계산할 수 없으면 null
 * @param operatingMarginChange      영업이익률의 전년 같은 기간 대비 변화(%p). 계산할 수 없으면 null
 * @param sourceKey                  근거 보고서의 번호. DART는 접수번호. 4분기와 연간은 사업보고서
 * @param documentUrl                근거 보고서 원문 주소
 */
public record PeriodPerformance(int fiscalYear, FiscalPeriod period, StatementType statementType, String currency,
		Metric revenue, Metric operatingIncome, Metric netIncome, BigDecimal operatingMargin,
		BigDecimal priorOperatingMargin, BigDecimal operatingMarginChange, String sourceKey, String documentUrl) {
}
