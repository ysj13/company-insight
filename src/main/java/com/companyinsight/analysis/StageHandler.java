package com.companyinsight.analysis;

/**
 * 한 단계의 실제 일을 하는 쪽. 단계마다 하나를 빈으로 등록하면 워커가 그 단계의 작업을 꺼내 넘겨준다.
 * 등록된 것이 없는 단계의 작업은 꺼내지 않고 대기로 둔다.
 *
 * <p>워커는 작업을 실행 중으로 바꿔 커밋한 뒤 트랜잭션 밖에서 이 메서드를 부른다. 외부 호출이 오래 걸려도
 * DB 트랜잭션을 잡고 있지 않기 위해서다. 그래서 DB에 써야 하는 것은 여기서 직접 쓰지 않고
 * {@link StageResult.Done#writes()}에 담아 돌려준다. 실행 기록, 작업의 상태, 다음 단계 줄과 한 트랜잭션에 들어간다.
 *
 * <p>다음 단계를 정할 때는 공시의 지금 분류(유형, 방향, 철회 여부)를 읽는다. 작업이 대기하는 동안 대응표가 바뀌었거나
 * 공시가 철회됐을 수 있다. 큐에는 "취소"가 없으므로, 더 할 일이 없어진 공시는 여기서 끝난 것으로 답한다.
 *
 * <p>같은 작업이 두 번 실행될 수 있다(끝을 기록하기 전에 앱이 죽은 경우). 두 번 실행해도 결과가 같게 만든다.
 */
public interface StageHandler {

	/** 이 구현이 맡는 단계. */
	AnalysisStage stage();

	/**
	 * 작업 한 건을 처리한다.
	 *
	 * @return 끝난 모양({@link StageResult.Done}) 또는 실패로 세지 않고 미룸({@link StageResult.Deferred})
	 * @throws RuntimeException 실패. 횟수로 세고, {@link AnalysisQueueService#MAX_ATTEMPTS}번이면 작업이 멈춘다
	 */
	StageResult handle(AnalysisJob job);
}
