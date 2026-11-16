package com.companyinsight.company;

/**
 * 검색 결과 한 건.
 *
 * @param id        회사 ID (내부 번호). 회사 한 곳을 고를 때 쓴다
 * @param stockCode 종목코드
 * @param name      회사명
 * @param market    상장 시장. 공시에서 시장 구분을 아직 받지 못했거나 상장 시장이 아닌 법인이면 null
 */
public record CompanySummary(long id, String stockCode, String name, Market market) {
}
