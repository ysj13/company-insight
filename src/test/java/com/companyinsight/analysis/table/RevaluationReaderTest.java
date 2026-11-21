package com.companyinsight.analysis.table;

import static org.assertj.core.api.Assertions.assertThat;

import com.companyinsight.analysis.table.TableReader.ReadTarget;
import com.companyinsight.analysis.table.TableReading.Verdict;
import com.companyinsight.disclosure.Tone;
import org.junit.jupiter.api.Test;

/**
 * 자산 재평가 결과의 표 읽기. 기대값은 실제 공시의 원문 표에 적힌 값이다({@link TableFixtures}).
 */
class RevaluationReaderTest {

	private static final ReadTarget TARGET = new ReadTarget("B6", false);

	private final RevaluationReader reader = new RevaluationReader(TableFixtures.impact());

	@Test
	void 재평가로_자산이_늘면_호재다() {
		TableReading reading = reader.read(TARGET, TableFixtures.tables(TableFixtures.REVALUATION_GAIN));

		assertThat(reading.verdict()).isEqualTo(Verdict.JUDGED);
		assertThat(reading.tone()).isEqualTo(Tone.POSITIVE);
		assertThat(reading.impact()).isEqualTo(1);
		assertThat(reading.ratio()).isNull();
		assertThat(reading.caseCode()).isEqualTo("GAIN");
		assertThat(reading.items()).containsEntry("재평가 목적물", "토지")
			.containsEntry("장부가액", "7,334,315,110")
			.containsEntry("재평가금액", "12,123,945,000")
			.containsEntry("재평가차액", "4,789,629,890")
			.containsEntry("재평가차액의 자산총액대비 비율(%)", "14.2");
	}

	@Test
	void 재평가_손실은_정해_둔_규칙이_없어_AI가_읽는다() {
		// 실제 1년치 27건은 모두 자산이 늘었다. 합계 줄의 숫자를 바꿔 손실을 만든다
		BodyTables tables = BodyTables.of(TableFixtures.documentsWithAll(TableFixtures.REVALUATION_GAIN, "12,123,945,000", "5,000,000,000",
			"4,789,629,890", "-2,334,315,110"));

		TableReading reading = reader.read(TARGET, tables);

		assertThat(reading.verdict()).isEqualTo(Verdict.NEEDS_AI);
		assertThat(reading.caseCode()).isEqualTo("LOSS");
	}

	@Test
	void 합계의_세_숫자가_맞지_않으면_읽지_못한_것으로_답한다() {
		BodyTables tables = BodyTables.of(TableFixtures.documentsWithAll(TableFixtures.REVALUATION_GAIN, "4,789,629,890", "4,000,000,000"));

		TableReading reading = reader.read(TARGET, tables);

		assertThat(reading.verdict()).isEqualTo(Verdict.UNREADABLE);
		assertThat(reading.caseCode()).isEqualTo("TOTAL_MISMATCH");
	}
}
