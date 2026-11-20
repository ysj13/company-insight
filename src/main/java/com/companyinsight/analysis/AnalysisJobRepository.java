package com.companyinsight.analysis;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분석 작업 큐(analysis_job)와 실행 기록(analysis_run)의 저장. {@code SKIP LOCKED}와 {@code ON CONFLICT}를 쓰므로 JDBC로 한다.
 *
 * <p>"지금" 시각은 DB의 now()가 아니라 부르는 쪽이 넘긴다. 테스트에서 시각을 정할 수 있고, 한 번의 처리에 쓰는
 * 시각이 하나로 맞는다.
 */
@Repository
public class AnalysisJobRepository {

	/** error 컬럼 길이 */
	private static final int ERROR_MAX = 500;

	/** pass_reason 컬럼 길이 */
	private static final int PASS_REASON_MAX = 40;

	/** error_kind 컬럼 길이 */
	private static final int ERROR_KIND_MAX = 20;

	/** error_code 컬럼 길이 */
	private static final int ERROR_CODE_MAX = 20;

	/** 외부 API 오류가 아닌 우리 코드·DB 오류, 끝을 보지 못한 실행의 분류 이름 */
	public static final String INTERNAL = "INTERNAL";

	/** deferrals 컬럼(smallint)이 넘치지 않게 멈추는 값 */
	private static final int DEFERRALS_MAX = 30000;

	/**
	 * 같은 공시의 같은 단계가 이미 있으면 그 줄을 대기로 되돌린다. 실행 중이던 줄도 되돌린다
	 * (잠금이 풀려 그 실행의 결과는 버려지고, 새 입력으로 다시 실행된다).
	 */
	private static final String UPSERT_PENDING = """
		INSERT INTO analysis_job (disclosure_id, stage, status, priority, run_after, enqueue_reason, created_at, updated_at)
		VALUES (:disclosureId, :stage, 'PENDING', :priority, :now, :reason, :now, :now)
		ON CONFLICT (disclosure_id, stage) DO UPDATE
		   SET status = 'PENDING', outcome = NULL, priority = EXCLUDED.priority, run_after = EXCLUDED.run_after,
		       attempts = 0, deferrals = 0, enqueue_reason = EXCLUDED.enqueue_reason, locked_at = NULL,
		       updated_at = EXCLUDED.updated_at
		""";

	private static final String INSERT_RUN = """
		INSERT INTO analysis_run (job_id, disclosure_id, stage, started_at, finished_at, outcome, enqueue_reason,
		                          pass_reason, error_kind, error_code, error, result)
		VALUES (:jobId, :disclosureId, :stage, :startedAt, :finishedAt, :outcome, :reason,
		        :passReason, :errorKind, :errorCode, :error, CAST(:result AS jsonb))
		RETURNING id
		""";

	private final JdbcClient jdbc;

	public AnalysisJobRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 본문을 받을 공시 가운데 아직 본문 받기 작업이 없는 것을 모두 줄 세운다. 여러 번 불러도 같은 공시는 한 줄이다.
	 *
	 * <p>대상: 유형이 본문을 받는 유형이고(disclosure_type.fetch_body) 철회되지 않은 공시.
	 * 아직 유형이 붙지 않은 공시(재분류 전)는 빠지고, 유형이 붙은 뒤에 다시 부르면 들어온다.
	 *
	 * @param stockCodes 분석 대상 회사의 종목코드. 비어 있으면 모든 회사
	 * @param newSince   접수일이 이 날짜부터면 새 공시의 우선순위, 그 앞이면 과거분의 우선순위를 준다
	 * @return 새로 줄 세운 건수
	 */
	@Transactional
	public int enqueueMissingBodyFetch(Collection<String> stockCodes, LocalDate newSince, Instant now) {
		return jdbc.sql("""
				INSERT INTO analysis_job (disclosure_id, stage, status, priority, run_after, enqueue_reason, created_at, updated_at)
				SELECT d.id, :stage, 'PENDING',
				       CASE WHEN d.rcept_date >= :newSince THEN :newPriority ELSE :backlogPriority END,
				       :now, :reason, :now, :now
				  FROM disclosure d
				  JOIN disclosure_type t ON t.code = d.type_code
				  JOIN company c ON c.id = d.company_id
				 WHERE t.fetch_body
				   AND NOT d.is_withdrawn
				   AND (:allCompanies OR c.stock_code IN (:stockCodes))
				   AND NOT EXISTS (SELECT 1 FROM analysis_job j WHERE j.disclosure_id = d.id AND j.stage = :stage)
				 ORDER BY d.id
				ON CONFLICT (disclosure_id, stage) DO NOTHING
				""")
			.param("stage", AnalysisStage.FETCH_BODY.name())
			.param("newSince", newSince)
			.param("newPriority", AnalysisPriority.NEW)
			.param("backlogPriority", AnalysisPriority.BACKLOG)
			.param("now", at(now))
			.param("reason", EnqueueReason.NEW.name())
			.param("allCompanies", stockCodes.isEmpty())
			// 빈 목록은 IN ()이 되어 문법 오류가 난다. 어느 회사에도 맞지 않는 값을 하나 넣는다
			.param("stockCodes", stockCodes.isEmpty() ? List.of("") : stockCodes)
			.update();
	}

	/**
	 * 다시 분류해 유형·방향·경우가 바뀐 공시 가운데 본문을 이미 받아 둔 것의 표 읽기 작업을 대기로 되돌린다(없으면 넣는다).
	 * 저장된 본문으로 표 읽기부터 다시 지난다. 본문 받기를 기다리는 공시는 본문 받기가 끝날 때 새 분류로 이어지므로
	 * 건드리지 않는다. 출처에 본문이 없던 공시도 읽을 것이 없어 건드리지 않는다.
	 *
	 * <p>본문을 받아 두었는지는 저장된 본문(disclosure_body)으로 본다. 본문 받기가 출처를 부르지 않고 끝난 공시
	 * (그때는 철회됐거나 본문을 받지 않는 유형이었다)는 본문이 없으므로, 본문 받기 작업을 대기로 되돌려 새 분류로 다시 보게 한다.
	 *
	 * <p>마침 본문 받기가 실행 중인 공시는 그 작업을 대기로 되돌린다. 실행 중인 쪽은 옛 분류를 보고 다음 단계를
	 * 정했을 수 있다. 되돌리면 그 실행의 결과는 버려지고 새 분류로 다시 실행된다.
	 *
	 * @param disclosureIds 분류가 바뀐 공시
	 * @return 대기로 되돌리거나 새로 넣은 건수
	 */
	@Transactional
	public int requeueTableReadOfFetched(Collection<Long> disclosureIds, Instant now) {
		if (disclosureIds.isEmpty()) {
			return 0;
		}
		jdbc.sql("""
				UPDATE analysis_job
				   SET status = 'PENDING', locked_at = NULL, run_after = :now, updated_at = :now
				 WHERE disclosure_id IN (:ids) AND stage = :fetchStage AND status = 'RUNNING'
				""")
			.param("now", at(now))
			.param("ids", disclosureIds)
			.param("fetchStage", AnalysisStage.FETCH_BODY.name())
			.update();
		jdbc.sql("""
				UPDATE analysis_job f
				   SET status = 'PENDING', outcome = NULL, priority = :priority, run_after = :now, attempts = 0,
				       deferrals = 0, enqueue_reason = :reason, updated_at = :now
				 WHERE f.disclosure_id IN (:ids) AND f.stage = :fetchStage AND f.status = 'DONE' AND f.outcome = 'ENDED'
				   AND NOT EXISTS (SELECT 1 FROM disclosure_body b WHERE b.disclosure_id = f.disclosure_id)
				""")
			.param("priority", AnalysisPriority.BACKLOG)
			.param("now", at(now))
			.param("reason", EnqueueReason.RERUN.name())
			.param("ids", disclosureIds)
			.param("fetchStage", AnalysisStage.FETCH_BODY.name())
			.update();
		return jdbc.sql("""
				INSERT INTO analysis_job (disclosure_id, stage, status, priority, run_after, enqueue_reason, created_at, updated_at)
				SELECT f.disclosure_id, :stage, 'PENDING', :priority, :now, :reason, :now, :now
				  FROM analysis_job f
				 WHERE f.disclosure_id IN (:ids)
				   AND f.stage = :fetchStage
				   AND f.status = 'DONE'
				   AND EXISTS (SELECT 1 FROM disclosure_body b WHERE b.disclosure_id = f.disclosure_id)
				 ORDER BY f.disclosure_id
				ON CONFLICT (disclosure_id, stage) DO UPDATE
				   SET status = 'PENDING', outcome = NULL, priority = EXCLUDED.priority, run_after = EXCLUDED.run_after,
				       attempts = 0, deferrals = 0, enqueue_reason = EXCLUDED.enqueue_reason, locked_at = NULL,
				       updated_at = EXCLUDED.updated_at
				""")
			.param("stage", AnalysisStage.READ_TABLE.name())
			.param("fetchStage", AnalysisStage.FETCH_BODY.name())
			.param("priority", AnalysisPriority.BACKLOG)
			.param("now", at(now))
			.param("reason", EnqueueReason.RERUN.name())
			.param("ids", disclosureIds)
			.update();
	}

	/**
	 * 한 단계가 "할 수 없어 건너뜀"으로 끝났던 작업 가운데, 지금은 할 수 있게 된 유형의 것을 대기로 되돌린다.
	 * 건너뛴 이유는 마지막 실행 기록의 결과({@code {"skipped": 이유}})에서 읽는다.
	 * 그 뒤에 다시 실행돼 다른 결과로 끝난 작업은 마지막 실행이 달라 건드리지 않는다. 여러 번 불러도 결과가 같다.
	 *
	 * @param stage      단계
	 * @param skipReason 건너뛴 이유 (예: NO_READER 읽는 규칙이 없었다)
	 * @param typeCodes  지금은 처리할 수 있는 유형 번호
	 * @return 대기로 되돌린 건수
	 */
	@Transactional
	public int requeueSkipped(AnalysisStage stage, String skipReason, Collection<String> typeCodes, Instant now) {
		if (typeCodes.isEmpty()) {
			return 0;
		}
		return jdbc.sql("""
				UPDATE analysis_job j
				   SET status = 'PENDING', outcome = NULL, priority = :priority, run_after = :now, attempts = 0,
				       deferrals = 0, enqueue_reason = :reason, updated_at = :now
				  FROM analysis_run r, disclosure d
				 WHERE j.stage = :stage AND j.status = 'DONE' AND j.outcome = 'ENDED'
				   AND r.id = j.last_run_id AND r.result ->> 'skipped' = :skipReason
				   AND d.id = j.disclosure_id AND d.type_code IN (:typeCodes)
				""")
			.param("priority", AnalysisPriority.BACKLOG)
			.param("now", at(now))
			.param("reason", EnqueueReason.RERUN.name())
			.param("stage", stage.name())
			.param("skipReason", skipReason)
			.param("typeCodes", typeCodes)
			.update();
	}

	/**
	 * 공시 한 건의 한 단계를 대기로 만든다. 줄이 없으면 넣고, 있으면 상태가 무엇이든 대기로 되돌린다.
	 * 실패 횟수와 미룬 횟수는 0으로 돌아간다.
	 */
	@Transactional
	public void putPending(long disclosureId, AnalysisStage stage, int priority, EnqueueReason reason, Instant now) {
		jdbc.sql(UPSERT_PENDING)
			.param("disclosureId", disclosureId)
			.param("stage", stage.name())
			.param("priority", priority)
			.param("reason", reason.name())
			.param("now", at(now))
			.update();
	}

	/**
	 * 대기 작업을 꺼내 실행 중으로 바꾼다. 우선순위, 다음 실행 시각, ID 순이다.
	 * 다른 트랜잭션이 집고 있는 줄은 기다리지 않고 건너뛰므로 두 워커가 같은 줄을 집지 않는다.
	 * 이 메서드가 커밋된 뒤, 트랜잭션 밖에서 일한다.
	 *
	 * @param stages 꺼낼 단계. 비어 있으면 아무것도 꺼내지 않는다
	 * @param limit  최대 건수
	 * @param now    꺼낸 시각으로 적을 값. 다음 실행 시각이 이 값 뒤인 줄은 꺼내지 않는다
	 */
	@Transactional
	public List<AnalysisJob> claim(Collection<AnalysisStage> stages, int limit, Instant now) {
		if (stages.isEmpty()) {
			return List.of();
		}
		List<AnalysisJob> claimed = jdbc.sql("""
				UPDATE analysis_job
				   SET status = 'RUNNING', locked_at = :now, updated_at = :now
				 WHERE id IN (SELECT id FROM analysis_job
				               WHERE stage IN (:stages) AND status = 'PENDING' AND run_after <= :now
				               ORDER BY priority, run_after, id
				               LIMIT :limit
				               FOR UPDATE SKIP LOCKED)
				RETURNING id, disclosure_id, stage, priority, attempts, enqueue_reason, locked_at
				""")
			.param("now", at(now))
			.param("stages", stages.stream().map(Enum::name).toList())
			.param("limit", limit)
			.query((rs, n) -> new AnalysisJob(rs.getLong("id"), rs.getLong("disclosure_id"),
				AnalysisStage.valueOf(rs.getString("stage")), rs.getInt("priority"), rs.getInt("attempts"),
				EnqueueReason.valueOf(rs.getString("enqueue_reason")),
				rs.getObject("locked_at", OffsetDateTime.class).toInstant()))
			.list();
		// UPDATE … RETURNING은 순서를 지키지 않는다
		return claimed.stream()
			.sorted(java.util.Comparator.comparingInt(AnalysisJob::priority).thenComparingLong(AnalysisJob::id))
			.toList();
	}

	/**
	 * 단계가 끝난 것을 기록한다. 한 트랜잭션에서 실행 기록을 쌓고, 작업을 끝남으로 고치고,
	 * 단계가 넘긴 DB 쓰기를 실행하고, 다음 단계를 줄 세운다(이미 있는 줄이면 대기로 되돌린다).
	 *
	 * @throws LockLostException 그 사이 잠금이 풀렸을 때. 아무것도 기록되지 않는다
	 */
	@Transactional
	public void complete(AnalysisJob job, StageResult.Done done, Instant now) {
		long runId = insertRun(job, now, done.outcome(), cut(done.passReason(), PASS_REASON_MAX), null, null, null,
			done.resultJson());
		int updated = jdbc.sql("""
				UPDATE analysis_job
				   SET status = 'DONE', outcome = :outcome, locked_at = NULL, last_run_id = :runId, updated_at = :now
				 WHERE id = :id AND status = 'RUNNING' AND locked_at = :lockedAt
				""")
			.param("outcome", done.outcome().name())
			.param("runId", runId)
			.param("now", at(now))
			.param("id", job.id())
			.param("lockedAt", at(job.lockedAt()))
			.update();
		if (updated == 0) {
			throw new LockLostException(job);
		}
		if (done.writes() != null) {
			done.writes().run();
		}
		for (StageResult.Next next : done.next()) {
			EnqueueReason reason = next.reason() != null ? next.reason()
					: job.enqueueReason() == EnqueueReason.RERUN ? EnqueueReason.RERUN : EnqueueReason.NEW;
			putPending(job.disclosureId(), next.stage(), AnalysisPriority.ofNext(job.priority(), next.stage()), reason,
				now);
		}
	}

	/**
	 * 실패를 기록한다. 실패 횟수를 하나 올리고, 한도에 닿으면 멈춤으로, 아니면 다시 대기로 둔다.
	 *
	 * @param errorKind   실패 분류 (외부 API 오류 분류 이름 또는 INTERNAL)
	 * @param errorCode   외부 소스의 원래 코드. 없으면 null
	 * @param error       오류 메시지. 500자까지만 저장한다
	 * @param retryAt     다시 대기로 둘 때의 다음 실행 시각
	 * @param maxAttempts 실패가 이만큼 쌓이면 멈춘다
	 * @throws LockLostException 그 사이 잠금이 풀렸을 때. 아무것도 기록되지 않는다
	 */
	@Transactional
	public void fail(AnalysisJob job, String errorKind, String errorCode, String error, Instant retryAt,
			int maxAttempts, Instant now) {
		countFailure(job, RunOutcome.FAILED, errorKind, errorCode, error, retryAt, maxAttempts, now);
	}

	/**
	 * 실패로 세지 않고 미룬 것을 기록한다. 실패 횟수는 그대로 두고 다음 실행 시각만 뒤로 민다.
	 *
	 * @throws LockLostException 그 사이 잠금이 풀렸을 때. 아무것도 기록되지 않는다
	 */
	@Transactional
	public void defer(AnalysisJob job, StageResult.Deferred deferred, Instant now) {
		long runId = insertRun(job, now, RunOutcome.DEFERRED, null, deferred.kind() == null ? null : deferred.kind().name(),
			deferred.code(), deferred.message(), null);
		int updated = jdbc.sql("""
				UPDATE analysis_job
				   SET status = 'PENDING', deferrals = LEAST(deferrals + 1, :deferralsMax), run_after = :until,
				       locked_at = NULL, last_run_id = :runId, updated_at = :now
				 WHERE id = :id AND status = 'RUNNING' AND locked_at = :lockedAt
				""")
			.param("deferralsMax", DEFERRALS_MAX)
			.param("until", at(deferred.until()))
			.param("runId", runId)
			.param("now", at(now))
			.param("id", job.id())
			.param("lockedAt", at(job.lockedAt()))
			.update();
		if (updated == 0) {
			throw new LockLostException(job);
		}
	}

	/**
	 * 대기 중인 작업의 다음 실행 시각을 모두 뒤로 민다. 이미 그 뒤인 줄은 그대로 둔다.
	 * 실행한 것이 아니므로 실행 기록과 미룬 횟수는 남기지 않는다.
	 *
	 * @return 민 건수
	 */
	@Transactional
	public int postponePending(Collection<AnalysisStage> stages, Instant until, Instant now) {
		if (stages.isEmpty()) {
			return 0;
		}
		return jdbc.sql("""
				UPDATE analysis_job
				   SET run_after = :until, updated_at = :now
				 WHERE stage IN (:stages) AND status = 'PENDING' AND run_after < :until
				""")
			.param("until", at(until))
			.param("now", at(now))
			.param("stages", stages.stream().map(Enum::name).toList())
			.update();
	}

	/**
	 * 끝을 보지 못한 실행을 되돌린다. 실행 중인 채로 꺼낸 시각이 기준 시각보다 앞선 줄을 실패 한 번으로 세고
	 * 다시 대기로 둔다. 실패가 한도에 닿으면 멈춤으로 둔다 (같은 공시가 앱을 계속 죽이는 경우 거기서 멈춘다).
	 *
	 * @param lockedBefore 꺼낸 시각이 이보다 앞선 줄이 대상이다
	 * @param maxAttempts  실패가 이만큼 쌓이면 멈춘다
	 * @return 되돌린 건수
	 */
	@Transactional
	public int recoverLost(Instant lockedBefore, int maxAttempts, Instant now) {
		List<AnalysisJob> lost = jdbc.sql("""
				SELECT id, disclosure_id, stage, priority, attempts, enqueue_reason, locked_at
				  FROM analysis_job
				 WHERE status = 'RUNNING' AND locked_at < :lockedBefore
				 ORDER BY id
				   FOR UPDATE SKIP LOCKED
				""")
			.param("lockedBefore", at(lockedBefore))
			.query((rs, n) -> new AnalysisJob(rs.getLong("id"), rs.getLong("disclosure_id"),
				AnalysisStage.valueOf(rs.getString("stage")), rs.getInt("priority"), rs.getInt("attempts"),
				EnqueueReason.valueOf(rs.getString("enqueue_reason")),
				rs.getObject("locked_at", OffsetDateTime.class).toInstant()))
			.list();
		for (AnalysisJob job : lost) {
			countFailure(job, RunOutcome.LOST, INTERNAL, null, "끝을 기록하지 못한 실행", now, maxAttempts, now);
		}
		return lost.size();
	}

	/**
	 * 멈춘 작업 하나를 다시 대기로 둔다 (사람이 시킨 다시 시도). 실패 횟수는 0으로, 우선순위는 새 공시와 같게 한다.
	 *
	 * @return 되돌렸으면 true. 그 작업이 없거나 멈춘 상태가 아니면 false
	 */
	@Transactional
	public boolean retryFailed(long jobId, Instant now) {
		return jdbc.sql("""
				UPDATE analysis_job
				   SET status = 'PENDING', attempts = 0, deferrals = 0, priority = :priority, run_after = :now,
				       enqueue_reason = :reason, updated_at = :now
				 WHERE id = :id AND status = 'FAILED'
				""")
			.param("priority", AnalysisPriority.NEW)
			.param("reason", EnqueueReason.RETRY.name())
			.param("now", at(now))
			.param("id", jobId)
			.update() == 1;
	}

	/** 실패 한 번을 실행 기록과 작업에 적는다. 잠금이 그대로인 줄만 고친다. */
	private void countFailure(AnalysisJob job, RunOutcome outcome, String errorKind, String errorCode, String error,
			Instant retryAt, int maxAttempts, Instant now) {
		long runId = insertRun(job, now, outcome, null, errorKind, errorCode, error, null);
		int updated = jdbc.sql("""
				UPDATE analysis_job
				   SET status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
				       attempts = attempts + 1, run_after = :retryAt, locked_at = NULL, last_run_id = :runId,
				       updated_at = :now
				 WHERE id = :id AND status = 'RUNNING' AND locked_at = :lockedAt
				""")
			.param("maxAttempts", maxAttempts)
			.param("retryAt", at(retryAt))
			.param("runId", runId)
			.param("now", at(now))
			.param("id", job.id())
			.param("lockedAt", at(job.lockedAt()))
			.update();
		if (updated == 0) {
			throw new LockLostException(job);
		}
	}

	private long insertRun(AnalysisJob job, Instant now, RunOutcome outcome, String passReason, String errorKind,
			String errorCode, String error, String resultJson) {
		return jdbc.sql(INSERT_RUN)
			.param("jobId", job.id())
			.param("disclosureId", job.disclosureId())
			.param("stage", job.stage().name())
			.param("startedAt", at(job.lockedAt()))
			.param("finishedAt", at(now))
			.param("outcome", outcome.name())
			.param("reason", job.enqueueReason().name())
			.param("passReason", passReason)
			.param("errorKind", cut(errorKind, ERROR_KIND_MAX))
			.param("errorCode", cut(errorCode, ERROR_CODE_MAX))
			.param("error", cut(error, ERROR_MAX))
			.param("result", resultJson)
			.query(Long.class)
			.single();
	}

	private static String cut(String text, int max) {
		if (text == null) {
			return null;
		}
		// PostgreSQL은 글자 0x00을 받지 않는다. 오류 메시지에 섞여 오면 기록 자체가 실패한다
		String clean = text.indexOf(' ') < 0 ? text : text.replace(" ", "");
		return clean.substring(0, Math.min(clean.length(), max));
	}

	private static OffsetDateTime at(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
