package com.companyinsight.analysis.table;

import static org.assertj.core.api.Assertions.assertThat;

import com.companyinsight.analysis.table.TableReader.ReadTarget;
import com.companyinsight.analysis.table.TableReading.Verdict;
import com.companyinsight.disclosure.Tone;
import org.junit.jupiter.api.Test;

/**
 * 유상증자 결정의 표 읽기. 기대값은 실제 공시의 원문 표에 적힌 값이다({@link TableFixtures}).
 * 세 회사의 1년치에 일반공모와 상장사 자신의 주주배정은 없어, 실제 공시의 증자방식 칸을 바꿔 만든다.
 */
class PaidInCapitalReaderTest {

	private static final ReadTarget OWN = new ReadTarget("D1", false);
	private static final ReadTarget SUBSIDIARY = new ReadTarget("D1", true);

	private final PaidInCapitalReader reader = new PaidInCapitalReader(TableFixtures.impact());

	@Test
	void 제3자배정은_방향을_정하지_않고_비율과_항목만_읽는다() {
		TableReading reading = reader.read(OWN, TableFixtures.tables(TableFixtures.RIGHTS_THIRD_PARTY));

		assertThat(reading.verdict()).isEqualTo(Verdict.NEEDS_AI);
		assertThat(reading.tone()).isNull();
		// 17,790,000 ÷ 712,702,365 × 100
		assertThat(reading.ratio()).isEqualByComparingTo("2.4961");
		assertThat(reading.items()).containsEntry("증자방식", "제3자배정증자")
			.containsEntry("신주의 종류와 수: 보통주식 (주)", "17,790,000")
			.containsEntry("증자전 발행주식총수: 보통주식 (주)", "712,702,365")
			.containsEntry("자금조달의 목적: 시설자금 (원)", "45,453,450,000,000")
			.containsEntry("납입일", "2026.07.14")
			.containsEntry("신주의 상장 예정일", "2026.07.29");
		assertThat(reading.unread()).isEmpty();
	}

	@Test
	void 정정_공시는_정정_전_표의_사본이_아니라_본문의_표를_읽는다() {
		TableReading reading = reader.read(OWN, TableFixtures.tables(TableFixtures.RIGHTS_CORRECTION));

		assertThat(reading.verdict()).isEqualTo(Verdict.NEEDS_AI);
		assertThat(reading.ratio()).isEqualByComparingTo("2.4961");
		assertThat(reading.items()).containsEntry("증자방식", "제3자배정증자");
	}

	@Test
	void 일반공모는_악재이고_크기는_신주_비율로_정한다() {
		// 실제 제3자배정 공시의 증자방식 칸(본문에 두 번째로 나오는 것은 정관 인용문이라 첫 번째만 바꾼다)
		BodyTables tables = TableFixtures.tablesWith(TableFixtures.RIGHTS_THIRD_PARTY, ">제3자배정증자<", ">일반공모증자<", 1);

		TableReading reading = reader.read(OWN, tables);

		assertThat(reading.verdict()).isEqualTo(Verdict.JUDGED);
		assertThat(reading.tone()).isEqualTo(Tone.NEGATIVE);
		// 2.4961%는 5% 미만이라 1
		assertThat(reading.impact()).isEqualTo(1);
		assertThat(reading.ratio()).isEqualByComparingTo("2.4961");
	}

	@Test
	void 주주배정은_악재이고_신주_비율이_20퍼센트_이상이면_3이다() {
		// 종속회사의 공시를 상장사 자신의 공시로 읽어 본다: 10,000,000,000 ÷ 1,693,653,240 = 590.4396%
		TableReading reading = reader.read(OWN, TableFixtures.tables(TableFixtures.RIGHTS_SUBSIDIARY));

		assertThat(reading.tone()).isEqualTo(Tone.NEGATIVE);
		assertThat(reading.impact()).isEqualTo(3);
		assertThat(reading.ratio()).isEqualByComparingTo("590.4396");
		assertThat(reading.items()).containsEntry("증자방식", "주주배정증자")
			.containsEntry("신주의 종류와 수: 보통주식(주)", "10,000,000,000")
			.containsEntry("증자전 발행주식총수: 보통주식(주)", "1,693,653,240");
	}

	@Test
	void 자회사의_유상증자는_증자방식과_관계없이_AI가_읽고_비율을_남기지_않는다() {
		TableReading reading = reader.read(SUBSIDIARY, TableFixtures.tables(TableFixtures.RIGHTS_SUBSIDIARY));

		assertThat(reading.verdict()).isEqualTo(Verdict.NEEDS_AI);
		assertThat(reading.caseCode()).isEqualTo("SUBSIDIARY");
		assertThat(reading.ratio()).isNull();
		assertThat(reading.items()).containsEntry("증자방식", "주주배정증자");
	}

	@Test
	void 처음_보는_증자방식은_읽지_못한_것으로_답한다() {
		BodyTables tables = TableFixtures.tablesWith(TableFixtures.RIGHTS_THIRD_PARTY, ">제3자배정증자<", ">기타<", 1);

		TableReading reading = reader.read(OWN, tables);

		assertThat(reading.verdict()).isEqualTo(Verdict.UNREADABLE);
		assertThat(reading.unread()).containsExactly("증자방식");
	}

	@Test
	void 증자방식_줄이_없는_본문은_읽지_못한_것이다() {
		TableReading reading = reader.read(OWN, TableFixtures.tables(TableFixtures.AUDIT_CLEAN));

		assertThat(reading.verdict()).isEqualTo(Verdict.UNREADABLE);
		assertThat(reading.unread()).contains("증자방식", "신주의 종류와 수", "증자전 발행주식총수");
	}
}
