package com.companyinsight.analysis;

/**
 * 분석 작업의 단계. 큐의 한 줄은 공시 한 건의 한 단계다.
 * DB의 {@code analysis_job.stage}, {@code analysis_run.stage}에 이름 그대로 저장한다.
 *
 * <p>공시 목록 수집과 제목 분류는 수집할 때 바로 하므로 큐에 넣지 않는다.
 */
public enum AnalysisStage {

	/** 공시 본문을 출처에서 받아 저장한다 */
	FETCH_BODY,

	/** 저장한 본문의 표에서 정해진 항목을 코드가 읽는다 */
	READ_TABLE,

	/** 본문을 AI가 읽어 방향을 정한다 */
	AI_JUDGE,

	/** 방향이 정해진 공시의 요약을 AI가 쓴다 */
	AI_SUMMARY
}
