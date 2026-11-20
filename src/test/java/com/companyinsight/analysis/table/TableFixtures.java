package com.companyinsight.analysis.table;

import com.companyinsight.analysis.body.BodyDocument;
import com.companyinsight.analysis.body.DisclosureBodyReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;

/**
 * 테스트에 쓰는 실제 공시 본문. DART의 공시서류원본파일 응답(zip)을 그대로 받아 둔 것이다.
 * 기대값은 각 공시의 원문 표에 적힌 값이다(DART 공시뷰어에서 접수번호로 볼 수 있다).
 */
final class TableFixtures {

	// ── 잠정실적 (B1) ───────────────────────────────────────────────

	/** SK하이닉스 2026-10-28 연결재무제표기준영업(잠정)실적. 영업이익 83,333,818 / 전년동기 11,383,390 (백만원), 전년동기대비 632.1% */
	static final String PRELIM_UP = "20261028800036";

	/** 현대자동차 2026-01-29 연결재무제표기준영업(잠정)실적. 영업이익 1,695,379 / 전년동기 2,822,194, 전년동기대비 -39.9% */
	static final String PRELIM_DOWN_39 = "20260129800342";

	/** 현대자동차 2026-07-23 연결재무제표기준영업(잠정)실적. 영업이익 2,850,888 / 전년동기 3,601,597, 전년동기대비 -20.8% */
	static final String PRELIM_DOWN_20 = "20260723800315";

	/** 현대자동차 2026-10-29 연결재무제표기준영업(잠정)실적. 영업이익 2,677,232 / 전년동기 2,537,266, 전년동기대비 5.5% */
	static final String PRELIM_SMALL = "20261029800228";

	/** 현대자동차 2026-11-02 영업(잠정)실적. 월별 판매 대수만 알린다. 영업이익 줄의 칸이 모두 "-" */
	static final String PRELIM_MONTHLY = "20261102800520";

	/**
	 * 삼성전자 2026-01-29 [기재정정]연결재무제표기준영업(잠정)실적. 맨 위 정정 표에 정정 전(20.00)·후(20.07)가 한 칸에 이어 적혀 있다.
	 * 본문 표: 영업이익 20.07 / 전년동기 6.49 (조원), 전년동기대비 209.17%
	 */
	static final String PRELIM_CORRECTION = "20260129800008";

	/** 씨어스 2026-11-06 (코스닥). 영업이익 -1,899 / 전년동기 6,726, 전년동기대비 증감율 "-", 흑자적자전환여부 "적자전환" */
	static final String PRELIM_TURN_TO_LOSS = "20261106900007";

	/** 신세계인터내셔날 2026-11-10. 영업이익 5,148 / 전년동기 -4,259, 전년동기대비 증감율 "-", 흑자적자전환여부 "흑자전환" */
	static final String PRELIM_TURN_TO_PROFIT = "20261110800326";

	/** 지누스 2026-11-03. 영업이익 -12,486 / 전년동기 -7,633, 전년동기대비 -63.6%, 흑자적자전환여부 "-". 적자 폭이 늘었다 */
	static final String PRELIM_DEFICIT_WIDENED = "20261103800355";

	/** SKC 2026-10-28. 영업이익 -496 / 전년동기 -549, 전년동기대비 9.7%, 흑자적자전환여부 "-". 적자 폭이 줄었다 */
	static final String PRELIM_DEFICIT_NARROWED = "20261028800215";

	/**
	 * HD현대 2026-10-28 연결재무제표기준영업(잠정)실적(공정공시)(자회사의주요경영사항). 지주회사가 자회사의 실적을 알린다.
	 * 영업이익 9,009 / 전년동기 1,912, 전년동기대비 371.2%
	 */
	static final String PRELIM_OF_SUBSIDIARY = "20261028800354";

	// ── 손익구조 변동 (B4) ──────────────────────────────────────────

	/** SK하이닉스 2026-01-28. 영업이익 47,206,319,000 / 직전 23,467,319,000 (천원), 증감비율 101.2% */
	static final String CHANGE_UP_101 = "20260128800679";

	/** 삼성전자 2026-01-29. 영업이익 43,601,100,000 / 직전 32,726,000,000, 증감비율 33.23% */
	static final String CHANGE_UP_33 = "20260129800005";

	/** 현대자동차 2026-01-29. 영업이익 11,467,851,000 / 직전 14,239,592,000, 증감비율 -19.5% */
	static final String CHANGE_DOWN_19 = "20260129800345";

	// ── 감사보고서 제출 (B5) ────────────────────────────────────────

	/** SK하이닉스 2026-03-04 감사보고서제출. 연결·별도 모두 감사의견 적정, 계속기업 미해당·미기재, 내부회계 미해당 */
	static final String AUDIT_CLEAN = "20260304801318";

	/** 삼성전자 2026-02-19 [기재정정]감사보고서제출. 맨 위에 정정 표. 본문은 모두 적정·미해당 */
	static final String AUDIT_CORRECTION = "20260219800741";

	/**
	 * 코람코더원리츠 2026-10-28 감사보고서제출. 별도 재무제표만 있다. 감사의견 적정, 계속기업 사유 미해당,
	 * 감사의견과 관련 없는 계속기업 존속 불확실성 "기재", 내부회계관리제도 "-"
	 */
	static final String AUDIT_GOING_CONCERN_NOTED = "20261028800384";

	/**
	 * 제일엠앤에스 2026-10-29 [기재정정]감사보고서제출(2024사업연도). 정정 표: 감사의견 의견거절 → 적정.
	 * 본문: 연결·별도 감사의견 적정, 계속기업 불확실성 기재(연결·별도), 별도 내부회계관리제도 비적정 "해당"
	 */
	static final String AUDIT_CORRECTED_TO_CLEAN = "20261029900293";

	// ── 유상증자 결정 (D1) ──────────────────────────────────────────

	/**
	 * SK하이닉스 2026-01-28 유상증자결정(종속회사의주요경영사항). 거래소 서식(HTML).
	 * 증자방식 주주배정증자, 신주 보통주식 10,000,000,000주, 증자전 발행주식총수 1,693,653,240주
	 */
	static final String RIGHTS_SUBSIDIARY = "20260128800941";

	/**
	 * SK하이닉스 2026-06-24 주요사항보고서(유상증자결정). 금융위 서식(전용 XML).
	 * 증자방식 제3자배정증자, 신주 보통주식 17,790,000주, 증자전 발행주식총수 712,702,365주
	 */
	static final String RIGHTS_THIRD_PARTY = "20260624000420";

	/** SK하이닉스 2026-07-10 [기재정정]주요사항보고서(유상증자결정). 정정 표와 정정 전·후 표의 사본이 본문 앞에 있다. 값은 위와 같다 */
	static final String RIGHTS_CORRECTION = "20260710000008";

	/**
	 * 상지건설 2026-10-29 [기재정정]주요사항보고서(유상증자결정). 증자방식 주주우선공모증자,
	 * 신주 보통주식 2,200,000주, 증자전 발행주식총수 6,828,712주 (32.2169%)
	 */
	static final String RIGHTS_PRIORITY_PUBLIC = "20261029000254";

	/**
	 * 한울앤제주 2026-11-05 [기재정정]주요사항보고서(유상증자결정). 증자방식 일반공모증자,
	 * 신주 보통주식 2,702,158주, 증자전 발행주식총수 23,607,980주 (11.4460%). 정정 표에도 "1. 신주의 종류와 수" 줄이 있다
	 */
	static final String RIGHTS_PUBLIC = "20261105000386";

	// ── 지분 보고 (A2) ─────────────────────────────────────────────
	// 개인을 알아볼 수 있는 칸(이름, 생년월일·등록번호, 주소, 연락처)은 가렸다. 숫자, 날짜, 변동 사유는 원문 그대로다.

	/** 2026-11-12 임원ㆍ주요주주 보고서(보고구분 신규). 장내매수(+) 9,514주와 4,986주, 발행주식 총수 22,184,124 → 0.065362% */
	static final String INSIDER_BUY = "20261112000174";

	/** 2026-10-29 임원ㆍ주요주주 보고서. 장내매수(+) 다섯 줄 합 63,199주, 발행주식 총수 22,055,785 → 0.286542% */
	static final String INSIDER_BUY_LARGE = "20261029000083";

	/** 2026-10-22 임원ㆍ주요주주 보고서. 장내매도(-) 1,215주와 1,285주, 발행주식 총수 10,957,422 → -0.022816% */
	static final String INSIDER_SELL = "20261022000277";

	/** 삼성전자 2026-02-02 임원ㆍ주요주주 보고서. 자사주상여금(+) 260주 한 줄 */
	static final String INSIDER_BONUS = "20260202000499";

	/** 삼성전자 2026-02-04 임원ㆍ주요주주 보고서. 장내매수(+) 2,566주와 장내매도(-) 2,566주 */
	static final String INSIDER_BUY_AND_SELL = "20260204000511";

	/** 삼성전자 2025-12-01 임원ㆍ주요주주 보고서. 신규선임(+) 603주와, 증감을 0으로 적은 장내매수(+) 한 줄 */
	static final String INSIDER_ZERO_TRADE = "20251201000265";

	/**
	 * SK하이닉스 2026-08-21 [기재정정]임원ㆍ주요주주 보고서. 맨 위 정정 표는 단가만 고쳤다.
	 * 장내매수(+) 5주, 장내매도(-) 600주, 발행주식 총수 730,492,365 → -0.000081%
	 */
	static final String INSIDER_CORRECTION = "20260821000615";

	/** 2026-11-13 최대주주등소유주식변동신고서. 친인척 한 사람의 장내매도(-) 2,000주, 발행주식총수 17,072,000 → -0.011715% */
	static final String LARGEST_SELL = "20261113801264";

	/** 2026-11-03 최대주주등소유주식변동신고서. 최대주주등 안에서 시간외매매(-) 3,400,000주와 시간외매매(+) 3,400,000주 */
	static final String LARGEST_WITHIN_GROUP = "20261103800642";

	/** 2026-10-26 최대주주등소유주식변동신고서. 기타(-) 39,523주 한 줄 */
	static final String LARGEST_OTHER = "20261026800368";

	/** 2026-10-29 주식등의대량보유상황보고서(약식). 주식등의 비율 9.62 → 10.68, 세부변동내역은 장내매수(+) */
	static final String BLOCK_UP = "20261029000259";

	/** 2026-10-21 주식등의대량보유상황보고서(약식). 주식등의 비율 5.00 → 4.08, 세부변동내역에 시간외매매(-)와 장내매도(-) */
	static final String BLOCK_DOWN = "20261021000162";

	/** 2026-11-10 주식등의대량보유상황보고서(약식). 주식등의 비율 8.75 → 3.67, 세부변동내역은 CB인수(+), 전환등(-), 전환사채의권리행사(+)뿐 */
	static final String BLOCK_NO_TRADE = "20261110000361";

	/** 2026-10-28 주식등의대량보유상황보고서(약식). 신규 보고. 직전 보고서 줄이 "-", 이번 보고서 15.73 */
	static final String BLOCK_NEW = "20261028000491";

	/** 2026-11-03 주식등의대량보유상황보고서(약식). 5.00 → 2.58, 세부변동내역에 "풋옵션권리행사에따른주식처분(-)" */
	static final String BLOCK_UNKNOWN_REASON = "20261103000254";

	/**
	 * 2026-11-16 주식등의대량보유상황보고서(일반). 주식등의 비율 73.92 → 73.73. 주식 수는 그대로이고
	 * 주식매수선택권 행사로 발행주식총수가 늘어 비율만 줄었다. 보유목적 "경영권 영향"은 항목 이름 없는 한 칸짜리 표에 있다
	 */
	static final String BLOCK_GENERAL_DILUTED = "20261116001399";

	/** 2026-10-23 [기재정정]주식등의대량보유상황보고서(일반). 31.97 → 31.97. 정정 표에 원래 결정을 거뒀다는 뜻의 낱말이 있다 */
	static final String BLOCK_GENERAL_CORRECTION = "20261023000450";

	private TableFixtures() {
	}

	/** 접수번호로 받아 둔 zip을 읽는다. */
	static byte[] zip(String receiptNo) {
		try (InputStream in = TableFixtures.class.getResourceAsStream("/dart/body/" + receiptNo + ".zip")) {
			if (in == null) {
				throw new IllegalArgumentException("받아 둔 본문이 없습니다: " + receiptNo);
			}
			return in.readAllBytes();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** 받아 둔 본문의 문서들. */
	static List<BodyDocument> documents(String receiptNo) {
		return DisclosureBodyReader.documents(zip(receiptNo));
	}

	/** 받아 둔 본문의 표(정정 표 제외). */
	static BodyTables tables(String receiptNo) {
		return BodyTables.of(documents(receiptNo));
	}

	/**
	 * 받아 둔 본문에서 글자 한 곳을 바꾼 문서의 표. 실제 공시에 없는 경우(비적정 의견 등)를 만들 때 쓴다.
	 *
	 * @param nth 몇 번째로 나온 것을 바꿀지 (1부터)
	 */
	static BodyTables tablesWith(String receiptNo, String target, String replacement, int nth) {
		List<BodyDocument> changed = documents(receiptNo).stream().map(document -> {
			String markup = document.markup();
			int at = -1;
			for (int i = 0; i < nth; i++) {
				at = markup.indexOf(target, at + 1);
				if (at < 0) {
					throw new IllegalArgumentException("바꿀 글자가 본문에 " + nth + "번 나오지 않습니다: " + target);
				}
			}
			return new BodyDocument(document.fileName(), document.format(),
				markup.substring(0, at) + replacement + markup.substring(at + target.length()), document.text());
		}).toList();
		return BodyTables.of(changed);
	}

	/** 운영 설정(application.yml)과 같은 값. */
	/** 본문에서 한 글자열을 나오는 대로 모두 바꾼 표. */
	static BodyTables tablesWithAll(String receiptNo, String target, String replacement) {
		return BodyTables.of(documentsWithAll(receiptNo, target, replacement));
	}

	/** 본문에서 글자열을 나오는 대로 모두 바꾼 문서. 바꿀 글자열과 바꿔 넣을 글자열을 번갈아 적는다. */
	static List<BodyDocument> documentsWithAll(String receiptNo, String... pairs) {
		return documents(receiptNo).stream().map(document -> {
			String markup = document.markup();
			for (int i = 0; i < pairs.length; i += 2) {
				if (!markup.contains(pairs[i])) {
					throw new IllegalArgumentException("바꿀 글자가 본문에 없습니다: " + pairs[i]);
				}
				markup = markup.replace(pairs[i], pairs[i + 1]);
			}
			return new BodyDocument(document.fileName(), document.format(), markup, document.text());
		}).toList();
	}

	static ImpactProperties impact() {
		return new ImpactProperties(
			new ImpactProperties.Earnings(new BigDecimal("10"), new BigDecimal("30"), new BigDecimal("100"), 3, 1),
			new ImpactProperties.ShareIncrease(new BigDecimal("5"), new BigDecimal("20")),
			new ImpactProperties.Audit(3, 2, 2),
			new ImpactProperties.Ownership(new BigDecimal("0.01"), new BigDecimal("0.1"), new BigDecimal("1")), 1);
	}
}
