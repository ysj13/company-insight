package com.companyinsight.company;

import java.time.LocalDate;

/** DART 고유번호 파일의 한 행 중 상장사만. */
public record CorpCode(String corpCode, String stockCode, String name, LocalDate modifyDate) {
}
