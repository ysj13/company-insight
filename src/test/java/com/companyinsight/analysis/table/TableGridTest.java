package com.companyinsight.analysis.table;

import static org.assertj.core.api.Assertions.assertThat;

import com.companyinsight.analysis.body.BodyDocument;
import com.companyinsight.analysis.body.BodyFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class TableGridTest {

	@Test
	void 여러_칸에_걸친_칸은_걸친_자리마다_같은_글이_들어간다() {
		BodyDocument document = html("""
			<table>
			  <tr><td colspan="2" rowspan="2">구분</td><td>당기실적</td><td colspan="2">전년동기대비</td></tr>
			  <tr><td>(3분기)</td><td>증감율(%)</td><td>흑자적자전환여부</td></tr>
			  <tr><td rowspan="2">영업이익</td><td>당해실적</td><td>100</td><td>25.0</td><td>-</td></tr>
			  <tr><td>누계실적</td><td>300</td><td>10.0</td><td>-</td></tr>
			</table>
			""");

		List<TableGrid> tables = TableGrid.parse(document);

		assertThat(tables).singleElement().satisfies(table -> assertThat(table.rows()).containsExactly(
			List.of("구분", "구분", "당기실적", "전년동기대비", "전년동기대비"),
			List.of("구분", "구분", "(3분기)", "증감율(%)", "흑자적자전환여부"),
			List.of("영업이익", "당해실적", "100", "25.0", "-"),
			List.of("영업이익", "누계실적", "300", "10.0", "-")));
	}

	@Test
	void 칸의_글은_공백을_하나로_줄이고_앞뒤를_뗀다() {
		BodyDocument document = html("<table><tr><td> <span> 증자전\n  발행주식총수&nbsp;(주) </span> </td><td></td></tr></table>");

		assertThat(TableGrid.parse(document).getFirst().rows()).containsExactly(List.of("증자전 발행주식총수 (주)", ""));
	}

	@Test
	void 표_안에_든_표는_따로_읽고_바깥_표의_줄에_넣지_않는다() {
		BodyDocument document = html("""
			<table><tr><td>바깥</td><td><table><tr><td>안</td><td>1</td></tr></table></td></tr></table>
			""");

		List<TableGrid> tables = TableGrid.parse(document);

		assertThat(tables).hasSize(2);
		assertThat(tables.get(0).rows()).hasSize(1);
		assertThat(tables.get(1).rows()).containsExactly(List.of("안", "1"));
	}

	@Test
	void 전용_XML의_TU_TE_칸도_칸으로_읽는다() {
		BodyDocument document = new BodyDocument("a.xml", BodyFormat.XML, """
			<?xml version="1.0" encoding="utf-8"?>
			<DOCUMENT><BODY><TABLE><TBODY>
			<TR><TD COLSPAN="2">5. 증자방식</TD><TU AUNIT="CI_MTH">제3자배정증자</TU></TR>
			<TR><TD ROWSPAN="2">1. 신주의 종류와 수</TD><TD>보통주식 (주)</TD><TE>17,790,000</TE></TR>
			<TR><TD>기타주식 (주)</TD><TE>-</TE></TR>
			</TBODY></TABLE></BODY></DOCUMENT>
			""", "");

		assertThat(TableGrid.parse(document).getFirst().rows()).containsExactly(
			List.of("5. 증자방식", "5. 증자방식", "제3자배정증자"),
			List.of("1. 신주의 종류와 수", "보통주식 (주)", "17,790,000"),
			List.of("1. 신주의 종류와 수", "기타주식 (주)", "-"));
	}

	@Test
	void 실제_잠정실적_표에서_누계실적_줄에도_항목_이름이_내려온다() {
		// SK하이닉스 2026-10-28. 원문: 영업이익 | 당해실적 | 83,333,818 … / 누계실적 | 181,486,709 …
		BodyTables tables = TableFixtures.tables(TableFixtures.PRELIM_UP);

		List<String> cumulative = tables.lastRow(row -> row.size() > 2 && row.get(0).equals("영업이익") && row.get(1).equals("누계실적"))
			.orElseThrow().row();

		assertThat(cumulative.get(2)).isEqualTo("181,486,709");
	}

	@Test
	void 정정_표는_본문의_표에서_뺀다() {
		// 삼성전자 [기재정정] 감사보고서제출. 맨 위에 "정정항목 | 정정전 | 정정후" 표가 있다
		List<TableGrid> all = TableFixtures.documents(TableFixtures.AUDIT_CORRECTION).stream()
			.flatMap(document -> TableGrid.parse(document).stream()).toList();
		BodyTables tables = TableFixtures.tables(TableFixtures.AUDIT_CORRECTION);

		assertThat(all.stream().filter(BodyTables::isCorrectionTable)).hasSize(1);
		assertThat(tables.tables()).hasSize(all.size() - 1);
		assertThat(tables.allRows(row -> row.stream().anyMatch(cell -> Cells.compact(cell).equals("정정전")))).isEmpty();
	}

	private static BodyDocument html(String markup) {
		return new BodyDocument("a.html", BodyFormat.HTML, "<html><body>" + markup + "</body></html>", "");
	}
}
