package com.companyinsight.analysis.table;

import static org.assertj.core.api.Assertions.assertThat;

import com.companyinsight.analysis.body.BodyDocument;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 정정 공시의 정정 표 읽기. 기대값은 각 공시의 원문(DART 공시뷰어)에 적힌 값이다.
 */
class CorrectionTableTest {

	@Test
	void 거래소_서식의_정정_표에서_최초_제출일과_사유와_바뀐_항목을_읽는다() {
		CorrectionTable table = read(TableFixtures.PRELIM_CORRECTION);

		// 삼성전자 2026-01-29 [기재정정]: "2. 정정관련 공시서류제출일 | 2026년 1월 8일"
		assertThat(table.correctedFiledOn()).isEqualTo(LocalDate.of(2026, 1, 8));
		assertThat(table.firstFiling()).isFalse();
		assertThat(table.reasons()).containsExactly("2025년 4분기 연결재무제표기준영업(잠정)실적(공정공시) 내용 정정");
		assertThat(table.changes()).isNotEmpty();
		assertThat(table.authorityOrdered()).isFalse();
	}

	@Test
	void 거래소_서식의_날짜가_줄표로_적혀도_읽는다() {
		CorrectionTable table = read(TableFixtures.AUDIT_CORRECTION);

		// "2. 정정관련 공시서류제출일 | 2026-02-12", "3. 정정사유 | 기재사항 추가"
		assertThat(table.correctedFiledOn()).isEqualTo(LocalDate.of(2026, 2, 12));
		assertThat(table.reasons()).containsExactly("기재사항 추가");
	}

	@Test
	void 금융위_서식은_항목마다_붙은_사유와_정정_전후를_읽는다() {
		CorrectionTable table = read(TableFixtures.RIGHTS_CORRECTION);

		// "2. 정정대상 공시서류의 최초제출일 : 2026년 06월 24일"
		assertThat(table.correctedFiledOn()).isEqualTo(LocalDate.of(2026, 6, 24));
		assertThat(table.firstFiling()).isTrue();
		assertThat(table.reasons()).containsExactly("발행 조건 확정에 따른 기재 정정");
		assertThat(table.changes().getFirst())
			.isEqualTo(new CorrectionTable.Change("4. 자금조달의 목적- 시설자금(원)", "43,140,750,000,000", "40,023,070,290,000"));
	}

	@Test
	void 지분_보고의_정정도_같은_모양으로_읽는다() {
		CorrectionTable table = read(TableFixtures.INSIDER_CORRECTION);

		assertThat(table.correctedFiledOn()).isEqualTo(LocalDate.of(2026, 8, 21));
		// 회사가 사유 칸에 항목 설명과 사유를 이어 적었다. 적힌 그대로 읽는다
		assertThat(table.reasons()).containsExactly("2026년 08월 20일취득/처분 단가(원)기재정정");
		assertThat(table.changes()).extracting(CorrectionTable.Change::before, CorrectionTable.Change::after)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("1,056,000", "1,760,000"));
	}

	@Test
	void 정정요구_칸이_비어_있으면_당국_요구가_아니다() {
		CorrectionTable table = read(TableFixtures.BLOCK_GENERAL_CORRECTION);

		// 2022-08-29에 낸 보고서를 2026-10-23에 고쳤다. "정정요구ㆍ명령관련 여부" 칸이 있지만 비어 있다
		assertThat(table.correctedFiledOn()).isEqualTo(LocalDate.of(2022, 8, 29));
		assertThat(table.authorityOrdered()).isFalse();
	}

	@Test
	void 정정_공시가_아니면_비어_있다() {
		List<BodyDocument> documents = TableFixtures.documents(TableFixtures.PRELIM_UP);

		assertThat(CorrectionTable.read(documents, BodyTables.of(documents))).isEmpty();
	}

	@Test
	void 최초_제출일이_표_밖의_문단에_있어도_읽는다() {
		// 금융위 서식 가운데 주식매수선택권 부여 신고 등은 날짜를 표가 아니라 문단에 적는다
		BodyDocument document = xml("""
				<P>1. 정정대상 공시서류 : 주식매수선택권 부여에 관한 신고</P>
				<P>2. 정정대상 공시서류의 최초제출일 : 2024년 03월 29일</P>
				<TABLE><TR><TH>항 목</TH><TH>정정사유</TH><TH>정 정 전</TH><TH>정 정 후</TH></TR>
				<TR><TD>3. 행사 조건- 행사가격(원)</TD><TD>유상증자에 따른 행사가격 조정</TD><TD>5,031</TD><TD>4,851</TD></TR></TABLE>
				""");

		Optional<CorrectionTable> table = CorrectionTable.read(List.of(document), BodyTables.of(List.of(document)));

		assertThat(table).get().extracting(CorrectionTable::correctedFiledOn).isEqualTo(LocalDate.of(2024, 3, 29));
		assertThat(table.get().changes())
			.containsExactly(new CorrectionTable.Change("3. 행사 조건- 행사가격(원)", "5,031", "4,851"));
	}

	@Test
	void 정정요구_칸이_예이면_당국_요구로_적는다() {
		BodyDocument document = xml("""
				<P>2. 정정대상 공시서류의 최초제출일 : 2026년 01월 16일</P>
				<TABLE><TR><TH>항 목</TH><TH>정정요구ㆍ명령관련 여부</TH><TH>정정사유</TH><TH>정 정 전</TH><TH>정 정 후</TH></TR>
				<TR><TD>처분 목적</TD><TD>예</TD><TD>정정명령에 따른 정정</TD><TD>-</TD><TD>운영자금</TD></TR></TABLE>
				""");

		CorrectionTable table = CorrectionTable.read(List.of(document), BodyTables.of(List.of(document))).orElseThrow();

		assertThat(table.authorityOrdered()).isTrue();
		assertThat(table.reasons()).containsExactly("정정명령에 따른 정정");
	}

	@Test
	void 날짜가_아닌_값이면_최초_제출일을_비운다() {
		// 항목 이름 뒤가 날짜 모양이 아니다. 정정 표는 있으므로 바뀐 항목은 읽는다
		BodyDocument document = xml("""
				<TABLE><TR><TD>2. 정정관련 공시서류제출일</TD><TD>-</TD><TD>-</TD></TR>
				<TR><TD>정정항목</TD><TD>정정전</TD><TD>정정후</TD></TR>
				<TR><TD>계약기간 종료일</TD><TD>2026-07-31</TD><TD>2026-04-24</TD></TR></TABLE>
				""");

		CorrectionTable table = CorrectionTable.read(List.of(document), BodyTables.of(List.of(document))).orElseThrow();

		assertThat(table.correctedFiledOn()).isNull();
		assertThat(table.changes()).containsExactly(new CorrectionTable.Change("계약기간 종료일", "2026-07-31", "2026-04-24"));
	}

	@Test
	void 있을_수_없는_날짜는_읽지_않는다() {
		BodyDocument document = xml("<P>2. 정정대상 공시서류의 최초제출일 : 2026년 02월 30일</P>");

		assertThat(CorrectionTable.filedOn(List.of(document))).isEmpty();
	}

	private static CorrectionTable read(String receiptNo) {
		List<BodyDocument> documents = TableFixtures.documents(receiptNo);
		return CorrectionTable.read(documents, BodyTables.of(documents)).orElseThrow();
	}

	/** 금융위 서식 모양의 문서 하나. 글은 태그를 걷어낸 것을 본문 받기와 같은 모양으로 만든다. */
	private static BodyDocument xml(String body) {
		String markup = "<DOCUMENT><BODY>" + body + "</BODY></DOCUMENT>";
		return com.companyinsight.analysis.body.DisclosureBodyReader
			.documents(TestZips.single("20260101000001.xml", markup)).getFirst();
	}
}
