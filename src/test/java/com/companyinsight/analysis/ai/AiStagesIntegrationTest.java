package com.companyinsight.analysis.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.companyinsight.TestcontainersConfiguration;
import com.companyinsight.analysis.AnalysisJobRepository;
import com.companyinsight.analysis.AnalysisProperties;
import com.companyinsight.analysis.AnalysisQueueService;
import com.companyinsight.analysis.AnalysisStage;
import com.companyinsight.analysis.AnalysisWorker;
import com.companyinsight.analysis.StageHandler;
import com.companyinsight.analysis.WorkerGroup;
import com.companyinsight.analysis.body.DisclosureBodyReader;
import com.companyinsight.analysis.body.DisclosureBodyRepository;
import com.companyinsight.analysis.table.AiSizeRules;
import com.companyinsight.analysis.table.TableReadHandler;
import com.companyinsight.common.dart.DisclosureListResponse.Item;
import com.companyinsight.common.external.ErrorKind;
import com.companyinsight.common.external.ExternalApiException;
import com.companyinsight.common.gemini.GeminiClient;
import com.companyinsight.common.gemini.GeminiProperties;
import com.companyinsight.common.gemini.GeminiReply;
import com.companyinsight.disclosure.DisclosureRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * AI 판정·요약 단계를 큐와 실제 PostgreSQL 위에서 확인한다. Gemini는 정해 둔 답을 돌려주는 가짜로 바꾼다.
 *
 * <p>본문은 실제 공시 원문이다: SK하이닉스 2026-06-24 주요사항보고서(유상증자결정), 접수번호 20260624000420.
 * 증자방식 제3자배정증자, 신주 보통주식 17,790,000주, 증자전 발행주식총수 712,702,365주, 시설자금 45,453,450,000,000원,
 * 납입일 2026.07.14, 신주의 상장 예정일 2026.07.29.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AiStagesIntegrationTest {

	/** 2026-11-20 10:00 (한국 시간) */
	private static final Instant NOW = Instant.parse("2026-11-20T01:00:00Z");

	private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));

	private static final String RIGHTS_THIRD_PARTY = "20260624000420";

	private static final String GOOD_JUDGE = """
			{"direction":"악재","evidence":"증자방식: 제3자배정증자","reason":"제3자배정 신주 발행",
			 "headline":"미국 주식예탁증서용 제3자배정 유상증자",
			 "summary":["신주 보통주식 17,790,000주를 발행한다.","시설자금 45조 4,535억 원을 조달한다.","납입일은 2026.07.14이다."]}
			""";

	@Autowired
	AnalysisJobRepository jobs;

	@Autowired
	DisclosureRepository disclosures;

	@Autowired
	DisclosureBodyRepository bodies;

	@Autowired
	DisclosureBodyReader bodyReader;

	@Autowired
	TableReadHandler tableRead;

	@Autowired
	AiTargetRepository targets;

	@Autowired
	AiPrompts prompts;

	@Autowired
	AiSettingVersions versions;

	@Autowired
	AiSizeRules sizeRules;

	@Autowired
	JdbcClient jdbc;

	private final AnalysisProperties properties = new AnalysisProperties(List.of(), null, null, Duration.ofMinutes(5));

	private final FakeGemini gemini = new FakeGemini();

	@BeforeEach
	@AfterEach
	void clean() {
		gemini.answers.clear();
		jdbc.sql("DELETE FROM disclosure_summary").update();
		jdbc.sql("DELETE FROM disclosure_body").update();
		jdbc.sql("DELETE FROM analysis_run").update();
		jdbc.sql("DELETE FROM analysis_job").update();
		jdbc.sql("DELETE FROM disclosure").update();
		jdbc.sql("DELETE FROM company").update();
	}

	@Test
	void 제3자배정은_AI가_방향을_정하고_코드가_신주_비율로_크기를_정하며_요약을_함께_적는다() {
		long id = saveAndReadTable();
		gemini.answers.add(GOOD_JUDGE);

		assertThat(drainAi()).isEqualTo(1);

		// 17,790,000 ÷ 712,702,365 = 2.50% → 주식 수 경계(5% 미만) 1
		assertThat(disclosure(id)).containsEntry("tone", "NEGATIVE").containsEntry("impact", 1).containsEntry("classified_by", "LLM");
		assertThat((BigDecimal) disclosure(id).get("impact_ratio")).isEqualByComparingTo("2.4961");
		assertThat(job(id, "AI_JUDGE")).containsEntry("status", "DONE").containsEntry("outcome", "ENDED");
		Map<String, Object> run = lastRun(id);
		assertThat(run.get("setting_version_id")).isNotNull();
		assertThat(run).containsEntry("input_tokens", 1000).containsEntry("output_tokens", 100);
		assertThat(run.get("result").toString()).contains("\"evidence_ok\": true", "\"summary_ok\": true", "\"size_basis\": \"RATIO\"");
		Map<String, Object> summary = summary(id);
		assertThat(summary).containsEntry("headline", "미국 주식예탁증서용 제3자배정 유상증자").containsEntry("written_by", "AI")
			.containsEntry("run_id", run.get("id"));
		assertThat(summary.get("lines").toString()).contains("17,790,000주");
		// AI에 보낸 입력에 표에서 읽은 항목과 본문이 들어 있다
		assertThat(gemini.lastUser).contains("[코드가 표에서 읽은 항목]", "증자방식: 제3자배정증자", "[본문]");
	}

	@Test
	void 근거로_옮긴_구절이_본문에_없으면_판정을_보류한다() {
		long id = saveAndReadTable();
		gemini.answers.add(GOOD_JUDGE.replace("증자방식: 제3자배정증자", "경영권 인수를 위한 전략적 투자"));

		drainAi();

		assertThat(disclosure(id)).containsEntry("tone", null).containsEntry("classified_by", null);
		assertThat(job(id, "AI_JUDGE")).containsEntry("status", "DONE").containsEntry("outcome", "HELD");
		assertThat(lastRun(id).get("result").toString()).contains("\"held\": \"EVIDENCE_NOT_FOUND\"");
		assertThat(count("SELECT count(*) FROM disclosure_summary")).isZero();
	}

	@Test
	void 판정불가면_보류하고_앞서_AI가_적은_판정을_지운다() {
		long id = saveAndReadTable();
		gemini.answers.add(GOOD_JUDGE);
		drainAi();
		assertThat(disclosure(id)).containsEntry("tone", "NEGATIVE");

		queue().rerun(List.of(id), AnalysisStage.AI_JUDGE);
		gemini.answers.add(GOOD_JUDGE.replace("\"악재\"", "\"판정불가\""));
		drainAi();

		assertThat(disclosure(id)).containsEntry("tone", null).containsEntry("impact", null);
		assertThat(count("SELECT count(*) FROM disclosure_summary")).isZero();
	}

	@Test
	void 요약의_숫자가_원문과_맞지_않으면_판정만_적고_요약_단계로_넘긴다() {
		long id = saveAndReadTable();
		gemini.answers.add(GOOD_JUDGE.replace("45조 4,535억 원", "46조 원"));
		// 이어서 요약 단계가 맞는 요약을 쓴다
		gemini.answers.add("""
				{"headline":"제3자배정 유상증자 결정","summary":["신주 보통주식 17,790,000주를 발행한다.","신주의 상장 예정일은 2026.07.29이다."]}
				""");

		assertThat(drainAi()).isEqualTo(2);

		assertThat(disclosure(id)).containsEntry("tone", "NEGATIVE").containsEntry("impact", 1);
		assertThat(job(id, "AI_JUDGE")).containsEntry("outcome", "PASSED");
		Map<String, Object> judgeRun = jdbc.sql("SELECT * FROM analysis_run WHERE disclosure_id = :id AND stage = 'AI_JUDGE'")
			.param("id", id).query().singleRow();
		assertThat(judgeRun).containsEntry("pass_reason", "SUMMARY_UNVERIFIED");
		assertThat(judgeRun.get("result").toString()).contains("본문에 없는 숫자·날짜: 46조");
		assertThat(job(id, "AI_SUMMARY")).containsEntry("status", "DONE").containsEntry("outcome", "ENDED");
		Map<String, Object> summary = summary(id);
		assertThat(summary).containsEntry("headline", "제3자배정 유상증자 결정");
		assertThat(summary.get("run_id")).isEqualTo(lastRun(id).get("id"));
		assertThat(lastRun(id)).containsEntry("stage", "AI_SUMMARY");
	}

	@Test
	void 요약이_세_번_원문과_맞지_않으면_멈추고_요약은_빈_채로_둔다() {
		long id = saveAndReadTable();
		jdbc.sql("UPDATE disclosure SET tone = 'NEGATIVE', impact = 1, classified_by = 'RULE' WHERE id = :id").param("id", id).update();
		queue().rerun(List.of(id), AnalysisStage.AI_SUMMARY);
		String wrong = """
				{"headline":"제3자배정 유상증자","summary":["신주 18,000,000주를 발행한다."]}
				""";
		for (int i = 0; i < 3; i++) {
			gemini.answers.add(wrong);
			jdbc.sql("UPDATE analysis_job SET run_after = :now WHERE disclosure_id = :id").param("now", OffsetDateTime.ofInstant(NOW, ZoneId.of("UTC")))
				.param("id", id).update();
			drainAi();
		}

		assertThat(job(id, "AI_SUMMARY")).containsEntry("status", "FAILED").containsEntry("attempts", 3);
		Map<String, Object> run = lastRun(id);
		assertThat(run).containsEntry("error_kind", "VERIFY");
		assertThat(run.get("result").toString()).contains("18,000,000");
		assertThat(run.get("setting_version_id")).isNotNull();
		assertThat(count("SELECT count(*) FROM disclosure_summary")).isZero();
		// 요약이 없어도 판정은 그대로다
		assertThat(disclosure(id)).containsEntry("tone", "NEGATIVE");
	}

	@Test
	void 과부하는_실패로_세지_않고_이_작업만_미룬다() {
		long id = saveAndReadTable();
		gemini.errors.add(ExternalApiException.of(GeminiClient.SOURCE, ErrorKind.TRANSIENT, "HTTP503", "서버 오류"));

		drainAi();

		Map<String, Object> job = jdbc.sql("""
				SELECT status, attempts::int AS attempts, deferrals::int AS deferrals, run_after FROM analysis_job
				 WHERE disclosure_id = :id AND stage = 'AI_JUDGE'
				""").param("id", id).query().singleRow();
		assertThat(job).containsEntry("status", "PENDING").containsEntry("attempts", 0).containsEntry("deferrals", 1);
		assertThat(((java.sql.Timestamp) job.get("run_after")).toInstant()).isEqualTo(NOW.plus(AiCaller.TRANSIENT_DELAY));
		assertThat(lastRun(id)).containsEntry("outcome", "DEFERRED").containsEntry("error_code", "HTTP503");
		assertThat(lastRun(id).get("setting_version_id")).isNotNull();
	}

	@Test
	void 하루_한도면_AI_쪽_대기_작업을_모두_한도가_풀리는_시각_뒤로_민다() {
		long first = saveAndReadTable();
		long other = saveAndReadTable("20260624000421");
		gemini.errors.add(ExternalApiException.of(GeminiClient.SOURCE, ErrorKind.QUOTA_EXCEEDED, "HTTP429", "하루 한도 소진"));

		assertThat(drainAi()).isEqualTo(1);

		// 미국 태평양 시간 2026-11-20 00:00 = 08:00Z, 여유 5분
		Instant reset = Instant.parse("2026-11-20T08:05:00Z");
		for (long id : List.of(first, other)) {
			java.sql.Timestamp runAfter = jdbc.sql("SELECT run_after FROM analysis_job WHERE disclosure_id = :id AND stage = 'AI_JUDGE'")
				.param("id", id).query(java.sql.Timestamp.class).single();
			assertThat(runAfter.toInstant()).isEqualTo(reset);
		}
	}

	@Test
	void 그_사이_중립이_된_공시의_요약은_부르지_않고_지운다() {
		long id = saveAndReadTable();
		gemini.answers.add(GOOD_JUDGE);
		drainAi();
		assertThat(count("SELECT count(*) FROM disclosure_summary")).isEqualTo(1);
		jdbc.sql("UPDATE disclosure SET tone = 'NEUTRAL', impact = NULL, classified_by = 'TABLE' WHERE id = :id").param("id", id).update();
		queue().rerun(List.of(id), AnalysisStage.AI_SUMMARY);

		drainAi();

		assertThat(gemini.calls).isEqualTo(1);
		assertThat(lastRun(id).get("result").toString()).contains("NOT_NEEDED");
		assertThat(count("SELECT count(*) FROM disclosure_summary")).isZero();
	}

	@Test
	void 뒤_정정으로_대체된_공시는_부르지_않는다() {
		long id = saveAndReadTable();
		long later = saveAndReadTable("20260710000008");
		jdbc.sql("UPDATE disclosure SET superseded_by = :later, bundle_id = :later WHERE id = :id").param("later", later).param("id", id).update();
		jdbc.sql("UPDATE analysis_job SET run_after = now() + interval '1 day' WHERE disclosure_id = :later").param("later", later).update();

		drainAi();

		assertThat(gemini.calls).isZero();
		assertThat(lastRun(id).get("result").toString()).contains("SUPERSEDED");
	}

	@Test
	void 같은_설정이면_설정_버전은_하나다() {
		saveAndReadTable();
		saveAndReadTable("20260624000421");
		gemini.answers.add(GOOD_JUDGE);
		gemini.answers.add(GOOD_JUDGE);

		drainAi();

		assertThat(count("SELECT count(DISTINCT setting_version_id) FROM analysis_run WHERE stage = 'AI_JUDGE'")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM setting_version WHERE bundle = 'AI_JUDGE'")).isEqualTo(1);
	}

	// ── 도우미 ──────────────────────────────────────────────────────

	private int drainAi() {
		return worker().drain(WorkerGroup.AI);
	}

	private AnalysisWorker worker() {
		GeminiProperties geminiProperties = new GeminiProperties("test-key", null, null, null, null, Duration.ZERO, null);
		AiCaller caller = new AiCaller(gemini, geminiProperties, CLOCK);
		List<StageHandler> handlers = List.of(
			new AiJudgeHandler(targets, bodyReader, caller, prompts, versions, sizeRules),
			new AiSummaryHandler(targets, bodyReader, caller, prompts, versions, sizeRules));
		return new AnalysisWorker(jobs, handlers, properties, CLOCK);
	}

	private AnalysisQueueService queue() {
		return new AnalysisQueueService(jobs, properties, CLOCK);
	}

	private long saveAndReadTable() {
		return saveAndReadTable(RIGHTS_THIRD_PARTY);
	}

	/** SK하이닉스의 유상증자결정을 그 접수번호로 저장하고(본문은 3자배정 원문), 표 읽기를 돌려 AI 판정에 줄 세운다. */
	private long saveAndReadTable(String receiptNo) {
		disclosures.saveListed(List.of(new Item("00164779", "SK하이닉스", "000660", "Y", "주요사항보고서(유상증자결정)", receiptNo,
				"SK하이닉스", "20260624", "")));
		long id = jdbc.sql("SELECT id FROM disclosure WHERE source_key = :key").param("key", receiptNo).query(Long.class).single();
		bodies.save(id, zip(RIGHTS_THIRD_PARTY), 1, NOW);
		queue().rerun(List.of(id), AnalysisStage.READ_TABLE);
		new AnalysisWorker(jobs, List.of(tableRead), properties, CLOCK).drain(WorkerGroup.CODE);
		assertThat(job(id, "AI_JUDGE")).containsEntry("status", "PENDING");
		return id;
	}

	private Map<String, Object> disclosure(long id) {
		return jdbc.sql("SELECT tone, impact::int AS impact, impact_ratio, classified_by FROM disclosure WHERE id = :id")
			.param("id", id).query().singleRow();
	}

	private Map<String, Object> job(long disclosureId, String stage) {
		return jdbc.sql("""
				SELECT status, outcome, attempts::int AS attempts FROM analysis_job WHERE disclosure_id = :id AND stage = :stage
				""")
			.param("id", disclosureId).param("stage", stage).query().singleRow();
	}

	private Map<String, Object> lastRun(long disclosureId) {
		return jdbc.sql("SELECT * FROM analysis_run WHERE disclosure_id = :id ORDER BY id DESC LIMIT 1")
			.param("id", disclosureId).query().singleRow();
	}

	private Map<String, Object> summary(long disclosureId) {
		return jdbc.sql("SELECT headline, lines, written_by, run_id FROM disclosure_summary WHERE disclosure_id = :id")
			.param("id", disclosureId).query().singleRow();
	}

	private long count(String sql) {
		return jdbc.sql(sql).query(Long.class).single();
	}

	private static byte[] zip(String receiptNo) {
		try (InputStream in = AiStagesIntegrationTest.class.getResourceAsStream("/dart/body/" + receiptNo + ".zip")) {
			return in.readAllBytes();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** 정해 둔 답(또는 오류)을 차례로 돌려주는 가짜 Gemini. */
	private static final class FakeGemini extends GeminiClient {

		final Deque<String> answers = new ArrayDeque<>();
		final Deque<ExternalApiException> errors = new ArrayDeque<>();
		int calls;
		String lastUser;

		FakeGemini() {
			super(null, new GeminiProperties("test-key", null, null, null, null, Duration.ZERO, null), CLOCK);
		}

		@Override
		public synchronized GeminiReply generate(String systemInstruction, String userText, Map<String, Object> responseSchema) {
			calls++;
			lastUser = userText;
			if (!errors.isEmpty()) {
				throw errors.poll();
			}
			if (answers.isEmpty()) {
				throw new IllegalStateException("정해 둔 답이 없습니다");
			}
			return new GeminiReply(answers.poll(), "STOP", 1000, 100);
		}
	}
}
