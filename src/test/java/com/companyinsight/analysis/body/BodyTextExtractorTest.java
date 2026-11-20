package com.companyinsight.analysis.body;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 글자 추출. 기대값은 실제 공시 원문에 적힌 글자다(DART에서 받은 zip을 그대로 둔 것).
 */
class BodyTextExtractorTest {

	// ── 실제 공시: 거래소 공시(HTML) ──────────────────────────────────

	@Test
	void 거래소_공시는_HTML로_읽고_표의_한_줄이_글의_한_줄이_된다() {
		BodyDocument document = BodyFixtures.document(BodyFixtures.VOLUNTARY);

		assertThat(document.fileName()).isEqualTo("20251128800899.xml");
		assertThat(document.format()).isEqualTo(BodyFormat.HTML);
		assertThat(lines(document)).containsExactly(
			"기타 경영사항(자율공시)",
			"1. 제목 | 사회복지공동모금회 연말성금 출연",
			"2. 주요내용 | - 사회복지공동모금회의 희망나눔캠페인에 연말 이웃사랑 성금을 기부하여 소외계층과 온기를 나누고 기업의 사회적 책임을 다하고자 함"
					+ " - 기부금액: 103.5억원 - 기부처: 사회복지법인 사회복지공동모금회",
			"3. 결정(확인)일자 | 2025-11-28",
			"4. 기타 투자판단과 관련한 중요사항",
			"- 상기 3항의 결정(확인)일자는 이사회 결의일임",
			"※ 관련공시 | -");
	}

	@Test
	void 스타일과_문서_머리의_제목은_글에_들어가지_않는다() {
		BodyDocument document = BodyFixtures.document(BodyFixtures.VOLUNTARY);

		// 원문에는 <STYLE>과 <title>삼성전자/기타 경영사항(자율공시)/(2025.11.28)…</title>이 있다
		assertThat(document.markup()).contains("font-family").contains("삼성전자/기타 경영사항(자율공시)/");
		assertThat(document.text()).doesNotContain("font-family").doesNotContain("삼성전자/기타");
	}

	@Test
	void 파일_안에_euc_kr이라고_적혀_있어도_UTF_8로_읽는다() {
		BodyDocument document = BodyFixtures.document(BodyFixtures.VOLUNTARY);

		assertThat(document.markup()).contains("charset=euc-kr");
		assertThat(document.text()).contains("사회복지공동모금회");
	}

	@Test
	void 정정_공시는_맨_위의_정정_표와_정정이_반영된_본문을_모두_읽는다() {
		BodyDocument document = BodyFixtures.document(BodyFixtures.CORRECTION);

		assertThat(lines(document)).startsWith(
			"정정신고(보고)",
			"정정일자 | 2026-08-24",
			"1. 정정관련 공시서류 | 생산중단",
			"2. 정정관련 공시서류제출일 | 2026-07-13",
			"3. 정정사유 | 단체교섭 관련 추가 파업 일정 반영",
			"4. 정정사항",
			"정정항목 | 정정전 | 정정후");
		// 칸 안에 줄바꿈이 있어도 표의 한 줄은 한 줄이다. 정정 전과 정정 후가 섞이지 않는다
		assertThat(lines(document)).contains(
			"9. 이사회결의일(결정일) | 2026-07-13 | 2026-08-21",
			"7. 생산중단일자 | 2026년 7월 13일(1조,2조 각 2시간) 2026년 7월 14일(1조,2조 각 2시간) 2026년 7월 15일(1조,2조 각 2시간)"
					+ " | 2026년 7월 13일(1조,2조 각 2시간) 2026년 7월 14일(1조,2조 각 2시간) 2026년 7월 15일(1조,2조 각 2시간) 2026년 8월 21일(전일)",
			"2. 생산중단내역 | 생산중단 분야의 매출액(원) | 78,766,791,000,000",
			"매출액대비(%) | 42.29",
			"※ 관련공시 | 2026-07-13 생산중단");
	}

	// ── 실제 공시: 금융위 문서(DART 전용 XML) ─────────────────────────

	@Test
	void 금융위_문서는_전용_XML로_읽는다() {
		BodyDocument document = BodyFixtures.document(BodyFixtures.TREASURY_DISPOSAL);

		assertThat(document.format()).isEqualTo(BodyFormat.XML);
		assertThat(document.markup()).contains("<DOCUMENT-NAME ACODE=\"11333\">");
		assertThat(lines(document)).startsWith(
			"주요사항보고서(자기주식 처분 결정)",
			"삼성전자",
			"주요사항보고서 / 거래소 신고의무 사항");
		assertThat(lines(document)).contains(
			"회 사 명 : | 삼성전자주식회사",
			"자기주식 처분 결정",
			"1. 처분예정주식(주) | 보통주식 | 1,152,022",
			"3. 처분예정금액(원) | 보통주식 | 175,222,546,200",
			"5. 처분목적 | 임원 등 성과급의 자기주식 지급",
			"7. 처분상대방 | 전영현 외 1,050명",
			"10. 처분결정일 | 2026년 01월 23일");
	}

	@Test
	void 전용_XML의_서식_버전은_글에_들어가지_않는다() {
		BodyDocument document = BodyFixtures.document(BodyFixtures.TREASURY_DISPOSAL);

		assertThat(document.markup()).contains("<FORMULA-VERSION ADATE=\"20251230\">6.2</FORMULA-VERSION>");
		assertThat(lines(document)).doesNotContain("6.2");
	}

	// ── 경계 입력 ───────────────────────────────────────────────────

	@Test
	void UTF_8이_아닌_파일은_MS949로_읽는다() {
		byte[] ms949 = "<html><body><p>유상증자 결정</p></body></html>".getBytes(Charset.forName("MS949"));

		assertThat(extract(ms949).text()).isEqualTo("유상증자 결정");
	}

	@Test
	void 맨_앞의_BOM은_뗀다() {
		byte[] withBom = ("﻿<?xml version=\"1.0\" encoding=\"utf-8\"?><DOCUMENT><BODY><P>본문</P></BODY></DOCUMENT>")
			.getBytes(StandardCharsets.UTF_8);

		BodyDocument document = extract(withBom);

		assertThat(document.format()).isEqualTo(BodyFormat.XML);
		assertThat(document.text()).isEqualTo("본문");
	}

	@Test
	void 이어진_공백과_탭과_줄바꿈은_공백_하나가_되고_빈_줄과_빈_칸은_없앤다() {
		String html = """
			<html><body>
			<table>
			<tr><td>  회사명\t :  </td><td></td><td>삼성전자
			   주식회사&nbsp;&nbsp;</td></tr>
			<tr><td></td><td>　</td></tr>
			</table>
			<p>   </p>
			<p>끝</p>
			</body></html>
			""";

		assertThat(extract(html.getBytes(StandardCharsets.UTF_8)).text()).isEqualTo("회사명 : | 삼성전자 주식회사\n끝");
	}

	@Test
	void 글자로_적힌_기호는_원래_글자로_바꾼다() {
		String html = "<html><body><p>R&amp;D 투자 &lt;1,000억원&gt;</p></body></html>";

		assertThat(extract(html.getBytes(StandardCharsets.UTF_8)).text()).isEqualTo("R&D 투자 <1,000억원>");
	}

	@Test
	void 전용_XML의_칸_요소_TU와_TE도_칸으로_읽고_처음_보는_요소는_줄을_바꾼다() {
		String xml = """
			<?xml version="1.0" encoding="utf-8"?>
			<DOCUMENT>
			<SUMMARY><EXTRACTION ACODE="HOLDCHECK">1100</EXTRACTION></SUMMARY>
			<BODY><SECTION-1><TITLE ATOC="Y">제1부 보고의 개요</TITLE>
			<TABLE><TBODY><TR><TH>구분</TH><TU AUNIT="X">보유비율</TU><TE>19.83</TE></TR></TBODY></TABLE>
			<UNKNOWN-TAG>처음 보는 요소</UNKNOWN-TAG><P>다음 문단</P>
			</SECTION-1></BODY>
			</DOCUMENT>
			""";

		assertThat(lines(extract(xml.getBytes(StandardCharsets.UTF_8)))).containsExactly(
			"제1부 보고의 개요",
			"구분 | 보유비율 | 19.83",
			"처음 보는 요소",
			"다음 문단");
	}

	@Test
	void 칸_밖의_줄바꿈_요소는_줄을_바꾼다() {
		String html = "<html><body><div>첫 줄<br>둘째 줄</div><span>이어</span><span>붙는다</span></body></html>";

		assertThat(lines(extract(html.getBytes(StandardCharsets.UTF_8)))).containsExactly("첫 줄", "둘째 줄", "이어붙는다");
	}

	@Test
	void 빈_파일은_읽지_못한다() {
		assertThatThrownBy(() -> extract(" \r\n ".getBytes(StandardCharsets.UTF_8)))
			.isInstanceOf(BodyFormatException.class);
	}

	@Test
	void 글이_없는_문서는_빈_글이다() {
		assertThat(extract("<html><head><title>제목뿐</title></head><body></body></html>".getBytes(StandardCharsets.UTF_8)).text())
			.isEmpty();
	}

	private static BodyDocument extract(byte[] content) {
		return BodyTextExtractor.extract(new BodyFile("test.xml", content));
	}

	private static List<String> lines(BodyDocument document) {
		return List.of(document.text().split("\n"));
	}
}
