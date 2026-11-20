package com.companyinsight.analysis;

/** 작업이 대기가 된 이유. DB의 {@code analysis_job.enqueue_reason}에 이름 그대로 저장한다. */
public enum EnqueueReason {

	/** 새로 줄 섰다 (새 공시이거나 앞 단계가 넘겼다) */
	NEW,

	/** 멈춘 작업을 사람이 다시 시도시켰다 */
	RETRY,

	/** 대응표나 읽는 코드, 지시문을 고친 뒤 다시 돌린다 */
	RERUN,

	/** AI가 유형을 고른 뒤 그 유형으로 다시 지난다 */
	PICKED_TYPE
}
