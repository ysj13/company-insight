package com.companyinsight.analysis.table;

import static org.assertj.core.api.Assertions.assertThat;

import com.companyinsight.analysis.table.TableReader.ReadTarget;
import com.companyinsight.analysis.table.TableReading.Verdict;
import com.companyinsight.disclosure.Tone;
import org.junit.jupiter.api.Test;

/**
 * 전환가액 등 조정의 표 읽기. 기대값은 실제 공시의 원문 표에 적힌 값이다({@link TableFixtures}).
 */
class ConversionPriceReaderTest {

	private static final ReadTarget TARGET = new ReadTarget("L1", false);

	private final ConversionPriceReader reader = new ConversionPriceReader(TableFixtures.impact());

	@Test
	void 시가_하락으로_가액을_낮추면_악재다() {
		TableReading reading = reader.read(TARGET, TableFixtures.tables(TableFixtures.PRICE_MARKET_DOWN));

		assertThat(reading.verdict()).isEqualTo(Verdict.JUDGED);
		assertThat(reading.tone()).isEqualTo(Tone.NEGATIVE);
		assertThat(reading.impact()).isEqualTo(1);
		assertThat(reading.ratio()).isNull();
		assertThat(reading.caseCode()).isEqualTo("MARKET_DOWN");
		assertThat(reading.items()).containsEntry("조정사유", "시가하락에 따른 조정")
			.containsEntry("회차 6: 조정 전 → 후", "2,550 → 2,379")
			.containsEntry("조정가액 적용일", "2026-11-07");
	}

	@Test
	void 사유가_시가_변동이어도_가액이_내려갔으면_악재다() {
		TableReading reading = reader.read(TARGET, TableFixtures.tables(TableFixtures.PRICE_MARKET_CHANGE_DOWN));

		assertThat(reading.tone()).isEqualTo(Tone.NEGATIVE);
		assertThat(reading.items()).containsEntry("회차 10: 조정 전 → 후", "3,535 → 2,988");
	}

	@Test
	void 안내공시_서식도_읽고_시가_하락과_무상증자가_함께_적히면_AI가_읽는다() {
		// 가액이 내려간 몫 가운데 무상증자에 따른 비례 조정과 시가 하락의 몫을 표로 나눌 수 없다
		TableReading reading = reader.read(TARGET, TableFixtures.tables(TableFixtures.PRICE_NOTICE_DOWN));

		assertThat(reading.verdict()).isEqualTo(Verdict.NEEDS_AI);
		assertThat(reading.caseCode()).isEqualTo("MIXED_REASON");
		assertThat(reading.items()).containsEntry("회차 5: 조정 전 → 후", "12,213 → 6,116");
	}

	@Test
	void 시가_상승으로_가액이_오르면_중립이다() {
		TableReading up = reader.read(TARGET, TableFixtures.tables(TableFixtures.PRICE_UP));

		assertThat(up.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(up.caseCode()).isEqualTo("PRICE_UP");
	}

	@Test
	void 가액이_올랐는데_사유가_시가_하락이면_AI가_읽는다() {
		// 두 회차 모두 가액이 올랐다(1,176 → 5,880, 784 → 2,744). 주식 병합이 끼면 가액만으로 하향 조정인지 알 수 없다
		TableReading reading = reader.read(TARGET, TableFixtures.tables(TableFixtures.PRICE_UP_WITH_DOWN_REASON));

		assertThat(reading.verdict()).isEqualTo(Verdict.NEEDS_AI);
		assertThat(reading.caseCode()).isEqualTo("REASON_MISMATCH");
		assertThat(reading.items()).containsEntry("회차 12: 조정 전 → 후", "1,176 → 5,880")
			.containsEntry("회차 13: 조정 전 → 후", "784 → 2,744");
	}

	@Test
	void 저가_유상증자에_따른_비례_조정은_가액이_내려가도_중립이다() {
		TableReading reading = reader.read(TARGET, TableFixtures.tables(TableFixtures.PRICE_ANTI_DILUTION));

		assertThat(reading.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(reading.caseCode()).isEqualTo("PROPORTIONAL");
		assertThat(reading.items()).containsEntry("회차 24: 조정 전 → 후", "1,289 → 1,285");
	}

	@Test
	void 가액은_내려갔는데_사유가_시가_상승이면_AI가_읽는다() {
		TableReading reading = reader.read(TARGET, TableFixtures.tables(TableFixtures.PRICE_DOWN_WITH_UP_REASON));

		assertThat(reading.verdict()).isEqualTo(Verdict.NEEDS_AI);
		assertThat(reading.caseCode()).isEqualTo("REASON_MISMATCH");
	}

	@Test
	void 가액이_그대로면_사유가_비어도_중립이다() {
		TableReading reading = reader.read(TARGET, TableFixtures.tables(TableFixtures.PRICE_BLANK));

		assertThat(reading.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(reading.caseCode()).isEqualTo("NO_CHANGE");
		assertThat(reading.items()).containsEntry("회차 24: 조정 전 → 후", "1,289 → 1,289");
	}

	@Test
	void 가액이_바뀌었는데_사유가_비면_읽지_못한_것으로_답한다() {
		BodyTables tables = TableFixtures.tablesWith(TableFixtures.PRICE_MARKET_DOWN, "시가하락에 따른 조정", "-", 1);

		TableReading reading = reader.read(TARGET, tables);

		assertThat(reading.verdict()).isEqualTo(Verdict.UNREADABLE);
		assertThat(reading.caseCode()).isEqualTo("NO_REASON");
		assertThat(reading.unread()).containsExactly("조정사유");
	}

	@Test
	void 조정_후_가액을_읽지_못하면_읽지_못한_것으로_답한다() {
		BodyTables tables = TableFixtures.tablesWith(TableFixtures.PRICE_MARKET_DOWN, ">2,379<", ">-<", 1);

		TableReading reading = reader.read(TARGET, tables);

		assertThat(reading.verdict()).isEqualTo(Verdict.UNREADABLE);
		assertThat(reading.caseCode()).isEqualTo("NO_PRICES");
		assertThat(reading.unread()).containsExactly("조정 전·후 가액");
	}

	@Test
	void 회차마다_오르내림이_다르면_AI가_읽는다() {
		BodyTables tables = TableFixtures.tablesWith(TableFixtures.PRICE_UP_WITH_DOWN_REASON, ">2,744<", ">700<", 1);

		assertThat(reader.read(TARGET, tables).verdict()).isEqualTo(Verdict.NEEDS_AI);
	}

	@Test
	void 정해_두지_않은_사유로_가액이_내려가면_읽지_못한_것으로_답한다() {
		BodyTables tables = TableFixtures.tablesWith(TableFixtures.PRICE_MARKET_DOWN, "시가하락에 따른 조정", "약정에 따른 조정", 1);

		TableReading reading = reader.read(TARGET, tables);

		assertThat(reading.verdict()).isEqualTo(Verdict.UNREADABLE);
		assertThat(reading.caseCode()).isEqualTo("UNKNOWN_REASON");
	}
}
