package com.companyinsight.analysis;

import java.util.Set;

/**
 * 큐에서 작업을 꺼내는 쪽. 부르는 곳(공시 출처, 코드, AI)마다 속도와 한도가 달라 따로 돈다.
 * 한쪽이 쉬어도(하루 한도, 과부하) 나머지는 그대로 돈다.
 */
public enum WorkerGroup {

	/** 공시 출처에서 본문을 받는다 */
	BODY(Set.of(AnalysisStage.FETCH_BODY)),

	/** 외부를 부르지 않고 코드가 읽는다 */
	CODE(Set.of(AnalysisStage.READ_TABLE)),

	/** AI를 부른다. 판정과 요약이 같은 한도를 나눠 쓰므로 한 줄로 세우고 우선순위 순으로 꺼낸다 */
	AI(Set.of(AnalysisStage.AI_JUDGE, AnalysisStage.AI_SUMMARY));

	private final Set<AnalysisStage> stages;

	WorkerGroup(Set<AnalysisStage> stages) {
		this.stages = stages;
	}

	/** 이 쪽이 꺼내는 단계. */
	public Set<AnalysisStage> stages() {
		return stages;
	}
}
