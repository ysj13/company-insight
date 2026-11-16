package com.companyinsight.company;

import java.time.LocalDate;

/**
 * DART 고유번호 파일의 한 행. 종목코드가 있는 회사만 만든다.
 *
 * @param corpCode   DART 고유번호 (8자리)
 * @param stockCode  종목코드 (6자리)
 * @param name       회사명
 * @param modifyDate DART 기업 정보 최종 변경일. 없으면 null.
 */
public record CorpCode(String corpCode, String stockCode, String name, LocalDate modifyDate) {
}
