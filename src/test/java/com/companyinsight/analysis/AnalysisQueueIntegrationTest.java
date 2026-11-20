package com.companyinsight.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.companyinsight.TestcontainersConfiguration;
import com.companyinsight.common.dart.DisclosureListResponse.Item;
import com.companyinsight.common.external.ErrorKind;
import com.companyinsight.common.external.ExternalApiException;
import com.companyinsight.disclosure.DisclosureClassificationService;
import com.companyinsight.disclosure.DisclosureRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 분석 작업 큐를 실제 PostgreSQL에서 확인한다. 꺼내기({@code SKIP LOCKED}), 끝 기록, 실패와 미룸, 끝을 보지 못한 실행,
 * 줄 세우는 대상.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AnalysisQueueIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	/** 2026-11-20 10:00 (한국 시간) */
	private static final Instant NOW = Instant.parse("2026-11-20T01:00:00Z");

	@Autowired
	AnalysisJobRepository jobs;

	@Autowired
	DisclosureRepository disclosures;

	@Autowired
	DisclosureClassificationService classification;

	@Autowired
	JdbcClient jdbc;

	private final MovableClock clock = new MovableClock(NOW);

	@BeforeEach
	void clean() {
		jdbc.sql("DELETE FROM analysis_run").update();
		jdbc.sql("DELETE FROM analysis_job").update();
		jdbc.sql("DELETE FROM disclosure").update();
		jdbc.sql("DELETE FROM company").update();
	}

	// ── 줄 세우는 대상 ──────────────────────────────────────────────

	@Test
	void 본문을_받는_유형의_공시만_본문_받기에_줄_선다() {
		saveSamsung(
			item("20261119000001", "주요사항보고서(자기주식처분결정)", "20261119", ""), // E4, AI가 방향을 정한다
			item("20261119000002", "임원ㆍ주요주주특정증권등소유상황보고서", "20261119", ""), // A2, 본문 표를 코드가 읽는다
			item("20261119000003", "기업설명회(IR)개최", "20261119", ""), // A6, 제목으로 중립. 본문은 받아 둔다
			item("20261119000004", "분기보고서 (2026.09)", "20261119", ""), // A1, 본문을 받지 않는다
			item("20261119000005", "증권신고서(지분증권)", "20261119", "")); // A4, 본문을 받지 않는다

		int enqueued = queue().enqueueMissing();

		assertThat(enqueued).isEqualTo(3);
		assertThat(jdbc.sql("""
			SELECT d.type_code || ' ' || j.stage || ' ' || j.status || ' ' || j.enqueue_reason
			  FROM analysis_job j JOIN disclosure d ON d.id = j.disclosure_id
			 ORDER BY d.source_key
			""").query(String.class).list())
			.containsExactly("E4 FETCH_BODY PENDING NEW", "A2 FETCH_BODY PENDING NEW", "A6 FETCH_BODY PENDING NEW");
	}

	@Test
	void 여러_번_줄_세워도_같은_공시는_한_줄이다() {
		saveSamsung(item("20261119000001", "주요사항보고서(자기주식처분결정)", "20261119", ""));

		assertThat(queue().enqueueMissing()).isEqualTo(1);
		assertThat(queue().enqueueMissing()).isZero();

		assertThat(count("SELECT count(*) FROM analysis_job")).isEqualTo(1);
	}

	@Test
	void 이미_끝난_작업이_있는_공시를_다시_줄_세우지_않는다() {
		saveSamsung(item("20261119000001", "주요사항보고서(자기주식처분결정)", "20261119", ""));
		queue().enqueueMissing();
		AnalysisJob job = claimOne(AnalysisStage.FETCH_BODY);
		jobs.complete(job, StageResult.ended(), clock.instant());

		assertThat(queue().enqueueMissing()).isZero();

		assertThat(jdbc.sql("SELECT status FROM analysis_job").query(String.class).single()).isEqualTo("DONE");
	}

	@Test
	void 철회된_공시는_줄_세우지_않는다() {
		saveSamsung(item("20261119000001", "주요사항보고서(자기주식처분결정)", "20261119", "철"));

		assertThat(queue().enqueueMissing()).isZero();
	}

	@Test
	void 분석_대상_회사를_정하면_그_회사의_공시만_줄_선다() {
		saveSamsung(item("20261119000001", "주요사항보고서(자기주식처분결정)", "20261119", ""));
		disclosures.saveListed(List.of(new Item("00164779", "SK하이닉스", "000660", "Y", "주요사항보고서(자기주식처분결정)",
			"20261119000002", "SK하이닉스", "20261119", "")));

		int enqueued = queue(" 005930 ").enqueueMissing();

		assertThat(enqueued).isEqualTo(1);
		assertThat(jdbc.sql("""
			SELECT c.stock_code FROM analysis_job j
			  JOIN disclosure d ON d.id = j.disclosure_id JOIN company c ON c.id = d.company_id
			""").query(String.class).list()).containsExactly("005930");
		// 대상을 비우면 나머지 회사도 줄 선다
		assertThat(queue().enqueueMissing()).isEqualTo(1);
	}

	@Test
	void 접수일이_최근_며칠_안인_공시가_과거분보다_먼저_꺼내진다() {
		// 오늘은 2026-11-20, 새 공시는 최근 3일(11-17부터)
		saveSamsung(
			item("20251201000001", "주요사항보고서(자기주식처분결정)", "20251201", ""),
			item("20261116000001", "주요사항보고서(자기주식처분결정)", "20261116", ""),
			item("20261117000001", "주요사항보고서(자기주식처분결정)", "20261117", ""),
			item("20261120000001", "주요사항보고서(자기주식처분결정)", "20261120", ""));

		queue().enqueueMissing();

		assertThat(jdbc.sql("""
			SELECT d.source_key || ' ' || j.priority FROM analysis_job j JOIN disclosure d ON d.id = j.disclosure_id
			 ORDER BY d.source_key
			""").query(String.class).list())
			.containsExactly("20251201000001 500", "20261116000001 500", "20261117000001 100", "20261120000001 100");
		List<AnalysisJob> claimed = jobs.claim(Set.of(AnalysisStage.FETCH_BODY), 2, clock.instant());
		assertThat(claimed).extracting(AnalysisJob::priority).containsExactly(100, 100);
	}

	// ── 꺼내기 ──────────────────────────────────────────────────────

	@Test
	void 워커_둘이_같은_작업을_집지_않는다() throws Exception {
		int total = 300;
		for (int i = 0; i < total; i++) {
			jobs.putPending(newDisclosure(), AnalysisStage.FETCH_BODY, AnalysisPriority.NEW, EnqueueReason.NEW, NOW);
		}
		List<Long> claimedIds = Collections.synchronizedList(new ArrayList<>());
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
			for (int w = 0; w < 4; w++) {
				int batch = w + 1; // 워커마다 한 번에 집는 건수를 달리해 서로 엇갈리게 한다
				pool.submit(() -> {
					start.await();
					while (true) {
						List<AnalysisJob> claimed = jobs.claim(Set.of(AnalysisStage.FETCH_BODY), batch, NOW);
						if (claimed.isEmpty()) {
							return null;
						}
						claimed.forEach(job -> claimedIds.add(job.id()));
					}
				});
			}
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(claimedIds).hasSize(total);
		assertThat(new HashSet<>(claimedIds)).hasSize(total);
		assertThat(count("SELECT count(*) FROM analysis_job WHERE status = 'RUNNING' AND locked_at IS NOT NULL"))
			.isEqualTo(total);
	}

	@Test
	void 우선순위_다음_실행_시각_ID_순으로_꺼내고_아직_때가_아닌_작업은_꺼내지_않는다() {
		long backlog = newDisclosure();
		long later = newDisclosure();
		long summary = newDisclosure();
		long fresh = newDisclosure();
		jobs.putPending(backlog, AnalysisStage.AI_JUDGE, AnalysisPriority.BACKLOG, EnqueueReason.RERUN, NOW.minusSeconds(3600));
		jobs.putPending(later, AnalysisStage.AI_JUDGE, AnalysisPriority.NEW, EnqueueReason.NEW, NOW.plusSeconds(60));
		jobs.putPending(summary, AnalysisStage.AI_SUMMARY, AnalysisPriority.NEW_SUMMARY, EnqueueReason.NEW, NOW.minusSeconds(60));
		jobs.putPending(fresh, AnalysisStage.AI_JUDGE, AnalysisPriority.NEW, EnqueueReason.NEW, NOW);

		// AI 판정과 AI 요약을 한 줄로 세워 우선순위 순으로 꺼낸다. 새 공시의 판정 → 새 공시의 요약 → 다시 돌리기
		List<AnalysisJob> claimed = jobs.claim(WorkerGroup.AI.stages(), 10, NOW);

		assertThat(claimed).extracting(AnalysisJob::disclosureId).containsExactly(fresh, summary, backlog);
		// 1분 뒤에는 미뤄 둔 작업도 나온다
		assertThat(jobs.claim(WorkerGroup.AI.stages(), 10, NOW.plusSeconds(60))).extracting(AnalysisJob::disclosureId)
			.containsExactly(later);
	}

	@Test
	void 다른_쪽의_단계는_꺼내지_않는다() {
		jobs.putPending(newDisclosure(), AnalysisStage.AI_JUDGE, AnalysisPriority.NEW, EnqueueReason.NEW, NOW);

		assertThat(jobs.claim(WorkerGroup.BODY.stages(), 10, NOW)).isEmpty();
		assertThat(jobs.claim(WorkerGroup.CODE.stages(), 10, NOW)).isEmpty();
		assertThat(jobs.claim(WorkerGroup.AI.stages(), 10, NOW)).hasSize(1);
	}

	@Test
	void 처리_구현이_없는_단계의_작업은_꺼내지_않고_대기로_둔다() {
		jobs.putPending(newDisclosure(), AnalysisStage.FETCH_BODY, AnalysisPriority.NEW, EnqueueReason.NEW, NOW);

		int processed = worker().drain(WorkerGroup.BODY);

		assertThat(processed).isZero();
		assertThat(jdbc.sql("SELECT status FROM analysis_job").query(String.class).single()).isEqualTo("PENDING");
	}

	// ── 끝 기록과 단계 잇기 ─────────────────────────────────────────

	@Test
	void 끝나면_실행_기록을_쌓고_다음_단계를_줄_세운다() {
		long disclosure = newDisclosure();
		jobs.putPending(disclosure, AnalysisStage.FETCH_BODY, AnalysisPriority.NEW, EnqueueReason.NEW, NOW);
		AnalysisWorker worker = worker(handler(AnalysisStage.FETCH_BODY, job -> {
			clock.advance(Duration.ofSeconds(2));
			return StageResult.passed("TONE_BY_AI", StageResult.Next.of(AnalysisStage.READ_TABLE))
				.withResult("{\"bytes\": 5200, \"files\": 1}");
		}));

		assertThat(worker.drain(WorkerGroup.BODY)).isEqualTo(1);

		Map<String, Object> fetch = job(disclosure, AnalysisStage.FETCH_BODY);
		assertThat(fetch).containsEntry("status", "DONE").containsEntry("outcome", "PASSED").containsEntry("locked_at", null);
		Map<String, Object> run = jdbc.sql("SELECT * FROM analysis_run").query().singleRow();
		assertThat(run).containsEntry("id", fetch.get("last_run_id"))
			.containsEntry("outcome", "PASSED")
			.containsEntry("pass_reason", "TONE_BY_AI")
			.containsEntry("stage", "FETCH_BODY")
			.containsEntry("enqueue_reason", "NEW");
		assertThat(jdbc.sql("SELECT result ->> 'bytes' FROM analysis_run").query(String.class).single()).isEqualTo("5200");
		assertThat(jdbc.sql("SELECT EXTRACT(EPOCH FROM finished_at - started_at)::int FROM analysis_run")
			.query(Integer.class).single()).isEqualTo(2);
		assertThat(job(disclosure, AnalysisStage.READ_TABLE)).containsEntry("status", "PENDING")
			.containsEntry("priority", 100)
			.containsEntry("enqueue_reason", "NEW");
	}

	@Test
	void 과거분에서_이어진_단계는_과거분의_우선순위로_새_공시의_요약은_판정보다_뒤로_줄_선다() {
		long past = newDisclosure();
		long fresh = newDisclosure();
		jobs.putPending(past, AnalysisStage.READ_TABLE, AnalysisPriority.BACKLOG, EnqueueReason.RERUN, NOW);
		jobs.putPending(fresh, AnalysisStage.READ_TABLE, AnalysisPriority.NEW, EnqueueReason.NEW, NOW);
		AnalysisWorker worker = worker(handler(AnalysisStage.READ_TABLE, job -> StageResult.passed("NEEDS_AI",
			StageResult.Next.of(AnalysisStage.AI_JUDGE), StageResult.Next.of(AnalysisStage.AI_SUMMARY))));

		worker.drain(WorkerGroup.CODE);

		assertThat(job(fresh, AnalysisStage.AI_JUDGE)).containsEntry("priority", 100).containsEntry("enqueue_reason", "NEW");
		assertThat(job(fresh, AnalysisStage.AI_SUMMARY)).containsEntry("priority", 200);
		assertThat(job(past, AnalysisStage.AI_JUDGE)).containsEntry("priority", 500).containsEntry("enqueue_reason", "RERUN");
		assertThat(job(past, AnalysisStage.AI_SUMMARY)).containsEntry("priority", 500);
	}

	@Test
	void 다음_단계의_줄이_이미_끝나_있으면_대기로_되돌린다() {
		// AI가 유형을 고른 뒤 표 읽기를 거쳐 AI 판정을 한 번 더 지나는 길
		long disclosure = newDisclosure();
		jobs.putPending(disclosure, AnalysisStage.AI_JUDGE, AnalysisPriority.NEW, EnqueueReason.NEW, NOW);
		AnalysisWorker worker = worker(
			handler(AnalysisStage.AI_JUDGE, job -> job.enqueueReason() == EnqueueReason.PICKED_TYPE ? StageResult.ended()
					: StageResult.passed("PICKED_TYPE", new StageResult.Next(AnalysisStage.READ_TABLE, EnqueueReason.PICKED_TYPE))),
			handler(AnalysisStage.READ_TABLE, job -> StageResult.passed("TONE_BY_AI",
				new StageResult.Next(AnalysisStage.AI_JUDGE, EnqueueReason.PICKED_TYPE))));

		worker.drain(WorkerGroup.AI);
		worker.drain(WorkerGroup.CODE);
		assertThat(job(disclosure, AnalysisStage.AI_JUDGE)).containsEntry("status", "PENDING")
			.containsEntry("outcome", null)
			.containsEntry("enqueue_reason", "PICKED_TYPE");
		worker.drain(WorkerGroup.AI);

		assertThat(job(disclosure, AnalysisStage.AI_JUDGE)).containsEntry("status", "DONE").containsEntry("outcome", "ENDED");
		assertThat(count("SELECT count(*) FROM analysis_job")).isEqualTo(2);
		// 실행 기록은 실행마다 남는다. AI 판정 두 번, 표 읽기 한 번
		assertThat(jdbc.sql("SELECT stage || ' ' || outcome FROM analysis_run ORDER BY id").query(String.class).list())
			.containsExactly("AI_JUDGE PASSED", "READ_TABLE PASSED", "AI_JUDGE ENDED");
	}

	@Test
	void 단계가_넘긴_DB_쓰기는_끝_기록과_한_트랜잭션이다() {
		long ok = newDisclosure();
		jobs.putPending(ok, AnalysisStage.AI_JUDGE, AnalysisPriority.NEW, EnqueueReason.NEW, NOW);
		worker(handler(AnalysisStage.AI_JUDGE, job -> StageResult.ended().withWrites(() -> jdbc
			.sql("UPDATE disclosure SET tone = 'NEGATIVE', classified_by = 'LLM' WHERE id = :id")
			.param("id", job.disclosureId())
			.update()))).drain(WorkerGroup.AI);

		assertThat(jdbc.sql("SELECT tone FROM disclosure WHERE id = :id").param("id", ok).query(String.class).single())
			.isEqualTo("NEGATIVE");
		assertThat(job(ok, AnalysisStage.AI_JUDGE)).containsEntry("status", "DONE");

		// 쓰기가 실패하면 끝 기록도, 다음 단계 줄도 남지 않고 실패 한 번으로 센다
		long broken = newDisclosure();
		jobs.putPending(broken, AnalysisStage.AI_JUDGE, AnalysisPriority.NEW, EnqueueReason.NEW, NOW);
		worker(handler(AnalysisStage.AI_JUDGE, job -> StageResult
			.passed("NEEDS_SUMMARY", StageResult.Next.of(AnalysisStage.AI_SUMMARY))
			.withWrites(() -> jdbc.sql("UPDATE disclosure SET tone = 'WRONG' WHERE id = :id")
				.param("id", job.disclosureId())
				.update()))).drain(WorkerGroup.AI);

		assertThat(job(broken, AnalysisStage.AI_JUDGE)).containsEntry("status", "PENDING").containsEntry("attempts", 1);
		assertThat(count("SELECT count(*) FROM analysis_job WHERE stage = 'AI_SUMMARY'")).isZero();
		assertThat(jdbc.sql("SELECT outcome || ' ' || error_kind FROM analysis_run WHERE disclosure_id = :id")
			.param("id", broken).query(String.class).list()).containsExactly("FAILED INTERNAL");
	}

	// ── 실패, 미룸, 다시 시도 ───────────────────────────────────────

	@Test
	void 세_번_실패하면_멈추고_실패마다_이유가_남는다() {
		long disclosure = newDisclosure();
		jobs.putPending(disclosure, AnalysisStage.FETCH_BODY, AnalysisPriority.NEW, EnqueueReason.NEW, NOW);
		AnalysisWorker worker = worker(handler(AnalysisStage.FETCH_BODY, job -> {
			throw ExternalApiException.of("DART", ErrorKind.TRANSIENT, "900", "시도 " + (job.attempts() + 1));
		}));

		// 실패한 작업은 5분 뒤에 다시 꺼낸다
		assertThat(worker.drain(WorkerGroup.BODY)).isEqualTo(1);
		assertThat(job(disclosure, AnalysisStage.FETCH_BODY)).containsEntry("status", "PENDING").containsEntry("attempts", 1);
		clock.advance(Duration.ofMinutes(4));
		assertThat(worker.drain(WorkerGroup.BODY)).isZero();
		clock.advance(Duration.ofMinutes(1));
		assertThat(worker.drain(WorkerGroup.BODY)).isEqualTo(1);
		clock.advance(Duration.ofMinutes(5));
		assertThat(worker.drain(WorkerGroup.BODY)).isEqualTo(1);

		assertThat(job(disclosure, AnalysisStage.FETCH_BODY)).containsEntry("status", "FAILED")
			.containsEntry("attempts", 3)
			.containsEntry("outcome", null)
			.containsEntry("locked_at", null);
		assertThat(jdbc.sql("SELECT outcome || ' ' || error_kind || ' ' || error_code || ' ' || error FROM analysis_run ORDER BY id")
			.query(String.class).list()).containsExactly("FAILED TRANSIENT 900 DART TRANSIENT [900]: 시도 1",
				"FAILED TRANSIENT 900 DART TRANSIENT [900]: 시도 2", "FAILED TRANSIENT 900 DART TRANSIENT [900]: 시도 3");
		// 멈춘 작업은 시간이 지나도 꺼내지 않는다
		clock.advance(Duration.ofDays(1));
		assertThat(worker.drain(WorkerGroup.BODY)).isZero();
	}

	@Test
	void 멈춘_작업을_다시_시도시키면_횟수가_0으로_돌아가_새_공시의_우선순위로_줄_선다() {
		long disclosure = newDisclosure();
		jobs.putPending(disclosure, AnalysisStage.FETCH_BODY, AnalysisPriority.BACKLOG, EnqueueReason.NEW, NOW);
		for (int i = 0; i < 3; i++) {
			AnalysisJob job = claimOne(AnalysisStage.FETCH_BODY);
			jobs.fail(job, "INTERNAL", null, "오류", clock.instant(), AnalysisQueueService.MAX_ATTEMPTS, clock.instant());
		}
		long jobId = (Long) job(disclosure, AnalysisStage.FETCH_BODY).get("id");

		assertThat(queue().retry(jobId)).isTrue();

		assertThat(job(disclosure, AnalysisStage.FETCH_BODY)).containsEntry("status", "PENDING")
			.containsEntry("attempts", 0)
			.containsEntry("priority", 100)
			.containsEntry("enqueue_reason", "RETRY");
		// 멈추지 않은 작업은 다시 시도시킬 수 없다
		assertThat(queue().retry(jobId)).isFalse();
		assertThat(queue().retry(-1)).isFalse();
		// 앞선 실패 기록은 그대로 남는다
		assertThat(count("SELECT count(*) FROM analysis_run WHERE outcome = 'FAILED'")).isEqualTo(3);
	}

	@Test
	void 미룬_것은_실패로_세지_않는다() {
		long disclosure = newDisclosure();
		jobs.putPending(disclosure, AnalysisStage.AI_JUDGE, AnalysisPriority.NEW, EnqueueReason.NEW, NOW);
		// AI가 과부하(503)라고 다섯 번 답한다. 실패 한도 3번을 넘겨도 멈추지 않아야 한다
		AnalysisWorker worker = worker(handler(AnalysisStage.AI_JUDGE,
			job -> StageResult.deferred(clock.instant().plusSeconds(120), ErrorKind.TRANSIENT, "503", "과부하")));

		for (int i = 0; i < 5; i++) {
			assertThat(worker.drain(WorkerGroup.AI)).isEqualTo(1);
			clock.advance(Duration.ofSeconds(120));
		}

		assertThat(job(disclosure, AnalysisStage.AI_JUDGE)).containsEntry("status", "PENDING")
			.containsEntry("attempts", 0)
			.containsEntry("deferrals", 5);
		assertThat(jdbc.sql("SELECT DISTINCT outcome || ' ' || error_kind || ' ' || error_code FROM analysis_run")
			.query(String.class).list()).containsExactly("DEFERRED TRANSIENT 503");
		assertThat(count("SELECT count(*) FROM analysis_run")).isEqualTo(5);
	}

	@Test
	void 하루_한도에_걸리면_같은_쪽의_대기_작업을_모두_미루고_다른_쪽은_그대로_둔다() {
		long first = newDisclosure();
		long second = newDisclosure();
		long summary = newDisclosure();
		long body = newDisclosure();
		jobs.putPending(first, AnalysisStage.AI_JUDGE, AnalysisPriority.NEW, EnqueueReason.NEW, NOW);
		jobs.putPending(second, AnalysisStage.AI_JUDGE, AnalysisPriority.NEW, EnqueueReason.NEW, NOW);
		jobs.putPending(summary, AnalysisStage.AI_SUMMARY, AnalysisPriority.NEW_SUMMARY, EnqueueReason.NEW, NOW);
		jobs.putPending(body, AnalysisStage.FETCH_BODY, AnalysisPriority.NEW, EnqueueReason.NEW, NOW);
		Instant resetAt = NOW.plus(Duration.ofHours(7));
		AnalysisWorker worker = worker(
			handler(AnalysisStage.AI_JUDGE, job -> StageResult.deferredAll(resetAt, ErrorKind.QUOTA_EXCEEDED, "429", "하루 한도")),
			handler(AnalysisStage.AI_SUMMARY, job -> StageResult.ended()),
			handler(AnalysisStage.FETCH_BODY, job -> StageResult.ended()));

		// 첫 작업에서 멈춘다. 나머지를 하나씩 꺼내 같은 답을 받지 않는다
		assertThat(worker.drain(WorkerGroup.AI)).isEqualTo(1);

		assertThat(count("SELECT count(*) FROM analysis_run")).isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM analysis_job WHERE stage LIKE 'AI_%' AND status = 'PENDING' AND run_after = :at")
			.param("at", java.time.OffsetDateTime.ofInstant(resetAt, java.time.ZoneOffset.UTC)).query(Long.class).single())
			.isEqualTo(3);
		assertThat(job(first, AnalysisStage.AI_JUDGE)).containsEntry("deferrals", 1).containsEntry("attempts", 0);
		assertThat(job(second, AnalysisStage.AI_JUDGE)).containsEntry("deferrals", 0);
		// 본문 받기는 그대로 돈다
		assertThat(worker.drain(WorkerGroup.BODY)).isEqualTo(1);
		assertThat(job(body, AnalysisStage.FETCH_BODY)).containsEntry("status", "DONE");
		// 한도가 풀리면 다시 꺼낸다
		assertThat(worker.drain(WorkerGroup.AI)).isZero();
		clock.set(resetAt);
		assertThat(jobs.claim(WorkerGroup.AI.stages(), 10, clock.instant())).hasSize(3);
	}

	// ── 끝을 보지 못한 실행 ─────────────────────────────────────────

	@Test
	void 실행_중에_죽은_작업은_10분_뒤에_되돌아오고_실패_한_번으로_센다() {
		long disclosure = newDisclosure();
		jobs.putPending(disclosure, AnalysisStage.FETCH_BODY, AnalysisPriority.NEW, EnqueueReason.NEW, NOW);
		AnalysisJob dead = claimOne(AnalysisStage.FETCH_BODY); // 꺼낸 뒤 끝을 기록하지 못했다

		clock.advance(Duration.ofMinutes(10));
		assertThat(queue().recoverLost()).isZero(); // 딱 10분은 아직이다
		assertThat(job(disclosure, AnalysisStage.FETCH_BODY)).containsEntry("status", "RUNNING");

		clock.advance(Duration.ofSeconds(1));
		assertThat(queue().recoverLost()).isEqualTo(1);

		assertThat(job(disclosure, AnalysisStage.FETCH_BODY)).containsEntry("status", "PENDING")
			.containsEntry("attempts", 1)
			.containsEntry("locked_at", null);
		assertThat(jdbc.sql("SELECT outcome FROM analysis_run").query(String.class).list()).containsExactly("LOST");
		// 바로 다시 꺼낼 수 있다
		AnalysisJob again = claimOne(AnalysisStage.FETCH_BODY);
		assertThat(again.attempts()).isEqualTo(1);
		// 죽은 줄 알았던 실행이 늦게 끝나도 새 실행의 줄을 덮어쓰지 못한다
		assertThatThrownBy(() -> jobs.complete(dead, StageResult.ended(), clock.instant()))
			.isInstanceOf(LockLostException.class);
		assertThat(job(disclosure, AnalysisStage.FETCH_BODY)).containsEntry("status", "RUNNING");
		assertThat(count("SELECT count(*) FROM analysis_run")).isEqualTo(1);
	}

	@Test
	void 같은_작업이_세_번_죽으면_멈춘다() {
		long disclosure = newDisclosure();
		jobs.putPending(disclosure, AnalysisStage.FETCH_BODY, AnalysisPriority.NEW, EnqueueReason.NEW, NOW);

		for (int i = 0; i < 3; i++) {
			claimOne(AnalysisStage.FETCH_BODY);
			clock.advance(Duration.ofMinutes(11));
			assertThat(queue().recoverLost()).isEqualTo(1);
		}

		assertThat(job(disclosure, AnalysisStage.FETCH_BODY)).containsEntry("status", "FAILED").containsEntry("attempts", 3);
		assertThat(jobs.claim(WorkerGroup.BODY.stages(), 10, clock.instant())).isEmpty();
	}

	@Test
	void 실행하는_동안_대기로_되돌려진_작업의_결과는_버린다() {
		long disclosure = newDisclosure();
		jobs.putPending(disclosure, AnalysisStage.READ_TABLE, AnalysisPriority.NEW, EnqueueReason.NEW, NOW);
		AnalysisWorker worker = worker(handler(AnalysisStage.READ_TABLE, job -> {
			if (job.enqueueReason() == EnqueueReason.NEW) {
				// 표를 읽는 동안 대응표가 바뀌어 이 작업이 다시 돌리기로 되돌려졌다
				queue().rerun(List.of(job.disclosureId()), AnalysisStage.READ_TABLE);
				return StageResult.passed("STALE", StageResult.Next.of(AnalysisStage.AI_JUDGE));
			}
			return StageResult.ended();
		}));

		assertThat(worker.drain(WorkerGroup.CODE)).isEqualTo(2);

		// 옛 입력으로 읽은 결과(AI 판정으로 넘김)는 남지 않고, 되돌려진 작업이 다시 실행됐다
		assertThat(count("SELECT count(*) FROM analysis_job WHERE stage = 'AI_JUDGE'")).isZero();
		assertThat(job(disclosure, AnalysisStage.READ_TABLE)).containsEntry("status", "DONE").containsEntry("outcome", "ENDED");
		assertThat(jdbc.sql("SELECT outcome || ' ' || enqueue_reason FROM analysis_run").query(String.class).list())
			.containsExactly("ENDED RERUN");
	}

	// ── 다시 분류된 공시 ────────────────────────────────────────────

	@Test
	void 다시_분류해_분류가_바뀐_공시는_본문을_받아_둔_것만_표_읽기부터_다시_지난다() {
		saveSamsung(
			item("20261119000001", "주요사항보고서(자기주식처분결정)", "20261119", ""), // 본문을 받았고 표 읽기까지 끝났다
			item("20261119000002", "주요사항보고서(자기주식처분결정)", "20261119", ""), // 본문 받기를 아직 기다린다
			item("20261119000003", "주요사항보고서(자기주식처분결정)", "20261119", ""), // 본문이 없는 공시였다
			item("20261119000004", "주요사항보고서(자기주식처분결정)", "20261119", ""), // 본문을 받았지만 분류가 바뀌지 않는다
			item("20261119000005", "주요사항보고서(자기주식처분결정)", "20261119", "")); // 본문을 받았고 표 읽기 줄이 없다
		queue().enqueueMissing();
		finishFetch("20261119000001", StageResult.passed("TONE_BY_AI", StageResult.Next.of(AnalysisStage.READ_TABLE)));
		AnalysisJob read = claimOne(AnalysisStage.READ_TABLE);
		jobs.complete(read, StageResult.ended(), clock.instant());
		finishFetch("20261119000003", StageResult.noBody());
		finishFetch("20261119000004", StageResult.ended());
		finishFetch("20261119000005", StageResult.ended());
		// 옛 대응표가 다른 유형을 붙여 둔 것처럼 만든다. 넷째는 버전만 옛것이고 분류는 지금과 같다
		jdbc.sql("UPDATE disclosure SET title_rule_version = 0").update();
		jdbc.sql("UPDATE disclosure SET type_code = 'Z9' WHERE source_key <> '20261119000004'").update();

		classification.reclassifyOutdated();

		assertThat(jdbc.sql("SELECT DISTINCT type_code FROM disclosure").query(String.class).list()).containsExactly("E4");
		assertThat(jdbc.sql("""
			SELECT d.source_key || ' ' || j.status || ' ' || j.priority || ' ' || j.enqueue_reason
			  FROM analysis_job j JOIN disclosure d ON d.id = j.disclosure_id
			 WHERE j.stage = 'READ_TABLE'
			 ORDER BY d.source_key
			""").query(String.class).list())
			.containsExactly("20261119000001 PENDING 500 RERUN", "20261119000005 PENDING 500 RERUN");
		// 본문 받기 줄은 건드리지 않는다
		assertThat(jdbc.sql("""
			SELECT d.source_key || ' ' || j.status FROM analysis_job j JOIN disclosure d ON d.id = j.disclosure_id
			 WHERE j.stage = 'FETCH_BODY' ORDER BY d.source_key
			""").query(String.class).list())
			.containsExactly("20261119000001 DONE", "20261119000002 PENDING", "20261119000003 DONE", "20261119000004 DONE",
				"20261119000005 DONE");
	}

	// ── 도우미 ──────────────────────────────────────────────────────

	private AnalysisQueueService queue(String... targetStockCodes) {
		return new AnalysisQueueService(jobs, new AnalysisProperties(List.of(targetStockCodes), null, null, null), clock);
	}

	private AnalysisWorker worker(StageHandler... handlers) {
		return new AnalysisWorker(jobs, List.of(handlers), new AnalysisProperties(null, null, null, null), clock);
	}

	private static StageHandler handler(AnalysisStage stage, Function<AnalysisJob, StageResult> body) {
		return new StageHandler() {

			@Override
			public AnalysisStage stage() {
				return stage;
			}

			@Override
			public StageResult handle(AnalysisJob job) {
				return body.apply(job);
			}
		};
	}

	private AnalysisJob claimOne(AnalysisStage stage) {
		List<AnalysisJob> claimed = jobs.claim(Set.of(stage), 1, clock.instant());
		assertThat(claimed).hasSize(1);
		return claimed.getFirst();
	}

	/** 그 공시의 본문 받기 작업을 꺼내 주어진 결과로 끝낸다. */
	private void finishFetch(String receiptNo, StageResult.Done done) {
		long disclosureId = jdbc.sql("SELECT id FROM disclosure WHERE source_key = :key").param("key", receiptNo)
			.query(Long.class).single();
		List<AnalysisJob> claimed = jobs.claim(Set.of(AnalysisStage.FETCH_BODY), 100, clock.instant());
		for (AnalysisJob job : claimed) {
			if (job.disclosureId() == disclosureId) {
				jobs.complete(job, done, clock.instant());
			}
			else {
				// 다른 공시의 작업은 도로 대기로 둔다
				jobs.putPending(job.disclosureId(), AnalysisStage.FETCH_BODY, job.priority(), EnqueueReason.NEW, clock.instant());
			}
		}
	}

	private void saveSamsung(Item... items) {
		disclosures.saveListed(List.of(items));
	}

	private static Item item(String receiptNo, String reportName, String day, String remark) {
		return new Item("00126380", "삼성전자", "005930", "Y", reportName, receiptNo, "삼성전자", day, remark);
	}

	private int sequence;

	/** 큐의 동작만 볼 때 쓰는 공시 한 건. 분류는 붙이지 않는다. */
	private long newDisclosure() {
		Long company = jdbc.sql("SELECT id FROM company WHERE source_key = '00126380'").query(Long.class).optional()
			.orElseGet(() -> jdbc.sql("""
				INSERT INTO company (country, source, source_key, stock_code, name)
				VALUES ('KR', 'DART', '00126380', '005930', '삼성전자') RETURNING id
				""").query(Long.class).single());
		return jdbc.sql("""
				INSERT INTO disclosure (source, source_key, company_id, report_name, rcept_date)
				VALUES ('DART', :key, :company, '큐 시험용 공시', DATE '2026-11-19') RETURNING id
				""")
			.param("key", "2026111990%04d".formatted(++sequence))
			.param("company", company)
			.query(Long.class)
			.single();
	}

	private Map<String, Object> job(long disclosureId, AnalysisStage stage) {
		return jdbc.sql("SELECT * FROM analysis_job WHERE disclosure_id = :id AND stage = :stage")
			.param("id", disclosureId)
			.param("stage", stage.name())
			.query()
			.singleRow();
	}

	private long count(String sql) {
		return jdbc.sql(sql).query(Long.class).single();
	}

	/** 시험에서 시각을 옮길 수 있는 시계. */
	private static final class MovableClock extends Clock {

		private Instant now;

		MovableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		void set(Instant instant) {
			now = instant;
		}

		@Override
		public ZoneId getZone() {
			return KST;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
