package com.companyinsight.analysis;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분석 작업 큐에 줄을 세우고 되돌리는 일. 꺼내서 처리하는 일은 {@link AnalysisWorker}가 한다.
 *
 * <p>브로커 없이 DB 테이블 하나를 큐로 쓴다. 공시의 판정과 작업의 상태가 같은 DB에 있어
 * "판정을 고치고 다음 단계를 줄 세우는 일"이 한 트랜잭션에 들어간다.
 */
@Service
public class AnalysisQueueService {

	private static final Logger log = LoggerFactory.getLogger(AnalysisQueueService.class);

	/** 실패가 이만큼 쌓이면 작업이 멈춘다. */
	public static final int MAX_ATTEMPTS = 3;

	private final AnalysisJobRepository jobs;
	private final AnalysisProperties properties;
	private final Clock clock;

	public AnalysisQueueService(AnalysisJobRepository jobs, AnalysisProperties properties, Clock clock) {
		this.jobs = jobs;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * 본문을 받을 공시 가운데 아직 줄 서지 않은 것을 본문 받기 단계에 줄 세운다. 공시를 수집하거나 다시 분류한 뒤에 부른다.
	 * 여러 번 불러도 같은 공시는 한 번만 줄 선다.
	 *
	 * <p>분석 대상 회사({@code app.analysis.target-stock-codes})를 정해 두었으면 그 회사의 공시만 줄 세운다.
	 * 접수일이 최근 며칠({@code app.analysis.new-days}) 안인 공시는 새 공시로, 그보다 오래된 공시는 과거분으로 줄 세워
	 * 과거분이 쌓여 있어도 새 공시가 먼저 처리된다.
	 *
	 * @return 새로 줄 세운 건수
	 */
	public int enqueueMissing() {
		LocalDate newSince = LocalDate.now(clock).minusDays(properties.newDays());
		int enqueued = jobs.enqueueMissingBodyFetch(properties.targetStockCodes(), newSince, clock.instant());
		if (enqueued > 0) {
			log.info("분석 대기열: 본문 받기 {}건을 줄 세웠습니다 (분석 대상 회사: {})", enqueued,
				properties.targetStockCodes().isEmpty() ? "전체" : properties.targetStockCodes());
		}
		return enqueued;
	}

	/**
	 * 다시 분류해 유형·방향·경우가 바뀐 공시를 뒤 단계로 다시 보낸다. 본문을 이미 받아 둔 공시는 저장된 본문으로
	 * 표 읽기부터 다시 지난다. 분류를 고치는 트랜잭션 안에서 부르면 둘이 함께 반영된다.
	 *
	 * @param disclosureIds 분류가 바뀐 공시
	 * @return 표 읽기를 대기로 되돌리거나 새로 넣은 건수
	 */
	@Transactional
	public int requeueReclassified(Collection<Long> disclosureIds) {
		return jobs.requeueTableReadOfFetched(disclosureIds, clock.instant());
	}

	/**
	 * 멈춘 작업 하나를 다시 시도시킨다.
	 *
	 * @return 되돌렸으면 true. 그 작업이 없거나 멈춘 상태가 아니면 false
	 */
	public boolean retry(long jobId) {
		return jobs.retryFailed(jobId, clock.instant());
	}

	/**
	 * 공시들의 한 단계를 다시 돌린다 (읽는 코드나 지시문을 고친 뒤). 줄이 없으면 넣는다.
	 * 과거분과 같은 우선순위라 새 공시보다 뒤에 처리된다.
	 */
	@Transactional
	public void rerun(Collection<Long> disclosureIds, AnalysisStage stage) {
		Instant now = clock.instant();
		for (long disclosureId : disclosureIds) {
			jobs.putPending(disclosureId, stage, AnalysisPriority.BACKLOG, EnqueueReason.RERUN, now);
		}
	}

	/**
	 * 한 단계가 "할 수 없어 건너뜀"으로 끝났던 작업 가운데, 지금은 할 수 있게 된 유형의 것을 다시 돌린다
	 * (그 유형을 처리하는 코드를 더한 뒤). 과거분과 같은 우선순위라 새 공시보다 뒤에 처리된다. 여러 번 불러도 결과가 같다.
	 *
	 * @param skipReason 건너뛴 이유. 그 단계가 실행 기록의 결과에 남긴 값
	 * @param typeCodes  지금은 처리할 수 있는 유형 번호
	 * @return 대기로 되돌린 건수
	 */
	public int requeueSkipped(AnalysisStage stage, String skipReason, Collection<String> typeCodes) {
		int requeued = jobs.requeueSkipped(stage, skipReason, typeCodes, clock.instant());
		if (requeued > 0) {
			log.info("분석 대기열: 건너뛰었던 {} 작업 {}건을 다시 줄 세웠습니다 (이유: {})", stage, requeued, skipReason);
		}
		return requeued;
	}

	/**
	 * 제목이 방향을 정했는데 크기가 비어 있는 공시의 표 읽기 작업 가운데, 지금은 크기를 읽을 수 있는 유형의 것을 대기로 되돌린다.
	 *
	 * @param typeCodes 지금은 크기를 읽을 수 있는 유형 번호
	 * @return 대기로 되돌린 건수
	 */
	public int requeueUnsized(Collection<String> typeCodes) {
		int requeued = jobs.requeueUnsized(typeCodes, clock.instant());
		if (requeued > 0) {
			log.info("분석 대기열: 크기 없이 지나간 표 읽기 작업 {}건을 다시 줄 세웠습니다", requeued);
		}
		return requeued;
	}

	/**
	 * 끝을 보지 못한 실행을 되돌린다. 앱이 죽으면 꺼낸 작업이 실행 중으로 남으므로,
	 * 앱이 뜰 때와 몇 분마다 부른다. 되돌린 실행은 실패 한 번으로 센다.
	 *
	 * @return 되돌린 건수
	 */
	public int recoverLost() {
		Instant now = clock.instant();
		int recovered = jobs.recoverLost(now.minus(properties.staleAfter()), MAX_ATTEMPTS, now);
		if (recovered > 0) {
			log.warn("분석 대기열: 끝을 기록하지 못한 실행 {}건을 되돌렸습니다", recovered);
		}
		return recovered;
	}
}
