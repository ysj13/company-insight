package com.companyinsight.financial;

import java.math.BigDecimal;

/**
 * 금액 하나와 전년 같은 기간 대비 증감.
 *
 * @param amount      금액
 * @param priorAmount 전년 같은 기간의 금액. 없으면 null
 * @param changeRate  전년 같은 기간 대비 증감률(%). 소수 첫째 자리까지. 전년 금액이 없거나 0 이하이거나, 올해 금액이 음수면 null
 * @param change      전년 같은 기간 대비 변화. 전년 금액이 없으면 null
 */
public record Metric(BigDecimal amount, BigDecimal priorAmount, BigDecimal changeRate, Change change) {
}
