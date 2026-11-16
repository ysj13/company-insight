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

	/**
	 * 검색어의 제어 문자와 앞뒤 공백을 없애고, 빈 값은 null로, 너무 긴 값은 잘라서 받는다.
	 * 주소로 들어온 NUL 문자가 DB까지 가면 쿼리가 실패하므로 여기서 걸러 낸다.
	 */
	public DisclosureSearchCondition {
		if (keyword != null) {
			int[] kept = keyword.codePoints().filter(cp -> !Character.isISOControl(cp)).toArray();
			keyword = new String(kept, 0, kept.length).strip();
			if (keyword.isEmpty()) {
				keyword = null;
			}
			else if (keyword.codePointCount(0, keyword.length()) > KEYWORD_MAX) {
				// 글자 단위로 자른다. char 단위로 자르면 이모지 같은 글자가 반으로 잘려 깨진 문자열이 된다
				keyword = keyword.substring(0, keyword.offsetByCodePoints(0, KEYWORD_MAX));
			}
		}
	}
}
