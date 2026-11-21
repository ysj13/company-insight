package com.companyinsight.analysis;

/**
 * 실행 한 번의 결과. DB의 {@code analysis_run.outcome}에 이름 그대로 저장한다.
 * 그중 단계를 끝낸 결과 넷은 {@code analysis_job.outcome}에도 남는다.
 */
public enum RunOutcome {

	/** 이 단계에서 끝났다. 다음 단계가 없다 */
	ENDED(true),

	/** 다음 단계로 넘겼다 */
	PASSED(true),

	/** 본문이 없는 공시다. 실패가 아니다 */
	NO_BODY(true),

	/** 판정을 보류했다 (판정할 수 없거나 근거가 본문과 맞지 않는다) */
	HELD(true),

	/** 실패. 횟수로 센다 */
	FAILED(false),

	/** 실패로 세지 않고 미뤘다 (외부 서비스의 일시 과부하, 하루 한도) */
	DEFERRED(false),

	/** 끝을 보지 못했다 (앱이 죽었거나 너무 오래 걸렸다). 실패 한 번으로 센다 */
	LOST(false);

	private final boolean finishesJob;

	RunOutcome(boolean finishesJob) {
		this.finishesJob = finishesJob;
	}

	/** 이 결과로 작업이 끝남({@link JobStatus#DONE})이 되는지. */
	public boolean finishesJob() {
		return finishesJob;
	}
}
