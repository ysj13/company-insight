package com.companyinsight.analysis;

import com.companyinsight.common.external.ExternalApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 큐에서 작업을 꺼내 단계별 처리({@link StageHandler})에 넘기고 끝을 기록한다.
 *
 * <p>한 건씩 꺼낸다. 여러 건을 한꺼번에 집어 두면 뒤에 선 작업이 실행을 기다리는 동안에도 "실행 중"으로 보여,
 * 끝을 보지 못한 실행으로 잘못 되돌려질 수 있다. 꺼낸 시각이 곧 실행을 시작한 시각이 되게 한다.
 */
@Service
public class AnalysisWorker {

	private static final Logger log = LoggerFactory.getLogger(AnalysisWorker.class);

	private final AnalysisJobRepository jobs;
	private final Map<AnalysisStage, StageHandler> handlers = new EnumMap<>(AnalysisStage.class);
	private final AnalysisProperties properties;
	private final Clock clock;

	/**
	 * @throws IllegalStateException 한 단계에 처리 구현이 둘일 때
	 */
	public AnalysisWorker(AnalysisJobRepository jobs, List<StageHandler> handlers, AnalysisProperties properties,
			Clock clock) {
		this.jobs = jobs;
		this.properties = properties;
		this.clock = clock;
		for (StageHandler handler : handlers) {
			if (this.handlers.put(handler.stage(), handler) != null) {
				throw new IllegalStateException("한 단계에 처리 구현이 둘입니다: " + handler.stage());
			}
		}
	}

	/**
	 * 한쪽의 대기 작업을 꺼낼 것이 없을 때까지 처리한다. 처리 구현이 등록되지 않은 단계는 꺼내지 않는다.
	 * 처리 구현이 "같은 쪽을 모두 미룬다"고 답하면 거기서 멈춘다.
	 *
	 * @return 처리한 건수 (끝남, 실패, 미룸을 모두 센다)
	 */
	public int drain(WorkerGroup group) {
		Set<AnalysisStage> stages = EnumSet.noneOf(AnalysisStage.class);
		for (AnalysisStage stage : group.stages()) {
			if (handlers.containsKey(stage)) {
				stages.add(stage);
			}
		}
		int processed = 0;
		while (!stages.isEmpty() && !Thread.currentThread().isInterrupted()) {
			List<AnalysisJob> claimed = jobs.claim(stages, 1, clock.instant());
			if (claimed.isEmpty()) {
				break;
			}
			processed++;
			if (!process(claimed.getFirst(), group)) {
				break;
			}
		}
		return processed;
	}

	/**
	 * 꺼낸 작업 한 건을 처리하고 끝을 기록한다.
	 *
	 * @return 같은 쪽의 다음 작업을 이어서 꺼내도 되면 true
	 */
	private boolean process(AnalysisJob job, WorkerGroup group) {
		StageResult result;
		try {
			result = handlers.get(job.stage()).handle(job);
		}
		catch (StageFailedException e) {
			log.warn("분석 작업 실패: job={}, stage={}, {} {}", job.id(), job.stage(), e.errorKind(), e.getMessage());
			recordFailure(job, e.errorKind(), null, e.getMessage(), e.resultJson(), e.aiUsage());
			return true;
		}
		catch (ExternalApiException e) {
			// 메시지에 소스·분류·코드가 들어 있다. 스택은 필요 없다
			log.warn("분석 작업 실패: job={}, stage={}, {}", job.id(), job.stage(), e.getMessage());
			recordFailure(job, e.kind().name(), e.code(), e.getMessage(), null, null);
			return true;
		}
		catch (RuntimeException e) {
			log.error("분석 작업 실패: job={}, stage={}", job.id(), job.stage(), e);
			recordFailure(job, AnalysisJobRepository.INTERNAL, null, e.toString(), null, null);
			return true;
		}

		try {
			switch (result) {
				case StageResult.Done done -> jobs.complete(job, done, clock.instant());
				case StageResult.Deferred asked -> {
					StageResult.Deferred deferred = asked;
					Instant now = clock.instant();
					if (!deferred.until().isAfter(now)) {
						// 지금이나 과거로 미루면 같은 작업을 곧바로 다시 집어 끝나지 않는다
						deferred = new StageResult.Deferred(now.plus(properties.retryDelay()), deferred.wholeGroup(),
							deferred.kind(), deferred.code(), deferred.message(), deferred.aiUsage());
					}
					jobs.defer(job, deferred, now);
					if (deferred.wholeGroup()) {
						int postponed = jobs.postponePending(group.stages(), deferred.until(), now);
						log.info("분석 작업 미룸: {} 쪽의 대기 작업 {}건을 {} 뒤로 미뤘습니다 ({})", group, postponed, deferred.until(),
							deferred.message());
						return false;
					}
				}
			}
		}
		catch (LockLostException e) {
			log.warn("{}. 이 실행의 결과는 버립니다", e.getMessage());
		}
		catch (RuntimeException e) {
			// 끝을 기록하는 트랜잭션이 되돌려졌다. 작업은 아직 실행 중이므로 실패로 적는다
			log.error("분석 작업의 끝을 기록하지 못했습니다: job={}, stage={}", job.id(), job.stage(), e);
			recordFailure(job, AnalysisJobRepository.INTERNAL, null, e.toString(), null, null);
		}
		return true;
	}

	private void recordFailure(AnalysisJob job, String errorKind, String errorCode, String error, String resultJson,
			AiUsage aiUsage) {
		Instant now = clock.instant();
		try {
			jobs.fail(job, errorKind, errorCode, error, resultJson, aiUsage, now.plus(properties.retryDelay()),
				AnalysisQueueService.MAX_ATTEMPTS, now);
		}
		catch (LockLostException e) {
			log.warn("{}. 이 실행의 결과는 버립니다", e.getMessage());
		}
		catch (RuntimeException e) {
			// 실패조차 적지 못했다. 작업은 실행 중으로 남아 끝을 보지 못한 실행으로 되돌려진다. 다음 작업은 이어서 처리한다
			log.error("분석 작업의 실패를 기록하지 못했습니다: job={}, stage={}", job.id(), job.stage(), e);
		}
	}
}
