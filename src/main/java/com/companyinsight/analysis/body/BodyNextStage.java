package com.companyinsight.analysis.body;

import com.companyinsight.analysis.AnalysisStage;
import com.companyinsight.analysis.StageResult;
import com.companyinsight.analysis.body.DisclosureBodyRepository.BodyTarget;
import com.companyinsight.disclosure.DisclosureType;
import com.companyinsight.disclosure.JudgeMethod;
import com.companyinsight.disclosure.Tone;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 본문을 받은 공시가 다음에 어느 단계로 가는지 정한다. 공시의 지금 분류(유형, 방향, 판정 방법)와 보고서명의 머리말을 본다.
 *
 * <p>여기서 끝나는 공시는 둘뿐이다: 첨부만 고치거나 더한 공시, 그리고 제목으로 중립이 정해진 공시(본문은 저장만 해 둔다).
 * 나머지는 모두 뒤 단계로 넘긴다. 뒤 단계가 할 일이 없으면 거기서 끝낸다.
 */
final class BodyNextStage {

	/** 넘긴 이유: 본문을 고친 정정 공시. 정정 표(최초 제출일, 정정 사유, 정정 전·후)를 읽는다 */
	static final String CORRECTION = "CORRECTION";

	/** 넘긴 이유: 서식이 정해지지 않은 유형. 읽을 표가 정해져 있지 않아 AI가 먼저 읽는다 */
	static final String NO_FIXED_FORM = "NO_FIXED_FORM";

	/** 넘긴 이유: 본문 표를 코드가 읽어 방향을 정하는 유형 */
	static final String TONE_BY_CODE = "TONE_BY_CODE";

	/** 넘긴 이유: AI가 방향을 정하는 유형. 표의 항목을 먼저 읽어 AI에 함께 준다 */
	static final String TONE_BY_AI = "TONE_BY_AI";

	/** 넘긴 이유: 방향은 제목으로 정해졌고 크기와 요약에 쓸 항목을 표에서 읽는다 */
	static final String TONE_BY_TITLE = "TONE_BY_TITLE";

	/** 보고서명 앞의 머리말. "[기재정정]", "[첨부추가]" 등. 여러 개가 이어 붙을 수 있다 */
	private static final Pattern PREFIXES = Pattern.compile("^\\s*(\\[[^\\]]*\\]\\s*)+");

	private static final Pattern SPACES = Pattern.compile("\\s+");

	/** 본문을 고친 정정의 머리말 */
	private static final String BODY_CORRECTION = "[기재정정]";

	/** 판정하지 않고 기록만 하는 머리말. 본문이 없거나 원래 공시의 본문 그대로다 */
	private static final Set<String> RECORD_ONLY = Set.of("[첨부정정]", "[첨부추가]", "[발행조건확정]");

	/** 서식이 정해지지 않은 유형: 포괄 제목, 꼬리 미상, 미분류 */
	private static final Set<String> NO_FIXED_FORM_TYPES = Set.of("K1", "K2", DisclosureType.UNCLASSIFIED);

	private BodyNextStage() {
	}

	/** 본문을 받은(또는 이미 받아 둔) 공시의 끝난 모양을 정한다. */
	static StageResult.Done decide(BodyTarget target) {
		String prefixes = prefixes(target.reportName());
		if (prefixes.contains(BODY_CORRECTION)) {
			return StageResult.passed(CORRECTION, StageResult.Next.of(AnalysisStage.READ_TABLE));
		}
		if (RECORD_ONLY.stream().anyMatch(prefixes::contains)) {
			return StageResult.ended();
		}
		if (target.judgeMethod() == JudgeMethod.TITLE && target.tone() == Tone.NEUTRAL) {
			return StageResult.ended();
		}
		if (NO_FIXED_FORM_TYPES.contains(target.typeCode())) {
			return StageResult.passed(NO_FIXED_FORM, StageResult.Next.of(AnalysisStage.AI_JUDGE));
		}
		String reason = switch (target.judgeMethod()) {
			case CODE -> TONE_BY_CODE;
			case AI -> TONE_BY_AI;
			case TITLE -> TONE_BY_TITLE;
		};
		return StageResult.passed(reason, StageResult.Next.of(AnalysisStage.READ_TABLE));
	}

	/** 보고서명 앞에 붙은 머리말을 공백 없이 이어 붙인 것. 없으면 빈 글. */
	static String prefixes(String reportName) {
		if (reportName == null) {
			return "";
		}
		Matcher matcher = PREFIXES.matcher(reportName);
		return matcher.find() ? SPACES.matcher(matcher.group()).replaceAll("") : "";
	}
}
