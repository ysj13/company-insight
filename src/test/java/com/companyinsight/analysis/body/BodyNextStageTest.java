package com.companyinsight.analysis.body;

import static org.assertj.core.api.Assertions.assertThat;

import com.companyinsight.analysis.AnalysisStage;
import com.companyinsight.analysis.RunOutcome;
import com.companyinsight.analysis.StageResult;
import com.companyinsight.analysis.body.DisclosureBodyRepository.BodyTarget;
import com.companyinsight.common.source.Source;
import com.companyinsight.disclosure.JudgeMethod;
import com.companyinsight.disclosure.Tone;
import org.junit.jupiter.api.Test;

/**
 * 본문을 받은 공시의 다음 단계. 제목과 유형은 테스트 회사 세 곳의 실제 공시에서 가져왔다.
 */
class BodyNextStageTest {

	@Test
	void 제목으로_중립이_정해진_공시는_본문만_저장하고_끝난다() {
		StageResult.Done done = BodyNextStage.decide(target("기업설명회(IR)개최", "A6", JudgeMethod.TITLE, Tone.NEUTRAL));

		assertThat(done.outcome()).isEqualTo(RunOutcome.ENDED);
		assertThat(done.next()).isEmpty();
	}

	@Test
	void 제목으로_호재나_악재가_정해진_공시는_크기와_요약_항목을_읽으러_표_읽기로_간다() {
		StageResult.Done done = BodyNextStage.decide(target("주식소각결정", "E2", JudgeMethod.TITLE, Tone.POSITIVE));

		assertPassed(done, BodyNextStage.TONE_BY_TITLE, AnalysisStage.READ_TABLE);
	}

	@Test
	void 코드가_표를_읽어_방향을_정하는_공시는_표_읽기로_간다() {
		StageResult.Done done = BodyNextStage.decide(
			target("연결재무제표기준영업(잠정)실적(공정공시)", "B1", JudgeMethod.CODE, null));

		assertPassed(done, BodyNextStage.TONE_BY_CODE, AnalysisStage.READ_TABLE);
	}

	@Test
	void AI가_방향을_정하는_공시도_표의_항목을_먼저_읽는다() {
		StageResult.Done done = BodyNextStage.decide(target("주요사항보고서(자기주식처분결정)", "E4", JudgeMethod.AI, null));

		assertPassed(done, BodyNextStage.TONE_BY_AI, AnalysisStage.READ_TABLE);
	}

	@Test
	void 포괄_제목과_꼬리_미상과_미분류는_읽을_표가_정해져_있지_않아_AI_판정으로_간다() {
		assertPassed(BodyNextStage.decide(target("기타경영사항(자율공시)", "K1", JudgeMethod.AI, null)),
			BodyNextStage.NO_FIXED_FORM, AnalysisStage.AI_JUDGE);
		assertPassed(BodyNextStage.decide(target("기타시장안내 (처음 보는 꼬리)", "K2", JudgeMethod.AI, null)),
			BodyNextStage.NO_FIXED_FORM, AnalysisStage.AI_JUDGE);
		assertPassed(BodyNextStage.decide(target("처음보는서식", "Z9", JudgeMethod.AI, null)),
			BodyNextStage.NO_FIXED_FORM, AnalysisStage.AI_JUDGE);
	}

	@Test
	void 본문을_고친_정정_공시는_유형과_방향이_무엇이든_정정_표를_읽으러_표_읽기로_간다() {
		assertPassed(BodyNextStage.decide(target("[기재정정]주주총회소집결의", "A5", JudgeMethod.TITLE, Tone.NEUTRAL)),
			BodyNextStage.CORRECTION, AnalysisStage.READ_TABLE);
		assertPassed(BodyNextStage.decide(target("[기재정정]기타경영사항(자율공시)", "K1", JudgeMethod.AI, null)),
			BodyNextStage.CORRECTION, AnalysisStage.READ_TABLE);
		// 머리말은 겹쳐 온다
		assertPassed(BodyNextStage.decide(target("[정정명령부과][기재정정]증권신고서(지분증권)", "A4", JudgeMethod.TITLE, Tone.NEUTRAL)),
			BodyNextStage.CORRECTION, AnalysisStage.READ_TABLE);
	}

	@Test
	void 첨부만_고치거나_더한_공시는_판정하지_않고_끝난다() {
		for (String prefix : new String[] { "[첨부추가]", "[첨부정정]", "[발행조건확정]", " [첨부추가] " }) {
			StageResult.Done done = BodyNextStage.decide(
				target(prefix + "주요사항보고서(자기주식처분결정)", "E4", JudgeMethod.AI, null));

			assertThat(done.outcome()).as(prefix).isEqualTo(RunOutcome.ENDED);
			assertThat(done.next()).as(prefix).isEmpty();
		}
	}

	@Test
	void 제목_가운데의_대괄호는_머리말이_아니다() {
		// 대규모기업집단현황공시[분기별공시(대표회사용)]처럼 제목 안에 대괄호가 든 서식이 있다
		assertThat(BodyNextStage.prefixes("대규모기업집단현황공시[분기별공시(대표회사용)]")).isEmpty();
		assertThat(BodyNextStage.prefixes("[기재정정]대규모기업집단현황공시[분기별공시(대표회사용)]")).isEqualTo("[기재정정]");
		assertThat(BodyNextStage.prefixes("[정정명령부과] [기재정정] 증권신고서")).isEqualTo("[정정명령부과][기재정정]");
		assertThat(BodyNextStage.prefixes(null)).isEmpty();
	}

	private static void assertPassed(StageResult.Done done, String reason, AnalysisStage next) {
		assertThat(done.outcome()).isEqualTo(RunOutcome.PASSED);
		assertThat(done.passReason()).isEqualTo(reason);
		assertThat(done.next()).extracting(StageResult.Next::stage).containsExactly(next);
	}

	private static BodyTarget target(String reportName, String typeCode, JudgeMethod judgeMethod, Tone tone) {
		return new BodyTarget(Source.DART, "20261119000001", reportName, false, tone, typeCode, true, judgeMethod);
	}
}
