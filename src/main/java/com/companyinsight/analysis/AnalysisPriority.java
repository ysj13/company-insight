package com.companyinsight.analysis;

/**
 * 작업의 우선순위 값. 작을수록 먼저 꺼낸다. {@code analysis_job.priority}에 저장한다.
 *
 * <p>새 공시가 과거분과 다시 돌리기보다 먼저이고, AI 판정이 AI 요약보다 먼저다
 * (판정은 회사 판단에 들어가고 요약은 들어가지 않는다).
 */
public final class AnalysisPriority {

	/** 새 공시 */
	public static final int NEW = 100;

	/** 새 공시의 AI 요약 */
	public static final int NEW_SUMMARY = 200;

	/** 과거분과 다시 돌리기 */
	public static final int BACKLOG = 500;

	private AnalysisPriority() {
	}

	/**
	 * 한 단계가 끝나 다음 단계를 줄 세울 때의 우선순위. 과거분·다시 돌리기에서 이어진 줄은 그대로 뒤에 서고,
	 * 그 밖에는 새 공시의 값을 받는다.
	 *
	 * @param currentPriority 방금 끝난 작업의 우선순위
	 * @param next            줄 세울 단계
	 */
	public static int ofNext(int currentPriority, AnalysisStage next) {
		if (currentPriority >= BACKLOG) {
			return BACKLOG;
		}
		return next == AnalysisStage.AI_SUMMARY ? NEW_SUMMARY : NEW;
	}
}
