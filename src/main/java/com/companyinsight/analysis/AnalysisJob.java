package com.companyinsight.analysis;

import java.time.Instant;

/**
 * 큐에서 꺼낸 작업 한 건.
 *
 * @param id            작업 ID
 * @param disclosureId  공시 ID
 * @param stage         단계
 * @param priority      우선순위
 * @param attempts      지금까지 실패로 센 횟수
 * @param enqueueReason 이 작업이 대기가 된 이유
 * @param lockedAt      꺼낸 시각. 끝을 기록할 때 이 값이 그대로인 줄만 고친다
 */
public record AnalysisJob(long id, long disclosureId, AnalysisStage stage, int priority, int attempts,
		EnqueueReason enqueueReason, Instant lockedAt) {
}
