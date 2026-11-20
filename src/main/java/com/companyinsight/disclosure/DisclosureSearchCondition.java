package com.companyinsight.disclosure;

import java.util.regex.Pattern;

/**
 * 공시 목록을 거르는 조건. 비어 있는 항목은 거르지 않는다.
 *
 * <p>회사는 두 가지로 거른다. {@code companyId}는 고른 회사 한 곳만 보여주고, {@code keyword}는 이름이나 종목코드가
 * 맞는 회사를 모두 보여준다. 이름이 비슷한 회사가 섞이지 않게 하려면 회사를 골라 {@code companyId}를 쓴다.
 * 둘이 함께 오면 검색어가 우선하고 {@code companyId}는 비운다.
 *
 * @param tone      판정. null이면 전체
 * @param bundle    공시 유형의 묶음. null이면 전체
 * @param keyword   회사명 일부 또는 종목코드 앞부분. null이면 전체
 * @param companyId 고른 회사의 ID. null이면 전체
 */
public record DisclosureSearchCondition(ToneFilter tone, DisclosureBundle bundle, String keyword, Long companyId) {

	private static final int KEYWORD_MAX = 50;

	/** 회사 ID는 1 이상의 정수다. bigint 범위를 넘지 않게 18자리까지만 받는다. */
	private static final Pattern COMPANY_ID = Pattern.compile("[1-9][0-9]{0,17}");

	/**
	 * 검색어의 제어 문자와 앞뒤 공백을 없애고, 빈 값은 null로, 너무 긴 값은 잘라서 받는다.
	 * 주소로 들어온 NUL 문자가 DB까지 가면 쿼리가 실패하므로 여기서 걸러 낸다.
	 * 회사 ID는 1보다 작으면 고르지 않은 것으로 본다.
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
		if (companyId != null && companyId < 1) {
			companyId = null;
		}
		// 회사를 고른 화면에서 검색어를 넣어 찾으면 다른 회사를 찾으려는 것이므로 검색어가 우선하고 고른 회사는 푼다.
		// 다듬은 뒤의 검색어로 판단해야 공백이나 제어 문자뿐인 검색어가 고른 회사를 풀지 않는다
		if (keyword != null) {
			companyId = null;
		}
	}

	/**
	 * 주소로 들어온 회사 ID를 읽는다. 숫자가 아니거나 형식이 틀리면 오류로 보지 않고 고르지 않은 것으로 본다.
	 *
	 * @param raw 주소의 값. 없으면 null
	 * @return 회사 ID. 읽을 수 없으면 null
	 */
	public static Long companyIdOf(String raw) {
		return raw != null && COMPANY_ID.matcher(raw).matches() ? Long.valueOf(raw) : null;
	}

	/** 회사를 고르지 않은 조건. */
	public DisclosureSearchCondition(ToneFilter tone, DisclosureBundle bundle, String keyword) {
		this(tone, bundle, keyword, null);
	}
}
