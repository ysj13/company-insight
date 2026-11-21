package com.companyinsight.analysis.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI의 답을 원문과 대조한다. AI가 판정의 근거로 옮긴 본문 구절이 본문에 실제로 있는지, 요약에 쓴 숫자와 날짜가 본문에 있는지를 코드가 본다.
 *
 * <p>숫자 대조의 규칙:
 * <ul>
 * <li>금액은 조·억·만(천억, 천만 포함) 단위로 바꿔 써도 된다. 바꾼 값이 본문 숫자를 그 자릿수로 반올림한 값과 같아야 한다.
 *   버림은 유효숫자가 두 자리 이상일 때만 받는다 (한 자리면 허용 폭이 두 배에 가깝다)
 *   (예: 본문 359,812,345,678 → "3,598억"은 맞고 "3,600억"은 틀리다). 본문에 천원·백만원·억원·조원이 적혀 있으면 본문 숫자(날짜와 연도처럼 보이는 정수는 빼고)에 그 배수를 곱해서도 본다.
 * <li>비율(%)은 본문 숫자(날짜의 연·월·일은 뺀다)를 그 자릿수로 반올림한 값과 같아야 한다. AI가 계산한 비율은 본문에 없으므로 틀린다.
 * <li>날짜는 연·월·일이 본문의 날짜와 같아야 한다. 연도 없이 월·일만 쓰면 월·일만 견준다.
 * <li>그 밖의 숫자는 본문에 같은 값이 있어야 한다. 10 이하의 정수(순서, 개수)는 보지 않는다.
 * <li>부호(△, -)는 보지 않는다.
 * </ul>
 * 문장의 뜻(목적, 상대방이 맞는지)은 대조하지 못한다.
 */
final class AiVerifier {

	/** 요약 문장 수의 상한. 목표가 아니라 원문 복제를 막는 장치다 */
	static final int MAX_LINES = 5;

	/** 요약 한 문장의 글자 수 상한 */
	static final int MAX_LINE_LENGTH = 200;

	/** 제목의 글자 수 상한 (지시문은 40자 이내라고 한다) */
	static final int MAX_HEADLINE_LENGTH = 60;

	/** 근거 구절의 가장 짧은 길이 (공백을 뺀 글자 수). 너무 짧으면 어디에나 있다 */
	static final int MIN_EVIDENCE_LENGTH = 6;

	private static final String NUM = "\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?";

	private static final Pattern FULL_DATE_KO = Pattern.compile("(\\d{4})\\s*년\\s*(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일");
	private static final Pattern FULL_DATE_NUM = Pattern.compile("(?<![\\d.])(\\d{4})\\s*[.\\-/]\\s*(\\d{1,2})\\s*[.\\-/]\\s*(\\d{1,2})(?![\\d])\\.?");
	private static final Pattern YEAR_MONTH = Pattern.compile("(\\d{4})\\s*년\\s*(\\d{1,2})\\s*월(?!\\s*\\d)");
	private static final Pattern MONTH_DAY = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일");

	/** 본문이 연도를 두 자리로 줄여 쓴 연·월 ("'17년 12월"). 50 이하는 20xx년, 그 위는 19xx년으로 읽는다 */
	private static final Pattern SHORT_YEAR_MONTH = Pattern.compile("['’‘](\\d{2})\\s*년\\s*(\\d{1,2})\\s*월");

	/** 금액·수량의 단위. 두 글자 단위(천억, 천만)를 먼저 본다 */
	private static final String UNIT = "(?:천억|천만|조|억|만|천)";

	/** 단위가 붙은 금액·수량. "1조 2,000억", "120만", "3,598억에", "9천억", "1억 2천만". 뒤에 붙은 조사는 보지 않는다 */
	private static final Pattern COMPOUND = Pattern.compile("(?:" + NUM + ")\\s*" + UNIT + "(?:\\s*(?:" + NUM + ")\\s*(?:천억|천만|억|만|천))*"
			+ "(?:\\s*(?:" + NUM + ")(?=\\s*[원주]))?");

	/** 단위가 붙은 금액 끝에 단위 없이 이어 쓴 나머지 ("8억 7,592만 1,100원"의 1,100) */
	private static final Pattern TRAILING = Pattern.compile("\\s*(" + NUM + ")\\s*");
	private static final Pattern UNIT_PART = Pattern.compile("(" + NUM + ")\\s*(" + UNIT + ")");

	/** 연도로 보는 정수의 범위. 표의 금액 단위 배수를 곱하지 않는다 ("2026년"이 2,026억이 되지 않게) */
	private static final BigDecimal YEAR_FROM = new BigDecimal("1990");
	private static final BigDecimal YEAR_TO = new BigDecimal("2100");

	/** 버림도 맞는 것으로 볼 가장 적은 유효숫자 수. 유효숫자가 적으면 버림을 받을 때 허용 폭이 너무 넓다 */
	private static final int TRUNCATION_MIN_DIGITS = 2;
	private static final Pattern PERCENT = Pattern.compile("(" + NUM + ")\\s*(?:%p|%|퍼센트|％)");
	private static final Pattern PLAIN = Pattern.compile("(?<!\\d)(?<!\\d[.,])(" + NUM + ")(?!\\d|[.,]\\d)");

	private static final BigDecimal TEN = BigDecimal.TEN;

	/** 본문 표의 금액 단위와 그 배수. 본문에 그 단위가 적혀 있을 때만 쓴다 */
	private static final List<TableScale> TABLE_SCALES = List.of(new TableScale("천원", new BigDecimal("1000")),
			new TableScale("백만원", new BigDecimal("1000000")), new TableScale("억원", new BigDecimal("100000000")),
			new TableScale("조원", new BigDecimal("1000000000000")));

	private AiVerifier() {
	}

	/**
	 * 근거 구절이 본문에 그대로 있는지. 공백, 표의 칸 구분, 콜론, 전각·반각 차이는 보지 않는다.
	 * 여러 곳을 이어 붙였거나 "..."로 건너뛴 구절은 없는 것으로 본다.
	 */
	static boolean evidenceFound(String body, String evidence) {
		if (evidence == null || evidence.contains("...") || evidence.contains("…")) {
			return false;
		}
		String needle = squeeze(evidence);
		return needle.length() >= MIN_EVIDENCE_LENGTH && squeeze(body).contains(needle);
	}

	/**
	 * 제목과 요약의 문제. 비어 있으면 통과다.
	 *
	 * @return 문제마다 한 줄 (예: "숫자 3,600억이 본문에 없음")
	 */
	static List<String> summaryProblems(String body, String headline, List<String> lines) {
		List<String> problems = new ArrayList<>();
		if (lines == null || lines.isEmpty() || lines.stream().allMatch(l -> l == null || l.isBlank())) {
			problems.add("요약이 비어 있음");
			return problems;
		}
		if (lines.size() > MAX_LINES) {
			problems.add("요약이 " + lines.size() + "문장 (상한 " + MAX_LINES + ")");
		}
		String headlineKey = headline == null ? "" : squeeze(headline);
		if (headline == null || headline.isBlank()) {
			problems.add("제목이 비어 있음");
		}
		else if (headline.strip().length() > MAX_HEADLINE_LENGTH) {
			problems.add("제목이 " + headline.strip().length() + "자 (상한 " + MAX_HEADLINE_LENGTH + ")");
		}
		List<String> texts = new ArrayList<>();
		texts.add(headline == null ? "" : headline);
		for (String line : lines) {
			if (line == null || line.isBlank()) {
				problems.add("빈 문장이 있음");
				continue;
			}
			if (line.strip().length() > MAX_LINE_LENGTH) {
				problems.add("문장이 " + line.strip().length() + "자 (상한 " + MAX_LINE_LENGTH + ")");
			}
			if (!headlineKey.isEmpty() && squeeze(line).equals(headlineKey)) {
				problems.add("요약 문장이 제목과 같음");
			}
			texts.add(line);
		}
		for (String text : texts) {
			if (text.contains("호재") || text.contains("악재")) {
				problems.add("판단을 적은 말(호재·악재)이 있음");
				break;
			}
		}
		for (String figure : unmatchedFigures(body, texts)) {
			problems.add("본문에 없는 숫자·날짜: " + figure);
		}
		return problems;
	}

	/** 글에 쓴 숫자와 날짜 가운데 본문과 맞지 않는 것. */
	static List<String> unmatchedFigures(String body, List<String> texts) {
		BodyFigures figures = BodyFigures.of(body);
		List<String> unmatched = new ArrayList<>();
		for (String raw : texts) {
			String text = normalize(raw);
			StringBuilder rest = new StringBuilder(text);
			for (Pattern pattern : List.of(FULL_DATE_KO, FULL_DATE_NUM)) {
				Matcher m = pattern.matcher(rest);
				while (m.find()) {
					String key = date(m.group(1), m.group(2), m.group(3));
					if (!figures.dates.contains(key)) {
						unmatched.add(m.group().strip());
					}
					blank(rest, m.start(), m.end());
				}
			}
			Matcher ym = YEAR_MONTH.matcher(rest);
			while (ym.find()) {
				if (!figures.yearMonths.contains(ym.group(1) + "-" + Integer.parseInt(ym.group(2)))) {
					unmatched.add(ym.group().strip());
				}
				blank(rest, ym.start(), ym.end());
			}
			Matcher md = MONTH_DAY.matcher(rest);
			while (md.find()) {
				if (!figures.monthDays.contains(Integer.parseInt(md.group(1)) + "-" + Integer.parseInt(md.group(2)))) {
					unmatched.add(md.group().strip());
				}
				blank(rest, md.start(), md.end());
			}
			Matcher compound = COMPOUND.matcher(rest);
			while (compound.find()) {
				Amount amount = Amount.parse(compound.group());
				if (amount != null && !figures.matchesAmount(amount)) {
					unmatched.add(compound.group().strip());
				}
				blank(rest, compound.start(), compound.end());
			}
			Matcher percent = PERCENT.matcher(rest);
			while (percent.find()) {
				BigDecimal value = number(percent.group(1));
				if (!figures.matchesRounded(value)) {
					unmatched.add(percent.group().strip());
				}
				blank(rest, percent.start(), percent.end());
			}
			Matcher plain = PLAIN.matcher(rest);
			while (plain.find()) {
				BigDecimal value = number(plain.group(1));
				boolean small = value.stripTrailingZeros().scale() <= 0 && value.compareTo(TEN) <= 0;
				if (!small && !figures.numbers.contains(key(value))) {
					unmatched.add(plain.group().strip());
				}
			}
		}
		return unmatched;
	}

	/** 공백·칸 구분을 없애고 전각을 반각으로, 가운뎃점과 따옴표를 한 모양으로 맞춘다. */
	static String squeeze(String text) {
		String normalized = normalize(text);
		StringBuilder out = new StringBuilder(normalized.length());
		for (int i = 0; i < normalized.length(); i++) {
			char c = normalized.charAt(i);
			// 표의 칸 구분(|)과, "항목: 값"으로 옮긴 콜론은 보지 않는다 (입력에 표 항목을 "항목: 값"으로 주므로 AI가 그 모양으로 옮긴다)
			if (Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '|' || c == ':') {
				continue;
			}
			out.append(switch (c) {
				case '·', 'ㆍ', '∙', '•', '‧' -> '·';
				case '“', '”', '„' -> '"';
				case '‘', '’', '‚' -> '\'';
				default -> c;
			});
		}
		return out.toString();
	}

	private static String normalize(String text) {
		return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFKC);
	}

	private static void blank(StringBuilder text, int start, int end) {
		for (int i = start; i < end; i++) {
			text.setCharAt(i, ' ');
		}
	}

	private static BigDecimal number(String text) {
		return new BigDecimal(text.replace(",", ""));
	}

	/** 유효숫자 수 (앞의 0은 세지 않는다. 3,598 → 4, 1 → 1, 0.5 → 1). */
	private static int significantDigits(BigDecimal value) {
		BigDecimal stripped = value.abs().stripTrailingZeros();
		return stripped.signum() == 0 ? 1 : stripped.precision();
	}

	private static String date(String year, String month, String day) {
		return year + "-" + Integer.parseInt(month) + "-" + Integer.parseInt(day);
	}

	/** 값이 같은 숫자를 같은 키로 (1.50과 1.5). */
	private static String key(BigDecimal value) {
		BigDecimal stripped = value.abs().stripTrailingZeros();
		return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
	}

	/** 본문 표의 금액 단위. */
	private record TableScale(String word, BigDecimal factor) {
	}

	/** 단위를 붙여 쓴 금액·수량. 값과, 쓴 자릿수(가장 작은 단위와 소수 자리). */
	private record Amount(BigDecimal value, BigDecimal unit, int decimals) {

		static Amount parse(String text) {
			Matcher part = UNIT_PART.matcher(text);
			BigDecimal total = BigDecimal.ZERO;
			BigDecimal smallest = null;
			int decimals = 0;
			int end = 0;
			while (part.find()) {
				BigDecimal mantissa = number(part.group(1));
				BigDecimal unit = switch (part.group(2)) {
					case "조" -> new BigDecimal("1000000000000");
					case "천억" -> new BigDecimal("100000000000");
					case "억" -> new BigDecimal("100000000");
					case "천만" -> new BigDecimal("10000000");
					case "만" -> new BigDecimal("10000");
					default -> new BigDecimal("1000");
				};
				total = total.add(mantissa.multiply(unit));
				smallest = unit;
				decimals = Math.max(0, mantissa.scale());
				end = part.end();
			}
			if (smallest == null) {
				return null;
			}
			Matcher trailing = TRAILING.matcher(text.substring(end));
			if (trailing.matches()) {
				total = total.add(number(trailing.group(1)));
				smallest = BigDecimal.ONE;
				decimals = 0;
			}
			return new Amount(total, smallest, decimals);
		}
	}

	/** 본문에 있는 숫자와 날짜. */
	private static final class BodyFigures {

		private final Set<String> numbers = new HashSet<>();
		private final List<BigDecimal> values = new ArrayList<>();
		private final Set<String> dates = new HashSet<>();
		private final Set<String> yearMonths = new HashSet<>();
		private final Set<String> monthDays = new HashSet<>();
		private final List<BigDecimal> scales = new ArrayList<>(List.of(BigDecimal.ONE));
		private final List<BigDecimal> nonDateValues = new ArrayList<>();

		static BodyFigures of(String body) {
			BodyFigures figures = new BodyFigures();
			String text = normalize(body);
			String compact = squeeze(text);
			for (TableScale scale : TABLE_SCALES) {
				if (compact.contains(scale.word())) {
					figures.scales.add(scale.factor());
				}
			}
			StringBuilder withoutDates = new StringBuilder(text);
			for (Pattern pattern : List.of(FULL_DATE_KO, FULL_DATE_NUM)) {
				Matcher m = pattern.matcher(text);
				while (m.find()) {
					figures.addDate(m.group(1), m.group(2), m.group(3));
					blank(withoutDates, m.start(), m.end());
				}
			}
			Matcher ym = YEAR_MONTH.matcher(text);
			while (ym.find()) {
				figures.yearMonths.add(ym.group(1) + "-" + Integer.parseInt(ym.group(2)));
			}
			Matcher md = MONTH_DAY.matcher(text);
			while (md.find()) {
				figures.monthDays.add(Integer.parseInt(md.group(1)) + "-" + Integer.parseInt(md.group(2)));
			}
			Matcher shortYm = SHORT_YEAR_MONTH.matcher(text);
			while (shortYm.find()) {
				int yy = Integer.parseInt(shortYm.group(1));
				figures.yearMonths.add((yy > 50 ? 1900 + yy : 2000 + yy) + "-" + Integer.parseInt(shortYm.group(2)));
			}
			Matcher compound = COMPOUND.matcher(text);
			while (compound.find()) {
				Amount amount = Amount.parse(compound.group());
				if (amount != null) {
					figures.add(amount.value());
				}
			}
			Matcher plain = Pattern.compile(NUM).matcher(text);
			while (plain.find()) {
				figures.add(number(plain.group()));
			}
			// 비율은 날짜의 연·월·일과 견주지 않는다 (AI가 계산한 "15%"가 날짜의 15일로 통과하지 않게)
			Matcher nonDate = Pattern.compile(NUM).matcher(withoutDates);
			while (nonDate.find()) {
				figures.nonDateValues.add(number(nonDate.group()).abs());
			}
			return figures;
		}

		private void addDate(String year, String month, String day) {
			int m = Integer.parseInt(month);
			int d = Integer.parseInt(day);
			if (m < 1 || m > 12 || d < 1 || d > 31) {
				return;
			}
			dates.add(date(year, month, day));
			yearMonths.add(year + "-" + m);
			monthDays.add(m + "-" + d);
		}

		private void add(BigDecimal value) {
			if (numbers.add(key(value))) {
				values.add(value.abs());
			}
		}

		/** 단위를 붙인 금액이 본문의 어떤 숫자(표의 단위 배수 포함)를 그 자릿수로 반올림·버림한 값과 같은지. */
		boolean matchesAmount(Amount amount) {
			BigDecimal shown = amount.value().divide(amount.unit()).setScale(amount.decimals(), RoundingMode.HALF_UP);
			boolean truncationOk = significantDigits(shown) >= TRUNCATION_MIN_DIGITS;
			for (BigDecimal value : values) {
				for (BigDecimal scale : scales) {
					if (scale.compareTo(BigDecimal.ONE) != 0 && !scalable(value)) {
						continue;
					}
					BigDecimal inUnit = value.multiply(scale).divide(amount.unit());
					if (inUnit.setScale(amount.decimals(), RoundingMode.HALF_UP).compareTo(shown) == 0
							|| truncationOk && inUnit.setScale(amount.decimals(), RoundingMode.DOWN).compareTo(shown) == 0) {
						return true;
					}
				}
			}
			return false;
		}

		/** 표의 금액 단위 배수를 곱해 볼 숫자인지: 날짜의 연·월·일이 아니고, 연도처럼 보이는 정수가 아니다. */
		private boolean scalable(BigDecimal value) {
			boolean yearLike = value.stripTrailingZeros().scale() <= 0 && value.compareTo(YEAR_FROM) >= 0
					&& value.compareTo(YEAR_TO) <= 0;
			return !yearLike && nonDateValues.stream().anyMatch(v -> v.compareTo(value) == 0);
		}

		/** 비율이 본문의 어떤 숫자를 그 자릿수로 반올림·버림한 값과 같은지. */
		boolean matchesRounded(BigDecimal shown) {
			BigDecimal target = shown.abs();
			int decimals = Math.max(0, target.scale());
			boolean truncationOk = significantDigits(target) >= TRUNCATION_MIN_DIGITS;
			for (BigDecimal value : nonDateValues) {
				if (value.setScale(decimals, RoundingMode.HALF_UP).compareTo(target) == 0
						|| truncationOk && value.setScale(decimals, RoundingMode.DOWN).compareTo(target) == 0) {
					return true;
				}
			}
			return false;
		}
	}
}
