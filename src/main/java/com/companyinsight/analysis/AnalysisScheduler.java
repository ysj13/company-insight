package com.companyinsight.analysis;

import com.companyinsight.common.schedule.ExclusiveJobRunner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 분석 작업 큐를 도는 일정. 꺼내는 쪽({@link WorkerGroup})마다 따로 돌아 한쪽이 오래 걸려도 나머지를 막지 않는다.
 * 같은 쪽은 겹쳐 돌지 않는다.
 *
 * <p>일정은 고정 간격(fixedRate)으로 건다. 가상 스레드를 켠 스케줄러는 "끝난 뒤 얼마"(fixedDelay) 작업을 스레드 하나에서
 * 차례로 실행해, 한쪽이 대기 작업을 비우는 동안 나머지가 시작하지 못한다. 고정 간격 작업은 실행마다 새 스레드에서 돌고,
 * 앞선 실행이 끝나지 않았으면 {@link ExclusiveJobRunner}가 이번 실행을 건너뛴다.
 */
@Component
public class AnalysisScheduler {

	private static final String GROUP_PREFIX = "analysis-";

	private final AnalysisWorker worker;
	private final AnalysisQueueService queue;
	private final ExclusiveJobRunner jobs;

	public AnalysisScheduler(AnalysisWorker worker, AnalysisQueueService queue, ExclusiveJobRunner jobs) {
		this.worker = worker;
		this.queue = queue;
		this.jobs = jobs;
	}

	/** 1분마다, 본문 받기 작업을 처리한다. */
	@Scheduled(fixedRateString = "${app.analysis.poll-interval:PT1M}", initialDelayString = "${app.analysis.poll-interval:PT1M}")
	public void drainBody() {
		drain(WorkerGroup.BODY);
	}

	/** 1분마다, 본문 표 읽기 작업을 처리한다. */
	@Scheduled(fixedRateString = "${app.analysis.poll-interval:PT1M}", initialDelayString = "${app.analysis.poll-interval:PT1M}")
	public void drainCode() {
		drain(WorkerGroup.CODE);
	}

	/** 1분마다, AI 판정과 AI 요약 작업을 처리한다. */
	@Scheduled(fixedRateString = "${app.analysis.poll-interval:PT1M}", initialDelayString = "${app.analysis.poll-interval:PT1M}")
	public void drainAi() {
		drain(WorkerGroup.AI);
	}

	/** 앱이 뜬 직후, 지난 실행에서 끝을 기록하지 못한 작업을 되돌린다. */
	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		recoverLost();
	}

	/** 5분마다, 끝을 기록하지 못한 채 오래 실행 중인 작업을 되돌린다. */
	@Scheduled(fixedRateString = "${app.analysis.recover-interval:PT5M}", initialDelayString = "${app.analysis.recover-interval:PT5M}")
	public void recoverLost() {
		jobs.run(GROUP_PREFIX + "recover", "분석 작업 되돌리기", queue::recoverLost);
	}

	private void drain(WorkerGroup group) {
		jobs.run(GROUP_PREFIX + group.name(), "분석 작업 처리(" + group + ")", () -> worker.drain(group));
	}
}
