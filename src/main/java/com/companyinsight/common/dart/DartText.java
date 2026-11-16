package com.companyinsight.common.dart;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * DART가 주는 문자열과 날짜를 저장하기 전에 다듬는 공통 처리.
 *
 * <p>기업 목록 동기화와 공시 수집이 같은 규칙을 써야 해서 한곳에 둔다. 규칙이 경로마다 다르면
 * 한쪽에서만 저장이 실패하는 일이 생긴다.
 */
public final class DartText {

	/** DART 날짜 형식 (예: 20261113). */
	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

	/** 부호와 숫자, 소수점만 있는 금액. 지수 표기 같은 다른 형식은 받지 않는다. */
	private static final Pattern AMOUNT = Pattern.compile("-?\\d+(\\.\\d+)?");

	private DartText() {
	}

	/**
	 * 컬럼에 저장할 수 있게 다듬는다. NUL 문자를 지우고 최대 길이로 자른다.
	 *
	 * <p>PostgreSQL 문자열은 NUL 문자를 저장하지 못하고, 길이를 넘으면 INSERT가 실패한다.
	 * 외부 값 한 건 때문에 배치 전체가 실패하지 않도록 저장 전에 항상 거친다.
	 *
	 * @param value     원본 값. null이면 null을 돌려준다.
	 * @param maxLength 컬럼 길이
	 */
	public static String clean(String value, int maxLength) {
		if (value == null) {
			return null;
		}
		String clean = value.indexOf(0) < 0 ? value : value.replace("\u0000", "");
		return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
	}

	/**
	 * 앞뒤 공백을 없애고, 연속된 공백 문자(탭·줄바꿈 포함)를 공백 하나로 줄인다. DART 공시 제목은 뒤에 공백이 길게 붙어 온다.
	 *
	 * @return 다듬은 문자열. null이면 빈 문자열.
	 */
	public static String collapseSpaces(String value) {
		return value == null ? "" : value.strip().replaceAll("\\s+", " ");
	}

	/** 비어 있거나 공백뿐이면 null, 아니면 앞뒤 공백을 없앤 값. */
	public static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	/**
	 * yyyyMMdd 문자열을 날짜로 바꾼다.
	 *
	 * <p>앞뒤 공백이 있는 값도 형식 오류로 본다. 날짜 문자열을 그대로 비교하는 코드가 있어 엄격하게 받는다.
	 *
	 * @return 형식이 맞으면 날짜, null·빈 값·형식 오류면 비어 있음
	 */
	public static Optional<LocalDate> parseDate(String yyyymmdd) {
		if (yyyymmdd == null || yyyymmdd.isBlank()) {
			return Optional.empty();
		}
		try {
			return Optional.of(LocalDate.parse(yyyymmdd, YYYYMMDD));
		}
		catch (DateTimeParseException e) {
			return Optional.empty();
		}
	}

	/**
	 * 쉼표가 들어간 금액 문자열을 숫자로 바꾼다. 예: "79,098,731,000,000", "-7,730,313,000,000".
	 *
	 * @return 숫자면 그 값. null·빈 값·"-"·숫자가 아닌 값이면 비어 있음
	 */
	public static Optional<BigDecimal> parseAmount(String amount) {
		if (amount == null) {
			return Optional.empty();
		}
		String digits = amount.strip().replace(",", "");
		if (!AMOUNT.matcher(digits).matches()) {
			return Optional.empty();
		}
		return Optional.of(new BigDecimal(digits));
	}

	/** 날짜를 DART 요청 형식(yyyyMMdd)으로 바꾼다. */
	public static String formatDate(LocalDate date) {
		return date.format(YYYYMMDD);
	}
}
