package com.companyinsight.ops;

/** 수집 작업 실행 상태. {@code collection_run.status}에 이름 그대로 저장한다. */
public enum RunStatus {

	/** 실행 중. 종료 기록 없이 이 상태로 남아 있으면 실행 도중 앱이 중단된 것이다. */
	RUNNING,

	/** 정상 종료 */
	SUCCESS,

	/** 오류로 종료. 원인은 error_kind, error_code, error에 남는다. */
	FAILED
}
