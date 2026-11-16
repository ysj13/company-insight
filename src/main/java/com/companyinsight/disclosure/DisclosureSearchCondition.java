package com.companyinsight.disclosure;

/**
 * 공시 목록을 거르는 조건. 비어 있는 항목은 거르지 않는다.
 *
 * @param tone    판정. null이면 전체
 * @param kind    공시 종류. null이면 전체
 * @param keyword 회사명 일부 또는 종목코드 앞부분. null이면 전체
 */
public record DisclosureSearchCondition(ToneFilter tone, DisclosureKind kind, String keyword) {

	private static final int KEYWORD_MAX = 50;

	/** 검색어의 앞뒤 공백을 없애고, 빈 값은 null로, 너무 긴 값은 잘라서 받는다. */
	public DisclosureSearchCondition {
		if (keyword != null) {
			keyword = keyword.strip();
			if (keyword.isEmpty()) {
				keyword = null;
			}
			else if (keyword.length() > KEYWORD_MAX) {
				keyword = keyword.substring(0, KEYWORD_MAX);
			}
		}
	}
}
