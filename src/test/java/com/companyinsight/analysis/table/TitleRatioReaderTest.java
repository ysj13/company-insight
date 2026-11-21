package com.companyinsight.analysis.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.companyinsight.analysis.table.TableReader.ReadTarget;
import com.companyinsight.analysis.table.TableReading.Verdict;
import com.companyinsight.disclosure.Tone;
import org.junit.jupiter.api.Test;

/**
 * 제목으로 방향이 정해지는 유형의 크기 읽기. 기대값은 실제 공시의 원문 표에 적힌 값이다({@link TableFixtures}).
 */
class TitleRatioReaderTest {

	private final TitleRatioReader reader = new TitleRatioReader(TableFixtures.impact());

	private static ReadTarget target(String type, String title, Tone tone) {
		return new ReadTarget(type, false, title, tone);
	}

	@Test
	void 공급계약_해지는_매출액_대비로_크기를_정하고_방향은_제목의_것이다() {
		TableReading reading = reader.read(target("C2", "단일판매ㆍ공급계약해지", Tone.NEGATIVE),
			TableFixtures.tables(TableFixtures.SUPPLY_CANCELLED));

		assertThat(reading.verdict()).isEqualTo(Verdict.JUDGED);
		assertThat(reading.tone()).isEqualTo(Tone.NEGATIVE);
		// 12.15%는 10% 이상 30% 미만이라 2
		assertThat(reading.impact()).isEqualTo(2);
		assertThat(reading.ratio()).isEqualByComparingTo("12.15");
		assertThat(reading.items()).containsEntry("해지금액(원)", "1,326,000,000").containsEntry("매출액대비(%)", "12.15");
		assertThat(reading.unread()).isEmpty();
	}

	@Test
	void 공시_유보로_비율이_비면_크기_1이고_읽지_못한_항목에_남긴다() {
		TableReading reading = reader.read(target("C1", "단일판매ㆍ공급계약체결", Tone.POSITIVE),
			TableFixtures.tables(TableFixtures.SUPPLY_WITHHELD));

		assertThat(reading.tone()).isEqualTo(Tone.POSITIVE);
		assertThat(reading.impact()).isEqualTo(1);
		assertThat(reading.ratio()).isNull();
		assertThat(reading.caseCode()).isEqualTo("NO_RATIO");
		assertThat(reading.unread()).containsExactly("매출액대비(%)");
	}

	@Test
	void 공급계약의_비율이_30퍼센트_이상이면_3이다() {
		BodyTables tables = TableFixtures.tablesWith(TableFixtures.SUPPLY_CANCELLED, ">12.15<", ">30.00<", 1);

		assertThat(reader.read(target("C2", "단일판매ㆍ공급계약해지", Tone.NEGATIVE), tables).impact()).isEqualTo(3);
	}

	@Test
	void 사채_발행의_정정_공시는_정정_전_표의_사본이_아니라_본문의_비율을_읽는다() {
		TableReading reading = reader.read(target("D3", "주요사항보고서(전환사채권발행결정)", Tone.NEGATIVE),
			TableFixtures.tables(TableFixtures.BOND_CORRECTION));

		// 9.69%는 주식 수 경계(늘어남) 5% 이상 20% 미만이라 2
		assertThat(reading.ratio()).isEqualByComparingTo("9.69");
		assertThat(reading.impact()).isEqualTo(2);
		assertThat(reading.items()).containsEntry("사채의권면(전자등록)총액(원)", "5,000,000,000").containsEntry("전환가액(원/주)", "685");
	}

	@Test
	void 교환사채는_주식총수_대비_칸이_없어_크기_1이다() {
		TableReading reading = reader.read(target("D3", "주요사항보고서(교환사채권발행결정)", Tone.NEGATIVE),
			TableFixtures.tables(TableFixtures.EXCHANGEABLE_BOND));

		assertThat(reading.impact()).isEqualTo(1);
		assertThat(reading.ratio()).isNull();
		assertThat(reading.unread()).containsExactly("주식총수대비비율(%)");
		assertThat(reading.items()).containsEntry("교환가액(원/주)", "247,000");
	}

	@Test
	void 전환_청구_행사는_발행주식총수_대비로_크기를_정한다() {
		TableReading large = reader.read(target("L2", "전환주식의전환청구권행사", Tone.NEGATIVE),
			TableFixtures.tables(TableFixtures.EXERCISE_LARGE));
		TableReading small = reader.read(target("L2", "신주인수권행사", Tone.NEGATIVE),
			TableFixtures.tables(TableFixtures.EXERCISE_PERCENT_IN_VALUE));

		assertThat(large.ratio()).isEqualByComparingTo("19.3");
		assertThat(large.impact()).isEqualTo(2);
		assertThat(large.items()).containsEntry("발행주식총수(주)", "13,536,399");
		// 항목 이름에 (%)가 없고 값에 %가 붙은 서식
		assertThat(small.ratio()).isEqualByComparingTo("3.14");
		assertThat(small.impact()).isEqualTo(1);
		assertThat(small.items()).containsEntry("발행주식총수대비", "3.14%");
	}

	@Test
	void 전환_청구_행사가_발행주식총수의_1퍼센트_미만이면_중립이다() {
		BodyTables tables = TableFixtures.tablesWith(TableFixtures.EXERCISE_PERCENT_IN_VALUE, ">3.14%<", ">0.99%<", 1);

		TableReading reading = reader.read(target("L2", "신주인수권행사", Tone.NEGATIVE), tables);

		assertThat(reading.verdict()).isEqualTo(Verdict.JUDGED);
		assertThat(reading.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(reading.impact()).isNull();
		assertThat(reading.ratio()).isEqualByComparingTo("0.99");
		assertThat(reading.caseCode()).isEqualTo("SMALL_EXERCISE");
	}

	@Test
	void 자기사채_매도와_시설_투자와_소송_제기의_비율을_읽는다() {
		TableReading sale = reader.read(target("L4", "주요사항보고서(자기전환사채매도결정)", Tone.NEGATIVE),
			TableFixtures.tables(TableFixtures.TREASURY_BOND_SALE));
		TableReading facility = reader.read(target("F8", "신규시설투자등", Tone.POSITIVE), TableFixtures.tables(TableFixtures.FACILITY));
		TableReading lawsuit = reader.read(target("I1", "소송등의제기ㆍ신청(일정금액이상의청구)", Tone.NEGATIVE),
			TableFixtures.tables(TableFixtures.LAWSUIT));

		assertThat(sale.ratio()).isEqualByComparingTo("13.89");
		assertThat(sale.impact()).isEqualTo(2);
		assertThat(facility.ratio()).isEqualByComparingTo("17.45");
		assertThat(facility.impact()).isEqualTo(2);
		assertThat(facility.items()).containsEntry("투자금액(원)", "97,182,000,000").containsEntry("자기자본(원)", "557,074,198,066");
		assertThat(lawsuit.ratio()).isEqualByComparingTo("7.0");
		assertThat(lawsuit.impact()).isEqualTo(1);
		assertThat(lawsuit.items()).containsEntry("청구금액(원)", "7,000,000,000");
	}

	@Test
	void 횡령_배임은_금액이_작아도_크기_2이다() {
		TableReading reading = reader.read(target("I2", "횡령ㆍ배임혐의발생", Tone.NEGATIVE), TableFixtures.tables(TableFixtures.EMBEZZLEMENT));

		// 7.23%는 금액 경계로 1이지만 횡령·배임은 가장 작은 크기가 2
		assertThat(reading.ratio()).isEqualByComparingTo("7.23");
		assertThat(reading.impact()).isEqualTo(2);
		assertThat(reading.caseCode()).isEqualTo("EMBEZZLEMENT");
		assertThat(reading.items()).containsEntry("발생금액(원)", "2,598,159,678");
	}

	@Test
	void 벌금_등의_부과는_금액_경계이고_회계처리기준_위반_조치는_정해_둔_크기다() {
		TableReading penalty = reader.read(target("I2", "벌금등의부과", Tone.NEGATIVE), TableFixtures.tables(TableFixtures.PENALTY));
		TableReading accounting = reader.read(target("I2", "회계처리기준위반행위로인한증권선물위원회의검찰고발등", Tone.NEGATIVE),
			TableFixtures.tables(TableFixtures.ACCOUNTING_VIOLATION));

		assertThat(penalty.ratio()).isEqualByComparingTo("2.57");
		assertThat(penalty.impact()).isEqualTo(1);
		assertThat(accounting.impact()).isEqualTo(2);
		assertThat(accounting.ratio()).isNull();
		assertThat(accounting.caseCode()).isEqualTo("ACCOUNTING_VIOLATION");
		assertThat(accounting.unread()).isEmpty();
	}

	@Test
	void 경계값은_이상_미만이다() {
		TableReading amount10 = reader.read(target("C2", "단일판매ㆍ공급계약해지", Tone.NEGATIVE),
			TableFixtures.tablesWith(TableFixtures.SUPPLY_CANCELLED, ">12.15<", ">10.00<", 1));
		TableReading amountBelow10 = reader.read(target("C2", "단일판매ㆍ공급계약해지", Tone.NEGATIVE),
			TableFixtures.tablesWith(TableFixtures.SUPPLY_CANCELLED, ">12.15<", ">9.99<", 1));
		TableReading exercise1 = reader.read(target("L2", "신주인수권행사", Tone.NEGATIVE),
			TableFixtures.tablesWith(TableFixtures.EXERCISE_PERCENT_IN_VALUE, ">3.14%<", ">1.00%<", 1));
		TableReading share5 = reader.read(target("L2", "신주인수권행사", Tone.NEGATIVE),
			TableFixtures.tablesWith(TableFixtures.EXERCISE_PERCENT_IN_VALUE, ">3.14%<", ">5.00%<", 1));

		assertThat(amount10.impact()).isEqualTo(2);
		assertThat(amountBelow10.impact()).isEqualTo(1);
		// 1%는 하한 이상이라 중립이 아니고 크기 1
		assertThat(exercise1.tone()).isEqualTo(Tone.NEGATIVE);
		assertThat(exercise1.impact()).isEqualTo(1);
		assertThat(share5.impact()).isEqualTo(2);
	}

	@Test
	void 제목이_방향을_정하지_않은_공시는_읽지_않는다() {
		assertThatThrownBy(() -> reader.read(target("C1", "단일판매ㆍ공급계약체결", Tone.NEUTRAL),
			TableFixtures.tables(TableFixtures.SUPPLY_CANCELLED)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> reader.read(new ReadTarget("C1", false), TableFixtures.tables(TableFixtures.SUPPLY_CANCELLED)))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
