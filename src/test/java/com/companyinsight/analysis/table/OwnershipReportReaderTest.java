package com.companyinsight.analysis.table;

import static org.assertj.core.api.Assertions.assertThat;

import com.companyinsight.analysis.table.TableReader.ReadTarget;
import com.companyinsight.analysis.table.TableReading.Verdict;
import com.companyinsight.disclosure.Tone;
import org.junit.jupiter.api.Test;

/**
 * 지분 보고의 표 읽기. 기대값은 실제 공시의 원문 표에 적힌 값이다({@link TableFixtures}).
 * 정해 두지 않은 사유처럼 받아 둔 공시에 없는 경우는 실제 공시의 한 칸을 바꿔 만든다.
 */
class OwnershipReportReaderTest {

	private static final ReadTarget INSIDER = new ReadTarget("A2", false, "임원ㆍ주요주주특정증권등소유상황보고서");
	private static final ReadTarget LARGEST = new ReadTarget("A2", false, "최대주주등소유주식변동신고서");
	private static final ReadTarget BLOCK_BRIEF = new ReadTarget("A2", false, "주식등의대량보유상황보고서(약식)");
	private static final ReadTarget BLOCK_GENERAL = new ReadTarget("A2", false, "주식등의대량보유상황보고서(일반)");

	private final OwnershipReportReader reader = new OwnershipReportReader(TableFixtures.impact());

	// ── 임원ㆍ주요주주특정증권등소유상황보고서 ─────────────────────────────

	@Test
	void 장내매수만_있으면_호재이고_크기는_발행주식총수_대비_비율로_정한다() {
		TableReading reading = reader.read(INSIDER, TableFixtures.tables(TableFixtures.INSIDER_BUY));

		assertThat(reading.verdict()).isEqualTo(Verdict.JUDGED);
		assertThat(reading.tone()).isEqualTo(Tone.POSITIVE);
		// (9,514 + 4,986) ÷ 22,184,124 × 100 = 0.065362%. 0.01% 이상 0.1% 미만이라 1
		assertThat(reading.ratio()).isEqualByComparingTo("0.065362");
		assertThat(reading.impact()).isEqualTo(1);
		assertThat(reading.caseCode()).isEqualTo("NET_BUY");
		assertThat(reading.items()).containsEntry("변동 사유", "장내매수(+) 2건")
			.containsEntry("매매 순증감 (주, 계산)", "14500")
			.containsEntry("발행주식총수", "22,184,124")
			.containsEntry("보고구분", "신규")
			.containsEntry("임원", "비등기임원")
			.containsEntry("이번 보고서 특정증권등의 수(주)", "14,500")
			.containsKeys("보고자", "이번 보고서 비율(%)");
		assertThat(reading.unread()).isEmpty();
	}

	@Test
	void 순매수가_발행주식총수의_천분의_일_이상이면_크기_2다() {
		TableReading reading = reader.read(INSIDER, TableFixtures.tables(TableFixtures.INSIDER_BUY_LARGE));

		assertThat(reading.tone()).isEqualTo(Tone.POSITIVE);
		// 63,199 ÷ 22,055,785 × 100
		assertThat(reading.ratio()).isEqualByComparingTo("0.286542");
		assertThat(reading.impact()).isEqualTo(2);
		assertThat(reading.items()).containsEntry("변동 사유", "장내매수(+) 5건");
	}

	@Test
	void 장내매도는_악재다() {
		TableReading reading = reader.read(INSIDER, TableFixtures.tables(TableFixtures.INSIDER_SELL));

		assertThat(reading.tone()).isEqualTo(Tone.NEGATIVE);
		// -(1,215 + 1,285) ÷ 10,957,422 × 100
		assertThat(reading.ratio()).isEqualByComparingTo("-0.022816");
		assertThat(reading.impact()).isEqualTo(1);
		assertThat(reading.caseCode()).isEqualTo("NET_SELL");
	}

	@Test
	void 매매가_아닌_변동만_있으면_중립이다() {
		TableReading reading = reader.read(INSIDER, TableFixtures.tables(TableFixtures.INSIDER_BONUS));

		assertThat(reading.verdict()).isEqualTo(Verdict.JUDGED);
		assertThat(reading.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(reading.caseCode()).isEqualTo("NOT_TRADE");
		assertThat(reading.ratio()).isNull();
		assertThat(reading.items()).containsEntry("변동 사유", "자사주상여금(+) 1건");
	}

	@Test
	void 매수와_매도가_섞이면_순증감으로_정하고_같으면_중립이다() {
		TableReading reading = reader.read(INSIDER, TableFixtures.tables(TableFixtures.INSIDER_BUY_AND_SELL));

		assertThat(reading.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(reading.caseCode()).isEqualTo("NET_ZERO");
		assertThat(reading.items()).containsEntry("변동 사유", "장내매수(+) 1건, 장내매도(-) 1건");
	}

	@Test
	void 아주_작은_매매는_중립이고_정정_공시는_본문의_표를_읽는다() {
		TableReading reading = reader.read(INSIDER, TableFixtures.tables(TableFixtures.INSIDER_CORRECTION));

		assertThat(reading.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(reading.caseCode()).isEqualTo("SMALL_TRADE");
		// (5 - 600) ÷ 730,492,365 × 100
		assertThat(reading.ratio()).isEqualByComparingTo("-0.000081");
		assertThat(reading.items()).containsEntry("매매 순증감 (주, 계산)", "-595");
	}

	@Test
	void 증감을_0으로_적은_매수_줄은_0주로_센다() {
		TableReading reading = reader.read(INSIDER, TableFixtures.tables(TableFixtures.INSIDER_ZERO_TRADE));

		assertThat(reading.verdict()).isEqualTo(Verdict.JUDGED);
		assertThat(reading.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(reading.caseCode()).isEqualTo("NET_ZERO");
		assertThat(reading.items()).containsEntry("변동 사유", "신규선임(+) 1건, 장내매수(+) 1건");
	}

	@Test
	void 매매_줄과_매매가_아닌_줄이_섞이면_매매_줄만_더한다() {
		// 실제 공시의 두 매수 줄 가운데 첫 줄(9,514주)을 자사주 상여금으로 바꾼다. 남는 매수는 4,986주
		BodyTables tables = TableFixtures.tablesWith(TableFixtures.INSIDER_BUY, ">장내매수(+)<", ">자사주상여금(+)<", 1);

		TableReading reading = reader.read(INSIDER, tables);

		assertThat(reading.tone()).isEqualTo(Tone.POSITIVE);
		// 4,986 ÷ 22,184,124 × 100 = 0.0224755…%
		assertThat(reading.ratio()).isEqualByComparingTo("0.022476");
		assertThat(reading.items()).containsEntry("매매 순증감 (주, 계산)", "4986");
	}

	@Test
	void 신주를_받은_변동은_매수로_세지_않는다() {
		BodyTables tables = TableFixtures.tablesWith(TableFixtures.INSIDER_BONUS, ">자사주상여금(+)<", ">유상신주취득(+)<", 1);

		TableReading reading = reader.read(INSIDER, tables);

		assertThat(reading.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(reading.caseCode()).isEqualTo("NOT_TRADE");
	}

	@Test
	void 정해_두지_않은_사유가_있으면_추측하지_않고_읽지_못한_것으로_답한다() {
		BodyTables tables = TableFixtures.tablesWith(TableFixtures.INSIDER_BUY, ">장내매수(+)<", ">알수없는사유(+)<", 1);

		TableReading reading = reader.read(INSIDER, tables);

		assertThat(reading.verdict()).isEqualTo(Verdict.UNREADABLE);
		assertThat(reading.caseCode()).isEqualTo("UNKNOWN_REASON");
		assertThat(reading.unread()).containsExactly("변동 사유: 알수없는사유(+)");
	}

	@Test
	void 매수인데_주식_수가_줄었다고_적혀_있으면_읽지_못한_것으로_답한다() {
		BodyTables tables = TableFixtures.tablesWith(TableFixtures.INSIDER_BUY, ">9,514<", ">-9,514<", 1);

		TableReading reading = reader.read(INSIDER, tables);

		assertThat(reading.verdict()).isEqualTo(Verdict.UNREADABLE);
		assertThat(reading.caseCode()).isEqualTo("TRADE_AMOUNT");
	}

	@Test
	void 발행주식총수를_읽지_못하면_크기를_정할_수_없어_읽지_못한_것으로_답한다() {
		BodyTables tables = TableFixtures.tablesWith(TableFixtures.INSIDER_BUY, ">22,184,124<", ">-<", 1);

		TableReading reading = reader.read(INSIDER, tables);

		assertThat(reading.verdict()).isEqualTo(Verdict.UNREADABLE);
		assertThat(reading.caseCode()).isEqualTo("NO_TOTAL_SHARES");
		assertThat(reading.unread()).containsExactly("발행주식총수");
	}

	// ── 최대주주등소유주식변동신고서 ────────────────────────────────────

	@Test
	void 최대주주_신고서의_장내매도는_악재다() {
		TableReading reading = reader.read(LARGEST, TableFixtures.tables(TableFixtures.LARGEST_SELL));

		assertThat(reading.tone()).isEqualTo(Tone.NEGATIVE);
		// -2,000 ÷ 17,072,000 × 100 (보통주식총수와 종류주식총수를 더한 발행주식총수)
		assertThat(reading.ratio()).isEqualByComparingTo("-0.011715");
		assertThat(reading.impact()).isEqualTo(1);
		assertThat(reading.items()).containsEntry("변동 사유", "장내매도(-) 1건")
			.containsEntry("발행주식총수", "17,072,000")
			.containsEntry("보고자", "보고자B(친인척)")
			.containsEntry("직전 보고서 주식 수(주)", "2,770,220")
			.containsEntry("직전 보고서 비율(%)", "16.23")
			.containsEntry("이번 보고서 주식 수(주)", "2,768,220")
			.containsEntry("이번 보고서 비율(%)", "16.21")
			.containsEntry("증감 주식 수(주)", "-2,000");
	}

	@Test
	void 최대주주등_안에서_주고받은_매매는_순증감이_0이라_중립이다() {
		TableReading reading = reader.read(LARGEST, TableFixtures.tables(TableFixtures.LARGEST_WITHIN_GROUP));

		assertThat(reading.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(reading.caseCode()).isEqualTo("NET_ZERO");
	}

	@Test
	void 사유가_기타뿐인_신고서는_중립이다() {
		TableReading reading = reader.read(LARGEST, TableFixtures.tables(TableFixtures.LARGEST_OTHER));

		assertThat(reading.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(reading.caseCode()).isEqualTo("NOT_TRADE");
		assertThat(reading.items()).containsEntry("변동 사유", "기타(-) 1건");
	}

	// ── 주식등의대량보유상황보고서 ──────────────────────────────────────

	@Test
	void 대량보유_비율이_1퍼센트포인트_이상_늘고_매수_줄이_있으면_호재_2다() {
		TableReading reading = reader.read(BLOCK_BRIEF, TableFixtures.tables(TableFixtures.BLOCK_UP));

		assertThat(reading.tone()).isEqualTo(Tone.POSITIVE);
		// 10.68 - 9.62
		assertThat(reading.ratio()).isEqualByComparingTo("1.06");
		assertThat(reading.impact()).isEqualTo(2);
		assertThat(reading.caseCode()).isEqualTo("RATIO_UP");
		assertThat(reading.items()).containsEntry("직전 보고서 비율(%)", "9.62")
			.containsEntry("이번 보고서 비율(%)", "10.68")
			.containsEntry("보유목적", "단순투자")
			.containsKeys("보고자", "보고구분", "변동 사유");
	}

	@Test
	void 대량보유_비율이_1퍼센트포인트_미만으로_줄면_악재_1이다() {
		TableReading reading = reader.read(BLOCK_BRIEF, TableFixtures.tables(TableFixtures.BLOCK_DOWN));

		assertThat(reading.tone()).isEqualTo(Tone.NEGATIVE);
		// 4.08 - 5.00
		assertThat(reading.ratio()).isEqualByComparingTo("-0.92");
		assertThat(reading.impact()).isEqualTo(1);
	}

	@Test
	void 대량보유_비율이_움직여도_매수_매도_줄이_없으면_중립이다() {
		TableReading reading = reader.read(BLOCK_BRIEF, TableFixtures.tables(TableFixtures.BLOCK_NO_TRADE));

		assertThat(reading.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(reading.caseCode()).isEqualTo("RATIO_MOVED_WITHOUT_TRADE");
		// 3.67 - 8.75
		assertThat(reading.ratio()).isEqualByComparingTo("-5.08");
	}

	@Test
	void 일반_서식에서_발행주식총수가_늘어_비율만_줄었으면_중립이고_보유목적을_읽는다() {
		TableReading reading = reader.read(BLOCK_GENERAL, TableFixtures.tables(TableFixtures.BLOCK_GENERAL_DILUTED));

		assertThat(reading.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(reading.caseCode()).isEqualTo("RATIO_MOVED_WITHOUT_TRADE");
		// 73.73 - 73.92
		assertThat(reading.ratio()).isEqualByComparingTo("-0.19");
		assertThat(reading.items()).containsEntry("보유목적", "경영권 영향")
			.containsEntry("발행회사와의 관계", "최대주주")
			.containsEntry("직전 보고서 주식등의 수(주)", "6,834,490")
			.containsEntry("이번 보고서 주식등의 수(주)", "6,834,490");
	}

	@Test
	void 직전_보고서가_없는_신규_보고는_중립이다() {
		TableReading reading = reader.read(BLOCK_BRIEF, TableFixtures.tables(TableFixtures.BLOCK_NEW));

		assertThat(reading.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(reading.caseCode()).isEqualTo("NEW_REPORT");
		assertThat(reading.items()).containsEntry("이번 보고서 비율(%)", "15.73").doesNotContainKey("직전 보고서 비율(%)");
	}

	@Test
	void 대량보유의_세부변동에_매매_줄이_없고_정해_두지_않은_사유가_있으면_읽지_못한_것으로_답한다() {
		TableReading reading = reader.read(BLOCK_BRIEF, TableFixtures.tables(TableFixtures.BLOCK_UNKNOWN_REASON));

		assertThat(reading.verdict()).isEqualTo(Verdict.UNREADABLE);
		assertThat(reading.caseCode()).isEqualTo("UNKNOWN_REASON");
		assertThat(reading.unread()).containsExactly("변동 사유: 풋옵션권리행사에따른주식처분(-)");
	}

	@Test
	void 비율이_그대로인_대량보유_보고서는_중립이다() {
		TableReading reading = reader.read(BLOCK_GENERAL, TableFixtures.tables(TableFixtures.BLOCK_GENERAL_CORRECTION));

		assertThat(reading.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(reading.caseCode()).isEqualTo("RATIO_UNCHANGED");
		assertThat(reading.ratio()).isEqualByComparingTo("0");
	}

	// ── 서식 ────────────────────────────────────────────────────────

	@Test
	void 제목이_아는_서식이_아니면_읽지_못한_것으로_답한다() {
		TableReading reading = reader.read(new ReadTarget("A2", false, "처음보는지분보고서"), TableFixtures.tables(TableFixtures.INSIDER_BUY));

		assertThat(reading.verdict()).isEqualTo(Verdict.UNREADABLE);
		assertThat(reading.caseCode()).isEqualTo("UNKNOWN_FORM");
	}

	@Test
	void 요약은_코드가_읽은_항목으로_쓴다() {
		assertThat(reader.summaryByCode()).isTrue();
	}
}
