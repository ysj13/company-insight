package com.companyinsight.analysis.table;

import static org.assertj.core.api.Assertions.assertThat;

import com.companyinsight.analysis.table.TableReader.ReadTarget;
import com.companyinsight.analysis.table.TableReading.Verdict;
import com.companyinsight.disclosure.Tone;
import org.junit.jupiter.api.Test;

/**
 * 보증·대여·차입의 표 읽기. 기대값은 실제 공시의 원문 표에 적힌 값이다({@link TableFixtures}).
 */
class GuaranteeReaderTest {

	private static final ReadTarget GUARANTEE = new ReadTarget("H1", false, "타인에대한채무보증결정");
	private static final ReadTarget COLLATERAL = new ReadTarget("H1", false, "타인에대한담보제공결정");
	private static final ReadTarget LOAN = new ReadTarget("H2", false, "금전대여결정");
	private static final ReadTarget BORROWING = new ReadTarget("H3", false, "단기차입금증가결정");

	private final GuaranteeReader reader = new GuaranteeReader(TableFixtures.impact());

	@Test
	void 자기자본의_30퍼센트_미만인_채무보증은_중립이다() {
		TableReading reading = reader.read(GUARANTEE, TableFixtures.tables(TableFixtures.GUARANTEE_SMALL));

		assertThat(reading.verdict()).isEqualTo(Verdict.JUDGED);
		assertThat(reading.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(reading.ratio()).isEqualByComparingTo("10.8");
		assertThat(reading.caseCode()).isEqualTo("BELOW_THRESHOLD");
		assertThat(reading.items()).containsEntry("회사와의관계", "종속회사")
			.containsEntry("채무보증금액(원)", "39,000,000,000")
			.containsEntry("자기자본(원)", "359,728,114,713")
			.containsEntry("채무보증총잔액(원)", "497,315,380,000");
	}

	@Test
	void 자기자본의_30퍼센트_이상이면_악재_1이고_100퍼센트_이상이면_악재_2이다() {
		TableReading large = reader.read(GUARANTEE, TableFixtures.tables(TableFixtures.GUARANTEE_LARGE));
		TableReading over = reader.read(COLLATERAL, TableFixtures.tables(TableFixtures.COLLATERAL_OVER_EQUITY));
		TableReading loan = reader.read(LOAN, TableFixtures.tables(TableFixtures.LOAN_LARGE));

		assertThat(large.tone()).isEqualTo(Tone.NEGATIVE);
		assertThat(large.impact()).isEqualTo(1);
		assertThat(large.ratio()).isEqualByComparingTo("64.73");
		assertThat(over.tone()).isEqualTo(Tone.NEGATIVE);
		assertThat(over.impact()).isEqualTo(2);
		assertThat(over.ratio()).isEqualByComparingTo("213.48");
		assertThat(over.items()).containsEntry("담보설정금액(원)", "61,094,000,000");
		assertThat(loan.tone()).isEqualTo(Tone.NEGATIVE);
		assertThat(loan.impact()).isEqualTo(1);
		assertThat(loan.items()).containsEntry("대여상대", "후성글로벌(주)").containsEntry("대여금액(원)", "136,000,000,000").containsEntry("금전대여목적", "운영자금 지원");
	}

	@Test
	void 차입은_자기자본의_50퍼센트부터_악재_1이다() {
		TableReading small = reader.read(BORROWING, TableFixtures.tables(TableFixtures.BORROWING_SMALL));
		TableReading justBelow = reader.read(BORROWING,
			TableFixtures.tablesWith(TableFixtures.BORROWING_SMALL, ">5.31<", ">49.99<", 1));
		TableReading atBoundary = reader.read(BORROWING,
			TableFixtures.tablesWith(TableFixtures.BORROWING_SMALL, ">5.31<", ">50.00<", 1));

		assertThat(small.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(small.items()).containsEntry("차입금액(원)", "100,000,000,000");
		assertThat(justBelow.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(atBoundary.tone()).isEqualTo(Tone.NEGATIVE);
		assertThat(atBoundary.impact()).isEqualTo(1);
	}

	@Test
	void 보증의_경계는_이상_미만이다() {
		TableReading at30 = reader.read(GUARANTEE,
			TableFixtures.tablesWith(TableFixtures.GUARANTEE_SMALL, ">10.8<", ">30.0<", 1));
		TableReading at100 = reader.read(GUARANTEE,
			TableFixtures.tablesWith(TableFixtures.GUARANTEE_SMALL, ">10.8<", ">100.0<", 1));

		assertThat(at30.impact()).isEqualTo(1);
		assertThat(at100.impact()).isEqualTo(2);
	}

	@Test
	void 단기차입금_감소는_비율과_관계없이_중립이다() {
		// 실제 표본에 감소 서식이 없어 증가 서식의 표를 감소 서식의 제목으로 읽는다
		TableReading reading = reader.read(new ReadTarget("H3", false, "단기차입금감소결정(자율공시)"),
			TableFixtures.tablesWith(TableFixtures.BORROWING_SMALL, ">5.31<", ">60.00<", 1));

		assertThat(reading.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(reading.caseCode()).isEqualTo("REPAYMENT");
	}

	@Test
	void 표를_확인하지_않은_서식은_추측하지_않고_AI가_읽는다() {
		TableReading debtAssumption = reader.read(new ReadTarget("H1", false, "채무인수결정"), TableFixtures.tables(TableFixtures.GUARANTEE_LARGE));
		TableReading waiver = reader.read(new ReadTarget("H2", false, "채무면제결정"), TableFixtures.tables(TableFixtures.LOAN_LARGE));

		assertThat(debtAssumption.verdict()).isEqualTo(Verdict.NEEDS_AI);
		assertThat(debtAssumption.caseCode()).isEqualTo("UNCHECKED_FORM");
		assertThat(waiver.verdict()).isEqualTo(Verdict.NEEDS_AI);
	}

	@Test
	void 비율_칸이_비면_방향을_추측하지_않고_읽지_못한_것으로_답한다() {
		TableReading reading = reader.read(GUARANTEE,
			TableFixtures.tablesWith(TableFixtures.GUARANTEE_SMALL, ">10.8<", ">-<", 1));

		assertThat(reading.verdict()).isEqualTo(Verdict.UNREADABLE);
		assertThat(reading.unread()).containsExactly("자기자본대비(%)");
	}
}
