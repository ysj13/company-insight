package com.companyinsight.analysis.table;

import static org.assertj.core.api.Assertions.assertThat;

import com.companyinsight.analysis.table.TableReader.ReadTarget;
import com.companyinsight.disclosure.Tone;
import org.junit.jupiter.api.Test;

/**
 * 주식 소각의 크기 읽기. 기대값은 실제 공시의 원문 표에 적힌 값으로 계산했다({@link TableFixtures}).
 */
class ShareCancellationReaderTest {

	private static final ReadTarget TARGET = new ReadTarget("E1", false, "주식소각결정", Tone.POSITIVE);

	private final ShareCancellationReader reader = new ShareCancellationReader(TableFixtures.impact());

	@Test
	void 소각_주식_수를_발행주식_총수로_나눠_크기를_정한다() {
		TableReading reading = reader.read(TARGET, TableFixtures.tables(TableFixtures.CANCEL_COMMON));

		assertThat(reading.tone()).isEqualTo(Tone.POSITIVE);
		// 100,418 ÷ 8,249,830 × 100 = 1.2172%. 주식 수 경계(줄어듦) 1% 이상 5% 미만이라 2
		assertThat(reading.ratio()).isEqualByComparingTo("1.2172");
		assertThat(reading.impact()).isEqualTo(2);
		assertThat(reading.items()).containsEntry("소각할 주식의 종류와 수: 보통주식 (주)", "100,418")
			.containsEntry("발행주식 총수: 보통주식 (주)", "8,249,830")
			.containsEntry("소각예정금액(원)", "912,468,733")
			.containsEntry("소각할 주식의 취득방법", "기취득 자기주식")
			.containsEntry("소각 예정일", "2026-11-09");
		assertThat(reading.unread()).isEmpty();
	}

	@Test
	void 종류주식을_소각하면_보통주식과_종류주식을_더한_총수로_나눈다() {
		TableReading reading = reader.read(TARGET, TableFixtures.tables(TableFixtures.CANCEL_PREFERRED));

		// 8,437 ÷ (53,328,897 + 14,708,840) × 100 = 0.0124%. 1% 미만이라 1
		assertThat(reading.ratio()).isEqualByComparingTo("0.0124");
		assertThat(reading.impact()).isEqualTo(1);
	}

	@Test
	void 소각_주식이_5퍼센트_이상이면_3이다() {
		BodyTables tables = TableFixtures.tablesWith(TableFixtures.CANCEL_COMMON, ">100,418<", ">500,000<", 1);

		// 500,000 ÷ 8,249,830 × 100 = 6.0607%
		assertThat(reader.read(TARGET, tables).impact()).isEqualTo(3);
	}

	@Test
	void 소각_비율의_경계는_이상_미만이다() {
		// 100,418 ÷ 10,041,800 = 정확히 1%, 100,418 ÷ 2,008,360 = 정확히 5%
		BodyTables at1 = TableFixtures.tablesWith(TableFixtures.CANCEL_COMMON, ">8,249,830<", ">10,041,800<", 1);
		BodyTables at5 = TableFixtures.tablesWith(TableFixtures.CANCEL_COMMON, ">8,249,830<", ">2,008,360<", 1);

		assertThat(reader.read(TARGET, at1).ratio()).isEqualByComparingTo("1.0000");
		assertThat(reader.read(TARGET, at1).impact()).isEqualTo(2);
		assertThat(reader.read(TARGET, at5).ratio()).isEqualByComparingTo("5.0000");
		assertThat(reader.read(TARGET, at5).impact()).isEqualTo(3);
	}

	@Test
	void 발행주식_총수가_비면_크기_1이고_읽지_못한_항목에_남긴다() {
		BodyTables tables = TableFixtures.tablesWith(TableFixtures.CANCEL_COMMON, ">8,249,830<", ">-<", 1);

		TableReading reading = reader.read(TARGET, tables);

		assertThat(reading.impact()).isEqualTo(1);
		assertThat(reading.ratio()).isNull();
		assertThat(reading.unread()).containsExactly("발행주식 총수");
	}
}
