package com.companyinsight.company;

/**
 * 검색 결과 한 건.
 *
 * @param corpCode  DART 고유번호
 * @param stockCode 종목코드
 * @param name      회사명
 * @param market    상장 시장. 최근 공시가 없어 아직 모르면 null
 */
public record CompanySummary(String corpCode, String stockCode, String name, Market market) {
}
