package com.companyinsight.analysis.table;

import static org.assertj.core.api.Assertions.assertThat;

import com.companyinsight.analysis.table.TableReader.ReadTarget;
import com.companyinsight.analysis.table.TableReading.Verdict;
import com.companyinsight.disclosure.Tone;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 실적 공시의 표 읽기. 기대값은 실제 공시의 원문 표에 적힌 값이다({@link TableFixtures}).
 * 세 회사의 1년치에 없는 경우(흑자·적자 전환, 적자 지속, 경계값)는 칸의 값을 직접 넣어 본다.
 */
class EarningsReaderTest {

	private static final ReadTarget PRELIMINARY = new ReadTarget("B1", false);
	private static final ReadTarget STRUCTURE_CHANGE = new ReadTarget("B4", false);

	private final EarningsReader reader = new EarningsReader(TableFixtures.impact());

	// ── 잠정실적: 실제 공시 ─────────────────────────────────────────

	@Test
	void 영업이익이_전년_동기보다_크게_늘면_호재_3이다() {
		TableReading reading = reader.read(PRELIMINARY, TableFixtures.tables(TableFixtures.PRELIM_UP));

		assertJudged(reading, Tone.POSITIVE, 3, "632.1");
		assertThat(reading.items()).containsEntry("영업이익", "83,333,818")
			.containsEntry("영업이익 (견주는 기간)", "11,383,390")
			.containsEntry("영업이익 증감율(%)", "632.1")
			.containsEntry("매출액", "104,615,483")
			.containsEntry("매출액 증감율(%)", "327.9")
			.containsEntry("당기순이익", "39,775,593")
			.containsEntry("당기순이익 증감율(%)", "215.7");
		assertThat(reading.unread()).isEmpty();
	}

	@Test
	void 전기_대비가_아니라_전년_동기_대비를_본다() {
		// 현대자동차 2026-07-23: 전기(1분기) 대비는 +13.4%, 전년 동기 대비는 -20.8%
		TableReading reading = reader.read(PRELIMINARY, TableFixtures.tables(TableFixtures.PRELIM_DOWN_20));

		assertJudged(reading, Tone.NEGATIVE, 1, "-20.8");
		assertThat(reading.items()).containsEntry("영업이익", "2,850,888").containsEntry("영업이익 (견주는 기간)", "3,601,597");
	}

	@Test
	void 영업이익이_30퍼센트_넘게_줄면_악재_2다() {
		TableReading reading = reader.read(PRELIMINARY, TableFixtures.tables(TableFixtures.PRELIM_DOWN_39));

		assertJudged(reading, Tone.NEGATIVE, 2, "-39.9");
	}

	@Test
	void 증감율이_10퍼센트보다_작으면_중립이고_비율은_남긴다() {
		TableReading reading = reader.read(PRELIMINARY, TableFixtures.tables(TableFixtures.PRELIM_SMALL));

		assertThat(reading.verdict()).isEqualTo(Verdict.JUDGED);
		assertThat(reading.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(reading.impact()).isNull();
		assertThat(reading.ratio()).isEqualByComparingTo("5.5");
		assertThat(reading.caseCode()).isEqualTo("SMALL_CHANGE");
	}

	@Test
	void 영업이익_칸이_빈_월별_실적은_중립이다() {
		TableReading reading = reader.read(PRELIMINARY, TableFixtures.tables(TableFixtures.PRELIM_MONTHLY));

		assertThat(reading.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(reading.ratio()).isNull();
		assertThat(reading.caseCode()).isEqualTo("NO_OPERATING_PROFIT");
	}

	@Test
	void 정정_공시는_정정_표가_아니라_고친_본문의_표를_읽는다() {
		// 정정 전 20.00조원, 정정 후 20.07조원. 본문 표에는 20.07이 적혀 있다
		TableReading reading = reader.read(PRELIMINARY, TableFixtures.tables(TableFixtures.PRELIM_CORRECTION));

		assertJudged(reading, Tone.POSITIVE, 3, "209.17");
		assertThat(reading.items()).containsEntry("영업이익", "20.07").containsEntry("영업이익 (견주는 기간)", "6.49");
	}

	// ── 손익구조 변동: 실제 공시 ────────────────────────────────────

	@Test
	void 손익구조_변동은_직전_사업연도와_견준다() {
		TableReading up = reader.read(STRUCTURE_CHANGE, TableFixtures.tables(TableFixtures.CHANGE_UP_101));
		TableReading mid = reader.read(STRUCTURE_CHANGE, TableFixtures.tables(TableFixtures.CHANGE_UP_33));
		TableReading down = reader.read(STRUCTURE_CHANGE, TableFixtures.tables(TableFixtures.CHANGE_DOWN_19));

		assertJudged(up, Tone.POSITIVE, 3, "101.2");
		assertJudged(mid, Tone.POSITIVE, 2, "33.23");
		assertJudged(down, Tone.NEGATIVE, 1, "-19.5");
		assertThat(down.items()).containsEntry("영업이익", "11,467,851,000")
			.containsEntry("영업이익 (견주는 기간)", "14,239,592,000")
			.containsEntry("재무제표의 종류", "연결")
			.containsEntry("변동 주요원인", "북미 관세영향 등에 따른 영업이익, 법인세비용차감전계속사업이익 및 당기순이익 감소");
	}

	@Test
	void 손익구조_변동의_영업이익_칸이_비면_읽지_못한_것이다() {
		// 실제 공시의 영업이익 칸을 "-"로 바꿨다. 잠정실적과 달리 이 서식에서 영업이익이 빌 이유가 없다
		BodyTables tables = TableFixtures.tablesWith(TableFixtures.CHANGE_DOWN_19, ">11,467,851,000<", ">-<", 1);

		TableReading reading = reader.read(STRUCTURE_CHANGE, tables);

		assertThat(reading.verdict()).isEqualTo(Verdict.UNREADABLE);
		assertThat(reading.unread()).contains("영업이익 (이번 기간)");
	}

	@Test
	void 영업이익_줄이_없는_본문은_읽지_못한_것이다() {
		// 감사보고서 제출의 본문에는 손익구조 변동의 머리 줄이 없다
		TableReading reading = reader.read(STRUCTURE_CHANGE, TableFixtures.tables(TableFixtures.RIGHTS_SUBSIDIARY));

		assertThat(reading.verdict()).isEqualTo(Verdict.UNREADABLE);
		assertThat(reading.unread()).containsExactly("영업이익");
	}

	// ── 칸의 값으로 본 규칙 (실제 공시에 없는 경우) ──────────────────────

	@ParameterizedTest(name = "이번 {0}, 견주는 기간 {1}, 증감율 {2} → {3} {4}")
	@CsvSource(delimiter = '|', value = {
		// 두 기간 모두 흑자: 증감율의 절댓값으로. 경계는 "이상 ~ 미만"
		"109.9 | 100  | 9.9    | NEUTRAL  | ",
		"110   | 100  | 10.0   | POSITIVE | 1",
		"129.9 | 100  | 29.9   | POSITIVE | 1",
		"130   | 100  | 30     | POSITIVE | 2",
		"199   | 100  | 99.9   | POSITIVE | 2",
		"200   | 100  | 100.0  | POSITIVE | 3",
		"90.1  | 100  | -9.9   | NEUTRAL  | ",
		"90    | 100  | -10    | NEGATIVE | 1",
		"70    | 100  | -30.0  | NEGATIVE | 2",
		"1     | 100  | -99.0  | NEGATIVE | 2",
		// 흑자 전환, 적자 전환: 증감율과 관계없이 3
		"50    | -20  | -      | POSITIVE | 3",
		"-50   | 20   | -      | NEGATIVE | 3",
		"△50   | 20   | -350.0 | NEGATIVE | 3",
		// 두 기간 모두 적자: 적자 폭이 늘면 악재 1, 줄거나 그대로면 중립
		"-150  | -100 | -      | NEGATIVE | 1",
		"-100  | -100 | -      | NEUTRAL  | ",
		"-11   | -100 | -      | NEUTRAL  | ",
		"(150) | (100)| -50.0  | NEGATIVE | 1",
	})
	void 영업이익_두_기간의_값으로_방향과_크기를_정한다(String current, String previous, String rate, Tone tone, Integer impact) {
		TableReading reading = judge(false, current, previous, rate, "-");

		assertThat(reading.verdict()).isEqualTo(Verdict.JUDGED);
		assertThat(reading.tone()).isEqualTo(tone);
		assertThat(reading.impact()).isEqualTo(impact);
	}

	@Test
	void 증감율_칸이_비면_두_숫자로_계산한다() {
		TableReading reading = judge(false, "150", "100", "-", "-");

		assertJudged(reading, Tone.POSITIVE, 2, "50");
	}

	@Test
	void 흑자적자전환여부_칸은_숫자와_맞는지만_본다() {
		assertThat(judge(false, "50", "-20", "-", "흑자전환").tone()).isEqualTo(Tone.POSITIVE);
		assertThat(judge(false, "-50", "20", "-", "적자전환").tone()).isEqualTo(Tone.NEGATIVE);
		assertThat(judge(false, "-150", "-100", "-", "적자 지속").tone()).isEqualTo(Tone.NEGATIVE);
		// 숫자는 두 기간 모두 흑자인데 칸에는 적자전환이라고 적혀 있다. 어느 칸을 잘못 읽었는지 알 수 없다
		TableReading mismatch = judge(false, "50", "20", "150.0", "적자전환");
		assertThat(mismatch.verdict()).isEqualTo(Verdict.UNREADABLE);
		assertThat(mismatch.caseCode()).isEqualTo("TURN_MISMATCH");
	}

	@Test
	void 증감율의_부호가_두_숫자와_어긋나면_읽지_못한_것이다() {
		TableReading reading = judge(false, "150", "100", "-50.0", "-");

		assertThat(reading.verdict()).isEqualTo(Verdict.UNREADABLE);
		assertThat(reading.caseCode()).isEqualTo("RATE_MISMATCH");
	}

	@Test
	void 견줄_기간의_값이_없거나_0이면_읽지_못한_것이다() {
		assertThat(judge(true, "150", "-", "-", "-").verdict()).isEqualTo(Verdict.UNREADABLE);
		assertThat(judge(true, "150", "0", "-", "-").verdict()).isEqualTo(Verdict.UNREADABLE);
		assertThat(judge(true, "0", "100", "-100.0", "-").verdict()).isEqualTo(Verdict.UNREADABLE);
		assertThat(judge(true, "해당없음", "100", "-", "-").verdict()).isEqualTo(Verdict.UNREADABLE);
	}

	@Test
	void 영업이익_칸이_비었을_때_중립으로_보는_것은_잠정실적뿐이다() {
		assertThat(judge(true, "-", "-", "-", "-").tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(judge(false, "-", "-", "-", "-").verdict()).isEqualTo(Verdict.UNREADABLE);
	}

	private TableReading judge(boolean blankMeansNeutral, String current, String previous, String rate, String turn) {
		return reader.judge(blankMeansNeutral, current, previous, rate, turn, new LinkedHashMap<>(), new ArrayList<>());
	}

	private static void assertJudged(TableReading reading, Tone tone, int impact, String ratio) {
		assertThat(reading.verdict()).isEqualTo(Verdict.JUDGED);
		assertThat(reading.tone()).isEqualTo(tone);
		assertThat(reading.impact()).isEqualTo(impact);
		assertThat(reading.ratio()).isEqualByComparingTo(new BigDecimal(ratio));
	}
}
