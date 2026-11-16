package com.companyinsight.disclosure;

import java.util.regex.Pattern;

/**
 * 공시 목록을 거르는 조건. 비어 있는 항목은 거르지 않는다.
 *
 * <p>회사는 두 가지로 거른다. {@code corpCode}는 고른 회사 한 곳만 보여주고, {@code keyword}는 이름이나 종목코드가
 * 맞는 회사를 모두 보여준다. 이름이 비슷한 회사가 섞이지 않게 하려면 회사를 골라 {@code corpCode}를 쓴다.
 * 둘이 함께 오면 검색어가 우선하고 {@code corpCode}는 비운다.
 *
 * @param tone     판정. null이면 전체
 * @param kind     공시 종류. null이면 전체
 * @param keyword  회사명 일부 또는 종목코드 앞부분. null이면 전체
 * @param corpCode 고른 회사의 DART 고유번호. null이면 전체
 */
public record DisclosureSearchCondition(ToneFilter tone, DisclosureKind kind, String keyword, String corpCode) {

	private static final int KEYWORD_MAX = 50;

	/** DART 고유번호는 숫자 8자리다. */
	private static final Pattern CORP_CODE = Pattern.compile("\\d{8}");

	/**
	 * 검색어의 제어 문자와 앞뒤 공백을 없애고, 빈 값은 null로, 너무 긴 값은 잘라서 받는다.
	 * 주소로 들어온 NUL 문자가 DB까지 가면 쿼리가 실패하므로 여기서 걸러 낸다.
	 * 고유번호는 형식이 맞지 않으면 고르지 않은 것으로 본다.
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
		if (corpCode != null && !CORP_CODE.matcher(corpCode).matches()) {
			corpCode = null;
		}
		// 회사를 고른 화면에서 검색어를 넣어 찾으면 다른 회사를 찾으려는 것이므로 검색어가 우선하고 고른 회사는 푼다.
		// 다듬은 뒤의 검색어로 판단해야 공백이나 제어 문자뿐인 검색어가 고른 회사를 풀지 않는다
		if (keyword != null) {
			corpCode = null;
		}
	}

	/** 회사를 고르지 않은 조건. */
	public DisclosureSearchCondition(ToneFilter tone, DisclosureKind kind, String keyword) {
		this(tone, kind, keyword, null);
	}
}
