package com.companyinsight.disclosure;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DART 보고서명을 대응표와 맞춰 볼 수 있게 다듬는다.
 *
 * <p>글자를 다루는 절차라 표로 적기 어려워 코드에 둔다. 출처마다 제목의 모양이 달라 출처별로 따로 둔다(지금은 DART뿐).
 */
final class TitleNormalizer {

	/** 보고서명 앞의 머리말. "[기재정정]", "[첨부추가]" 등. 여러 개가 이어 붙을 수 있다. */
	private static final Pattern PREFIXES = Pattern.compile("^(\\[[^\\]]*\\])+");

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	/** 정기보고서 제목 끝의 기간 표기. "분기보고서 (2025.09)" */
	private static final Pattern PERIOD = Pattern.compile("\\(\\d{4}\\.\\d{2}\\)$");

	private TitleNormalizer() {
	}

	/**
	 * 공백을 모두 없애고 가운뎃점을 DART가 쓰는 글자(ㆍ)로 통일한다.
	 * 같은 공시도 "기타시장안내 (개선기간 부여)"처럼 공백이 제각각이다.
	 */
	static String squeeze(String text) {
		if (text == null) {
			return "";
		}
		return WHITESPACE.matcher(text).replaceAll("").replace('·', 'ㆍ').replace('・', 'ㆍ');
	}

	/**
	 * 보고서명에서 머리말과 기간 표기를 뗀다.
	 *
	 * @param reportName 보고서명. 머리말과 공백이 섞여 있어도 된다. null이면 빈 제목
	 */
	static Normalized normalize(String reportName) {
		String name = squeeze(reportName);
		Matcher prefix = PREFIXES.matcher(name);
		boolean hasPrefix = prefix.find();
		if (hasPrefix) {
			name = name.substring(prefix.end());
		}
		name = PERIOD.matcher(name).replaceFirst("");
		return new Normalized(name, hasPrefix);
	}

	/**
	 * 다듬은 제목.
	 *
	 * @param title     머리말, 공백, 기간 표기를 뗀 제목. 속성 꼬리는 아직 붙어 있다
	 * @param hasPrefix 머리말이 붙어 있었는지
	 */
	record Normalized(String title, boolean hasPrefix) {
	}
}
