package com.companyinsight.analysis.body;

/**
 * 본문 압축 파일 안의 문서 파일 하나.
 *
 * @param name    파일 이름 (예: 20261029000539.xml)
 * @param content 파일 내용
 */
public record BodyFile(String name, byte[] content) {
}
