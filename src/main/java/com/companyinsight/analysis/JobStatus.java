package com.companyinsight.analysis;

/** 분석 작업의 상태. DB의 {@code analysis_job.status}에 이름 그대로 저장한다. */
public enum JobStatus {

	/** 대기. 꺼내 갈 수 있다 */
	PENDING,

	/** 실행 중. 꺼낸 쪽이 끝을 기록할 때까지 이 상태다 */
	RUNNING,

	/** 끝남. 어떻게 끝났는지는 {@link RunOutcome} */
	DONE,

	/** 멈춤. 실패가 {@link AnalysisQueueService#MAX_ATTEMPTS}번 쌓였다. 사람이 다시 시도를 시켜야 움직인다 */
	FAILED
}
