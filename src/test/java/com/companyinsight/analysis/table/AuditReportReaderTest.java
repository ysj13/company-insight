package com.companyinsight.analysis.table;

import static org.assertj.core.api.Assertions.assertThat;

import com.companyinsight.analysis.table.TableReader.ReadTarget;
import com.companyinsight.analysis.table.TableReading.Verdict;
import com.companyinsight.disclosure.Tone;
import org.junit.jupiter.api.Test;

/**
 * 감사보고서 제출의 표 읽기. 세 회사의 실제 공시는 모두 적정이라, 적정이 아닌 경우는 실제 공시의 칸 하나를 바꿔 만든다.
 * 칸의 순서(원문): 감사의견 = 연결 당해, 연결 직전, 별도 당해, 별도 직전.
 * 미해당 = 연결 계속기업(당해, 직전), 연결 내부회계(당해, 직전), 별도 계속기업(당해, 직전), 별도 내부회계(당해, 직전).
 */
class AuditReportReaderTest {

	private static final ReadTarget TARGET = new ReadTarget("B5", false);

	private final AuditReportReader reader = new AuditReportReader(TableFixtures.impact());

	@Test
	void 연결과_별도가_모두_적정이고_해당_사항이_없으면_중립이다() {
		TableReading reading = reader.read(TARGET, TableFixtures.tables(TableFixtures.AUDIT_CLEAN));

		assertThat(reading.verdict()).isEqualTo(Verdict.JUDGED);
		assertThat(reading.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(reading.impact()).isNull();
		assertThat(reading.items()).containsEntry("감사의견", "적정 / 적정")
			.containsEntry("계속기업 존속불확실성 사유 해당여부", "미해당 / 미해당")
			.containsEntry("감사의견과 관련 없는 계속기업 존속 불확실성 기재여부", "미기재 / 미기재")
			.containsEntry("내부회계관리제도 감사(검토)의견 비적정 등 여부", "미해당 / 미해당");
		assertThat(reading.unread()).isEmpty();
	}

	@Test
	void 정정_공시도_고친_본문의_표를_읽는다() {
		TableReading reading = reader.read(TARGET, TableFixtures.tables(TableFixtures.AUDIT_CORRECTION));

		assertThat(reading.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(reading.items()).containsEntry("감사의견", "적정 / 적정");
	}

	@Test
	void 실제_공시에서_적정_의견이어도_계속기업_불확실성이_기재돼_있으면_악재_2다() {
		// 코람코더원리츠. 별도 재무제표만 있고 내부회계관리제도 칸은 "-"다
		TableReading reading = reader.read(TARGET, TableFixtures.tables(TableFixtures.AUDIT_GOING_CONCERN_NOTED));

		assertThat(reading.tone()).isEqualTo(Tone.NEGATIVE);
		assertThat(reading.impact()).isEqualTo(2);
		assertThat(reading.caseCode()).isEqualTo("GOING_CONCERN");
		assertThat(reading.items()).containsEntry("감사의견", "적정")
			.containsEntry("계속기업 존속불확실성 사유 해당여부", "미해당")
			.containsEntry("감사의견과 관련 없는 계속기업 존속 불확실성 기재여부", "기재")
			.doesNotContainKey("내부회계관리제도 감사(검토)의견 비적정 등 여부");
	}

	@Test
	void 실제_정정_공시에서_정정_전의_의견거절이_아니라_고친_본문의_적정을_읽는다() {
		// 제일엠앤에스. 정정 표에 "감사의견 | 의견거절 | 적정"이 있다. 본문은 적정이고 계속기업 기재, 별도 내부회계 비적정이다
		TableReading reading = reader.read(TARGET, TableFixtures.tables(TableFixtures.AUDIT_CORRECTED_TO_CLEAN));

		assertThat(reading.tone()).isEqualTo(Tone.NEGATIVE);
		assertThat(reading.impact()).isEqualTo(2);
		assertThat(reading.caseCode()).isEqualTo("GOING_CONCERN+INTERNAL_CONTROL");
		assertThat(reading.items()).containsEntry("감사의견", "적정 / 적정")
			.containsEntry("감사의견과 관련 없는 계속기업 존속 불확실성 기재여부", "기재 / 기재")
			.containsEntry("내부회계관리제도 감사(검토)의견 비적정 등 여부", "해당");
	}

	@Test
	void 연결_감사의견이_적정이_아니면_악재_3이다() {
		for (String opinion : new String[] { "한정", "부적정", "의견거절" }) {
			TableReading reading = reader.read(TARGET,
				TableFixtures.tablesWith(TableFixtures.AUDIT_CLEAN, ">적정<", ">" + opinion + "<", 1));

			assertThat(reading.tone()).as(opinion).isEqualTo(Tone.NEGATIVE);
			assertThat(reading.impact()).isEqualTo(3);
			assertThat(reading.caseCode()).isEqualTo("OPINION_NOT_CLEAN");
		}
	}

	@Test
	void 별도_감사의견만_적정이_아니어도_악재다() {
		TableReading reading = reader.read(TARGET, TableFixtures.tablesWith(TableFixtures.AUDIT_CLEAN, ">적정<", ">한정<", 3));

		assertThat(reading.tone()).isEqualTo(Tone.NEGATIVE);
		assertThat(reading.items()).containsEntry("감사의견", "적정 / 한정");
	}

	@Test
	void 직전_사업연도의_의견은_보지_않는다() {
		// 연결 직전 사업연도 칸만 한정으로 바꿨다
		TableReading reading = reader.read(TARGET, TableFixtures.tablesWith(TableFixtures.AUDIT_CLEAN, ">적정<", ">한정<", 2));

		assertThat(reading.tone()).isEqualTo(Tone.NEUTRAL);
	}

	@Test
	void 계속기업_존속_불확실성에_해당하면_악재_2다() {
		TableReading byReason = reader.read(TARGET, TableFixtures.tablesWith(TableFixtures.AUDIT_CLEAN, ">미해당<", ">해당<", 1));
		TableReading byNote = reader.read(TARGET, TableFixtures.tablesWith(TableFixtures.AUDIT_CLEAN, ">미기재<", ">기재<", 1));

		assertThat(byReason.tone()).isEqualTo(Tone.NEGATIVE);
		assertThat(byReason.impact()).isEqualTo(2);
		assertThat(byReason.caseCode()).isEqualTo("GOING_CONCERN");
		assertThat(byNote.tone()).isEqualTo(Tone.NEGATIVE);
		assertThat(byNote.impact()).isEqualTo(2);
		assertThat(byNote.caseCode()).isEqualTo("GOING_CONCERN");
	}

	@Test
	void 내부회계관리제도_의견이_비적정이면_악재_2다() {
		// 세 번째 "미해당"이 연결 내부회계관리제도의 당해 사업연도 칸이다
		TableReading reading = reader.read(TARGET, TableFixtures.tablesWith(TableFixtures.AUDIT_CLEAN, ">미해당<", ">해당<", 3));

		assertThat(reading.tone()).isEqualTo(Tone.NEGATIVE);
		assertThat(reading.impact()).isEqualTo(2);
		assertThat(reading.caseCode()).isEqualTo("INTERNAL_CONTROL");
		assertThat(reading.items()).containsEntry("내부회계관리제도 감사(검토)의견 비적정 등 여부", "해당 / 미해당");
	}

	@Test
	void 정해진_낱말이_아닌_값은_추측하지_않고_읽지_못한_것으로_답한다() {
		TableReading reading = reader.read(TARGET,
			TableFixtures.tablesWith(TableFixtures.AUDIT_CLEAN, ">적정<", ">적정(강조사항 있음)<", 1));

		assertThat(reading.verdict()).isEqualTo(Verdict.UNREADABLE);
		assertThat(reading.unread()).containsExactly("감사의견");
		assertThat(reading.items()).containsEntry("감사의견", "적정(강조사항 있음) / 적정");
	}

	@Test
	void 감사의견_줄이_없는_본문은_읽지_못한_것이다() {
		TableReading reading = reader.read(TARGET, TableFixtures.tables(TableFixtures.CHANGE_UP_101));

		assertThat(reading.verdict()).isEqualTo(Verdict.UNREADABLE);
		assertThat(reading.unread()).containsExactly("감사의견");
	}
}
