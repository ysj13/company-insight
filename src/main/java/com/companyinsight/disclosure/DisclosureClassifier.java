package com.companyinsight.disclosure;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 공시 보고서명만 보고 종류와 호재·악재를 판정하는 규칙.
 *
 * <p>제목으로 방향이 정해지는 공시(자기주식 취득, 정기보고서 등)는 여기서 판정을 끝내고,
 * 본문을 읽어야 알 수 있는 공시(실적, 공급계약, 증자 등)는 종류만 정하고 tone을 비워 둔다.
 * 규칙에 없는 제목도 tone을 비워 둔다. 모르는 것을 중립으로 단정하지 않기 위해서다.
 *
 * <p>규칙은 위에서부터 차례로 맞춰 보고 처음 맞는 것을 쓴다. 그래서 더 긴 제목을 먼저 둔다
 * (예: "불성실공시법인지정예고"가 "불성실공시법인지정"보다 앞).
 *
 * <p>규칙을 바꾸면 {@link #RULE_VERSION}을 올린다. 옛 버전으로 분류된 공시를 다시 분류하는 기준이다.
 */
@Component
public class DisclosureClassifier {

	/** 규칙 버전. {@code disclosure.rule_version}에 저장한다. */
	public static final int RULE_VERSION = 1;

	/** 보고서명 앞의 머리말. "[기재정정]", "[첨부추가]" 등. 여러 개가 이어 붙을 수 있다. */
	private static final Pattern PREFIXES = Pattern.compile("^(\\[[^\\]]*\\])+");

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	/** 금융위에 내는 주요사항보고서는 "주요사항보고서(자기주식취득결정)"처럼 실제 내용이 괄호 안에 있다. */
	private static final String MAJOR_REPORT_OPEN = "주요사항보고서(";

	/** 비고의 철회 표시 */
	private static final String WITHDRAWN_MARK = "철";

	private static final List<Rule> RULES = List.of(
		// --- 거래소 시장안내. 같은 제목 아래 괄호 안 사유로 갈린다 ---
		rule(notice("상장폐지우려"), DisclosureKind.MARKET, Tone.NEGATIVE, 3),
		rule(notice("관리종목지정우려").or(notice("관리종목지정사유추가우려")), DisclosureKind.MARKET, Tone.NEGATIVE, 2),
		rule(startsWith("불성실공시법인지정예고"), DisclosureKind.MARKET, Tone.NEGATIVE, 1),
		rule(startsWith("불성실공시법인지정"), DisclosureKind.MARKET, Tone.NEGATIVE, 2),

		// --- 호재 ---
		rule(startsWith("주식소각결정"), DisclosureKind.MAJOR, Tone.POSITIVE, 3),
		rule(startsWith("회생절차종결결정"), DisclosureKind.MAJOR, Tone.POSITIVE, 3),
		rule(startsWith("자기주식취득결정"), DisclosureKind.MAJOR, Tone.POSITIVE, 2),
		rule(startsWith("자기주식취득신탁계약체결결정"), DisclosureKind.MAJOR, Tone.POSITIVE, 2),
		rule(startsWith("현금ㆍ현물배당결정"), DisclosureKind.MAJOR, Tone.POSITIVE, 2),
		rule(startsWith("무상증자결정"), DisclosureKind.MAJOR, Tone.POSITIVE, 2),
		rule(startsWith("파산신청기각"), DisclosureKind.MAJOR, Tone.POSITIVE, 2),
		rule(startsWith("기업가치제고계획예고"), DisclosureKind.IR, Tone.NEUTRAL, 0),
		rule(startsWith("기업가치제고계획"), DisclosureKind.MAJOR, Tone.POSITIVE, 1),
		rule(startsWith("생산재개"), DisclosureKind.MAJOR, Tone.POSITIVE, 1),

		// --- 악재 ---
		rule(startsWith("횡령ㆍ배임혐의발생"), DisclosureKind.MAJOR, Tone.NEGATIVE, 3),
		rule(startsWith("회생절차개시신청"), DisclosureKind.MAJOR, Tone.NEGATIVE, 3),
		rule(startsWith("파산신청"), DisclosureKind.MAJOR, Tone.NEGATIVE, 3),
		rule(startsWith("대출원리금연체사실발생"), DisclosureKind.MAJOR, Tone.NEGATIVE, 3),
		rule(startsWith("영업정지"), DisclosureKind.MAJOR, Tone.NEGATIVE, 3),
		rule(startsWith("자본잠식50%이상"), DisclosureKind.MAJOR, Tone.NEGATIVE, 3),
		rule(startsWith("반기검토의견부적정"), DisclosureKind.MAJOR, Tone.NEGATIVE, 3),
		rule(startsWith("회계처리기준위반"), DisclosureKind.MAJOR, Tone.NEGATIVE, 3),
		rule(startsWith("단일판매ㆍ공급계약해지"), DisclosureKind.MAJOR, Tone.NEGATIVE, 2),
		rule(startsWith("거래처와의거래중단"), DisclosureKind.MAJOR, Tone.NEGATIVE, 2),
		rule(startsWith("생산중단"), DisclosureKind.MAJOR, Tone.NEGATIVE, 2),
		rule(startsWith("파생상품거래손실발생"), DisclosureKind.MAJOR, Tone.NEGATIVE, 2),
		rule(startsWith("중대재해발생"), DisclosureKind.MAJOR, Tone.NEGATIVE, 2),
		rule(startsWith("벌금등의부과"), DisclosureKind.MAJOR, Tone.NEGATIVE, 1),

		// --- 중립: 정기보고서 ---
		neutral(DisclosureKind.PERIODIC, "분기보고서", "반기보고서", "사업보고서"),
		// --- 중립: 지분 보고. 매수·매도 방향은 본문에만 있고 건수가 많아 방향을 판정하지 않는다 ---
		neutral(DisclosureKind.OWNERSHIP, "임원ㆍ주요주주특정증권등소유상황보고서", "주식등의대량보유상황보고서",
			"최대주주등소유주식변동신고서", "임원ㆍ주요주주특정증권등거래계획"),
		// --- 중립: 기업설명회와 일정 ---
		neutral(DisclosureKind.IR, "기업설명회", "결산실적공시예고"),
		// --- 중립: 증권 발행 서류와 발행 조건 조정 ---
		neutral(DisclosureKind.ISSUANCE, "증권발행실적보고서", "투자설명서", "일괄신고추가서류", "일괄신고서", "증권신고서",
			"소액공모공시서류", "소액공모실적보고서", "증권발행결과", "전환가액의조정", "신주인수권행사가액의조정",
			"교환가액의조정", "전환가액ㆍ신주인수권행사가액ㆍ교환가액의조정"),
		// --- 중립: 주주총회와 절차 ---
		neutral(DisclosureKind.GOVERNANCE, "주주총회소집결의", "주주총회소집공고", "주주명부폐쇄기간또는기준일설정",
			"임시주주총회결과", "정기주주총회결과", "의결권대리행사권유참고서류", "현금ㆍ현물배당을위한주주명부폐쇄",
			"중간(분기)배당을위한주주명부폐쇄", "본점소재지변경", "상호변경안내", "소속부변경", "주식매수선택권부여에관한신고"),
		// --- 중립: 이미 공시한 결정의 결과 보고 ---
		neutral(DisclosureKind.MAJOR, "자기주식취득결과보고서", "자기주식처분결과보고서", "신탁계약해지결과보고서",
			"신탁계약에의한취득상황보고서"),
		// --- 중립: 계열회사·특수관계인과의 통상 거래 ---
		neutral(DisclosureKind.ETC, "동일인등출자계열회사와의상품ㆍ용역거래", "특수관계인과의내부거래"),

		// --- 종류만 정하고 판정은 본문을 읽어서 ---
		undecided(DisclosureKind.EARNINGS, "영업(잠정)실적", "연결재무제표기준영업(잠정)실적", "영업실적등에대한전망",
			"연결재무제표기준영업실적등에대한전망", "매출액또는손익구조30%"),
		undecided(DisclosureKind.MAJOR, "단일판매ㆍ공급계약체결", "유상증자결정", "유무상증자결정", "전환사채권발행결정",
			"신주인수권부사채권발행결정", "교환사채권발행결정", "회사합병결정", "회사분할결정", "회사분할합병결정",
			"주식교환ㆍ이전결정", "타법인주식및출자증권", "감자결정", "자기주식처분결정", "자기주식취득신탁계약해지결정",
			"최대주주변경", "소송등", "신규시설투자등", "투자판단관련주요경영사항", "영업양수결정", "영업양도결정", "유형자산"),
		undecided(DisclosureKind.MARKET, "기타시장안내", "투자유의안내", "주권매매거래정지", "매매거래정지", "조회공시요구",
			"풍문또는보도에대한해명", "불성실공시법인미지정"));

	/**
	 * 공시 한 건을 분류한다.
	 *
	 * @param reportName 보고서명. 머리말과 공백이 섞여 있어도 된다.
	 * @param remark     DART 비고. 없으면 null.
	 */
	public Classification classify(String reportName, String remark) {
		String name = normalize(reportName);

		String withoutPrefix = PREFIXES.matcher(name).replaceFirst("");
		boolean correction = withoutPrefix.length() != name.length();
		name = withoutPrefix;

		boolean majorReport = name.startsWith(MAJOR_REPORT_OPEN);
		if (majorReport) {
			name = name.substring(MAJOR_REPORT_OPEN.length());
		}

		DisclosureKind kind = majorReport ? DisclosureKind.MAJOR : DisclosureKind.ETC;
		Tone tone = null;
		Integer impact = null;
		for (Rule rule : RULES) {
			if (rule.matcher().test(name)) {
				kind = rule.kind();
				tone = rule.tone();
				impact = impactOf(rule, name);
				break;
			}
		}

		boolean withdrawn = remark != null && remark.contains(WITHDRAWN_MARK);
		if (withdrawn) {
			// 철회된 공시는 없던 일이 된다. 호재·악재로 세지 않고, 본문을 읽을 대상에서도 뺀다.
			tone = Tone.NEUTRAL;
			impact = null;
		}
		return new Classification(kind, tone, impact, tone == null ? null : ClassifiedBy.RULE, correction, withdrawn);
	}

	/**
	 * 영향도. 자회사·종속회사에서 일어난 일은 한 단계 낮춘다(최소 1).
	 * 같은 사건이라도 회사 자신의 일보다 영향이 작다고 본다.
	 */
	private static Integer impactOf(Rule rule, String name) {
		if (rule.tone() == null || rule.tone() == Tone.NEUTRAL) {
			return null;
		}
		boolean subsidiary = name.contains("자회사의주요경영사항") || name.contains("종속회사의주요경영사항");
		return subsidiary ? Math.max(1, rule.impact() - 1) : rule.impact();
	}

	/**
	 * 규칙과 맞춰 볼 수 있게 다듬는다. 공백을 모두 없애고 가운뎃점을 DART가 쓰는 글자(ㆍ)로 통일한다.
	 * 같은 공시도 "기타시장안내 (개선기간 부여)"처럼 공백이 제각각이다.
	 */
	private static String normalize(String reportName) {
		if (reportName == null) {
			return "";
		}
		return WHITESPACE.matcher(reportName).replaceAll("").replace('·', 'ㆍ').replace('・', 'ㆍ');
	}

	private static Predicate<String> startsWith(String prefix) {
		return name -> name.startsWith(prefix);
	}

	/** 거래소 시장안내(기타시장안내, 투자유의안내) 중 사유에 이 말이 들어 있는 것 */
	private static Predicate<String> notice(String reason) {
		return name -> (name.startsWith("기타시장안내") || name.startsWith("투자유의안내")) && name.contains(reason);
	}

	private static Rule rule(Predicate<String> matcher, DisclosureKind kind, Tone tone, int impact) {
		return new Rule(matcher, kind, tone, impact);
	}

	private static Rule neutral(DisclosureKind kind, String... prefixes) {
		return new Rule(startsWithAny(prefixes), kind, Tone.NEUTRAL, 0);
	}

	private static Rule undecided(DisclosureKind kind, String... prefixes) {
		return new Rule(startsWithAny(prefixes), kind, null, 0);
	}

	private static Predicate<String> startsWithAny(String... prefixes) {
		List<String> list = List.of(prefixes);
		return name -> list.stream().anyMatch(name::startsWith);
	}

	/**
	 * 규칙 한 줄.
	 *
	 * @param matcher 다듬은 보고서명이 이 규칙에 해당하는지
	 * @param kind    공시 종류
	 * @param tone    판정. null이면 종류만 정하고 판정은 하지 않는다
	 * @param impact  영향도 1~3. 호재·악재가 아니면 쓰지 않는다
	 */
	private record Rule(Predicate<String> matcher, DisclosureKind kind, Tone tone, int impact) {
	}
}
