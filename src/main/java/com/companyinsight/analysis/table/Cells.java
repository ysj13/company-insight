package com.companyinsight.analysis.table;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 표의 칸에 적힌 글을 다루는 도구. 항목 이름을 견주기 좋게 다듬고, 숫자를 읽는다.
 */
final class Cells {

	private static final Pattern SPACES = Pattern.compile("[\\s\\u00A0\\u3000]+");

	/** 항목 이름 앞의 차례 표시: "1.", "7-1.", "가.", "-", "ㆍ", "※" 등 */
	private static final Pattern LEADING_MARK = Pattern.compile("^(?:\\d+(?:-\\d+)?\\.|[가나다라마바사아자차카타파하]\\.|[-ㆍ·※○●▶*])+");

	/** 숫자 한 개. 천 단위 쉼표와 소수점을 받는다 */
	private static final Pattern NUMBER = Pattern.compile("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?");

	private Cells() {
	}

	/** 앞뒤 공백을 떼고 이어진 공백(줄바꿈, 전각 공백 포함)을 한 칸으로 줄인다. */
	static String squeeze(String text) {
		return text == null ? "" : SPACES.matcher(text).replaceAll(" ").strip();
	}

	/** 공백을 모두 없앤다. 서식마다 띄어쓰기가 다른 항목 이름을 견줄 때 쓴다. */
	static String compact(String text) {
		return text == null ? "" : SPACES.matcher(text).replaceAll("");
	}

	/** 항목 이름: 공백을 없애고 앞의 차례 표시를 뗀 것. "가. 감사의견" → "감사의견", "5. 증자방식" → "증자방식". */
	static String label(String text) {
		return LEADING_MARK.matcher(compact(text)).replaceFirst("");
	}

	/** 값이 비어 있는지. 빈 칸과 "-"만 있는 칸은 값이 없는 것이다. */
	static boolean isBlank(String text) {
		String compact = compact(text);
		return compact.isEmpty() || compact.chars().allMatch(c -> c == '-' || c == '－' || c == '\u2212' || c == '‐' || c == '–' || c == '—');
	}

	/**
	 * 칸의 숫자를 읽는다. 쉼표를 받고, 음수는 앞의 "-", "△", "▲"와 괄호로 적힌 것을 받는다. 뒤에 붙은 "%"는 뗀다.
	 *
	 * @return 값이 비었거나 숫자 하나로 읽히지 않으면 비어 있음
	 */
	static Optional<BigDecimal> number(String text) {
		// 부호·괄호와 숫자 사이의 공백만 받는다. 숫자 사이가 벌어진 글("12 34")은 숫자 하나가 아니다
		String value = squeeze(text);
		if (isBlank(value)) {
			return Optional.empty();
		}
		boolean negative = false;
		if (value.startsWith("(") && value.endsWith(")")) {
			negative = true;
			value = value.substring(1, value.length() - 1).strip();
		}
		if (value.endsWith("%")) {
			value = value.substring(0, value.length() - 1).strip();
		}
		if (!value.isEmpty() && "-－\u2212△▲+".indexOf(value.charAt(0)) >= 0) {
			negative = negative || value.charAt(0) != '+';
			value = value.substring(1).strip();
		}
		if (!NUMBER.matcher(value).matches()) {
			return Optional.empty();
		}
		BigDecimal number = new BigDecimal(value.replace(",", ""));
		return Optional.of(negative ? number.negate() : number);
	}
}
