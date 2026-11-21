package com.companyinsight.analysis;

/**
 * 작업의 끝을 기록하려는데 잠금이 이미 풀려 있다. 너무 오래 걸려 끝을 보지 못한 실행으로 처리됐거나,
 * 실행하는 동안 그 작업이 다시 대기로 되돌려졌다. 이 실행의 결과는 버린다. 작업은 다시 실행된다.
 */
public class LockLostException extends RuntimeException {

	public LockLostException(AnalysisJob job) {
		super("작업의 잠금을 잃었습니다: job=" + job.id() + ", stage=" + job.stage());
	}
}
