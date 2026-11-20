package com.companyinsight.analysis.body;

/**
 * 본문 문서 파일 하나를 글자로 읽은 것.
 *
 * @param fileName 파일 이름
 * @param format   형식
 * @param markup   태그가 그대로 있는 원래 글. 표의 칸을 자리로 읽을 때 쓴다
 * @param text     태그를 걷어낸 글. 표의 한 줄이 글의 한 줄이고 칸은 " | "로 나뉜다
 */
public record BodyDocument(String fileName, BodyFormat format, String markup, String text) {
}
