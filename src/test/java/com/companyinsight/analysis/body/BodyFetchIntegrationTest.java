package com.companyinsight.analysis.body;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.companyinsight.TestcontainersConfiguration;
import com.companyinsight.analysis.AnalysisJobRepository;
import com.companyinsight.analysis.AnalysisProperties;
import com.companyinsight.analysis.AnalysisQueueService;
import com.companyinsight.analysis.AnalysisWorker;
import com.companyinsight.analysis.WorkerGroup;
import com.companyinsight.common.dart.DartClient;
import com.companyinsight.common.dart.DartProperties;
import com.companyinsight.common.dart.DisclosureListResponse.Item;
import com.companyinsight.common.external.SourcePauseRegistry;
import com.companyinsight.disclosure.DisclosureRepository;
import io.github.resilience4j.ratelimiter.RateLimiter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 본문 받기 단계를 큐와 실제 PostgreSQL 위에서 확인한다. DART는 부르지 않고, 실제로 받아 둔 응답을 돌려준다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BodyFetchIntegrationTest {

	/** 2026-11-20 10:00 (한국 시간) */
	private static final Instant NOW = Instant.parse("2026-11-20T01:00:00Z");

	private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));

	private static final String NO_FILE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><result><status>014</status>"
			+ "<message>파일이 존재하지 않습니다.</message></result>";

	@Autowired
	AnalysisJobRepository jobs;

	@Autowired
	DisclosureRepository disclosures;

	@Autowired
	DisclosureBodyRepository bodies;

	@Autowired
	DisclosureBodyReader reader;

	@Autowired
	JdbcClient jdbc;

	private MockRestServiceServer dartServer;
	private DartClient dart;
	private final SourcePauseRegistry pauses = new SourcePauseRegistry(CLOCK);
	private final AnalysisProperties properties = new AnalysisProperties(List.of(), null, null, null);

	@BeforeEach
	void setUp() {
		clean();
		dart = dartClient("test-key");
	}

	/** 다른 테스트가 공시를 지울 때 본문이 남아 있으면 참조 제약에 걸린다. */
	@AfterEach
	void clean() {
		jdbc.sql("DELETE FROM disclosure_body").update();
		jdbc.sql("DELETE FROM analysis_run").update();
		jdbc.sql("DELETE FROM analysis_job").update();
		jdbc.sql("DELETE FROM disclosure").update();
		jdbc.sql("DELETE FROM company").update();
	}

	// ── 받아서 저장 ─────────────────────────────────────────────────

	@Test
	void 본문을_받아_그대로_저장하고_표_읽기로_넘긴다() {
		long id = save(BodyFixtures.TREASURY_DISPOSAL, "주요사항보고서(자기주식처분결정)", "20260126");
		byte[] zip = BodyFixtures.zip(BodyFixtures.TREASURY_DISPOSAL);
		respond(BodyFixtures.TREASURY_DISPOSAL, zip);

		int processed = drain();

		assertThat(processed).isEqualTo(1);
		dartServer.verify();
		assertThat(job(id, "FETCH_BODY")).containsEntry("status", "DONE").containsEntry("outcome", "PASSED");
		assertThat(job(id, "READ_TABLE")).containsEntry("status", "PENDING");
		Map<String, Object> body = jdbc.sql("SELECT content, byte_size, file_count::int AS file_count, fetched_at FROM disclosure_body WHERE disclosure_id = :id").param("id", id)
			.query().singleRow();
		assertThat((byte[]) body.get("content")).isEqualTo(zip);
		assertThat(body).containsEntry("byte_size", 5198).containsEntry("file_count", 1);
		assertThat(instant(body.get("fetched_at"))).isEqualTo(NOW);
		Map<String, Object> run = lastRun(id);
		assertThat(run).containsEntry("outcome", "PASSED").containsEntry("pass_reason", "TONE_BY_AI");
		assertThat(run.get("result").toString()).contains("\"bytes\": 5198", "\"files\": 1", "\"format\": \"XML\"");
	}

	@Test
	void 저장한_본문을_다시_글자로_읽을_수_있다() {
		long id = save(BodyFixtures.TREASURY_DISPOSAL, "주요사항보고서(자기주식처분결정)", "20260126");
		respond(BodyFixtures.TREASURY_DISPOSAL, BodyFixtures.zip(BodyFixtures.TREASURY_DISPOSAL));
		drain();

		List<BodyDocument> documents = reader.read(id).orElseThrow();

		assertThat(documents).singleElement().satisfies(document -> {
			assertThat(document.format()).isEqualTo(BodyFormat.XML);
			assertThat(document.text()).contains("3. 처분예정금액(원) | 보통주식 | 175,222,546,200");
		});
	}

	@Test
	void 제목으로_중립이_정해진_공시는_본문을_저장만_하고_끝난다() {
		// 본문은 다른 공시의 것을 빌렸다. 여기서 보는 것은 다음 단계가 없다는 것이다
		long id = save("20261119800001", "기업설명회(IR)개최", "20261119");
		respond("20261119800001", BodyFixtures.zip(BodyFixtures.VOLUNTARY));

		drain();

		assertThat(job(id, "FETCH_BODY")).containsEntry("status", "DONE").containsEntry("outcome", "ENDED");
		assertThat(count("SELECT count(*) FROM analysis_job")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM disclosure_body")).isEqualTo(1);
	}

	@Test
	void 포괄_제목의_공시는_AI_판정으로_넘긴다() {
		long id = save(BodyFixtures.VOLUNTARY, "기타경영사항(자율공시)", "20251128");
		respond(BodyFixtures.VOLUNTARY, BodyFixtures.zip(BodyFixtures.VOLUNTARY));

		drain();

		assertThat(job(id, "FETCH_BODY")).containsEntry("outcome", "PASSED");
		assertThat(job(id, "AI_JUDGE")).containsEntry("status", "PENDING");
		assertThat(lastRun(id)).containsEntry("pass_reason", "NO_FIXED_FORM");
	}

	@Test
	void 정정_공시는_제목이_중립이어도_표_읽기로_넘긴다() {
		long id = save(BodyFixtures.CORRECTION, "[기재정정]생산중단", "20260824");
		respond(BodyFixtures.CORRECTION, BodyFixtures.zip(BodyFixtures.CORRECTION));

		drain();

		assertThat(job(id, "READ_TABLE")).containsEntry("status", "PENDING");
		assertThat(lastRun(id)).containsEntry("pass_reason", "CORRECTION");
	}

	// ── 본문 없음 ───────────────────────────────────────────────────

	@Test
	void 본문이_없는_공시는_실패가_아니라_본문_없음으로_끝난다() {
		long id = save("20261119000001", "[첨부정정]주요사항보고서(자기주식처분결정)", "20261119");
		respond("20261119000001", NO_FILE.getBytes(StandardCharsets.UTF_8));

		drain();

		assertThat(job(id, "FETCH_BODY")).containsEntry("status", "DONE").containsEntry("outcome", "NO_BODY")
			.containsEntry("attempts", 0);
		assertThat(count("SELECT count(*) FROM disclosure_body")).isZero();
		assertThat(count("SELECT count(*) FROM analysis_job")).isEqualTo(1);
	}

	// ── 출처를 부르지 않고 끝나는 경우 ─────────────────────────────────

	@Test
	void 줄_선_뒤에_철회된_공시는_출처를_부르지_않고_끝난다() {
		long id = save("20261119000001", "주요사항보고서(자기주식처분결정)", "20261119");
		jdbc.sql("UPDATE disclosure SET is_withdrawn = true WHERE id = :id").param("id", id).update();

		drain();

		dartServer.verify(); // 기대한 호출이 없다
		assertThat(job(id, "FETCH_BODY")).containsEntry("outcome", "ENDED");
		assertThat(lastRun(id).get("result").toString()).contains("WITHDRAWN");
		assertThat(count("SELECT count(*) FROM disclosure_body")).isZero();
	}

	@Test
	void 줄_선_뒤에_본문을_받지_않는_유형으로_바뀐_공시는_출처를_부르지_않고_끝난다() {
		long id = save("20261119000001", "주요사항보고서(자기주식처분결정)", "20261119");
		jdbc.sql("UPDATE disclosure SET type_code = 'A1', title_rule_id = NULL WHERE id = :id").param("id", id).update();

		drain();

		dartServer.verify();
		assertThat(job(id, "FETCH_BODY")).containsEntry("outcome", "ENDED");
		assertThat(lastRun(id).get("result").toString()).contains("TYPE_NOT_FETCHED");
	}

	@Test
	void 본문을_이미_받아_둔_공시는_출처를_다시_부르지_않는다() {
		long id = save(BodyFixtures.TREASURY_DISPOSAL, "주요사항보고서(자기주식처분결정)", "20260126");
		byte[] zip = BodyFixtures.zip(BodyFixtures.TREASURY_DISPOSAL);
		bodies.save(id, zip, 1, NOW.minusSeconds(3600));

		drain();

		dartServer.verify();
		assertThat(job(id, "FETCH_BODY")).containsEntry("outcome", "PASSED");
		assertThat(job(id, "READ_TABLE")).containsEntry("status", "PENDING");
		assertThat(lastRun(id).get("result").toString()).contains("\"reused\": true");
		assertThat(bodies.findContent(id).orElseThrow()).isEqualTo(zip);
	}

	// ── 실패와 미룸 ─────────────────────────────────────────────────

	@Test
	void 한_건이_실패해도_나머지는_계속_받는다() {
		long failed = save("20261119000001", "주요사항보고서(자기주식처분결정)", "20261119");
		long ok = save("20261119000002", "주요사항보고서(자기주식처분결정)", "20261119");
		dartServer.expect(requestTo(containsString("rcept_no=20261119000001"))).andRespond(withServerError());
		respond("20261119000002", BodyFixtures.zip(BodyFixtures.TREASURY_DISPOSAL));

		int processed = drain();

		assertThat(processed).isEqualTo(2);
		assertThat(job(failed, "FETCH_BODY")).containsEntry("status", "PENDING").containsEntry("attempts", 1);
		assertThat(instant(job(failed, "FETCH_BODY").get("run_after")))
			.isEqualTo(NOW.plus(properties.retryDelay()));
		assertThat(lastRun(failed)).containsEntry("outcome", "FAILED").containsEntry("error_kind", "TRANSIENT")
			.containsEntry("error_code", "HTTP500");
		assertThat(lastRun(failed).get("error").toString()).doesNotContain("test-key");
		assertThat(job(ok, "FETCH_BODY")).containsEntry("status", "DONE");
	}

	@Test
	void 세_번_실패하면_멈춘다() {
		long id = save("20261119000001", "주요사항보고서(자기주식처분결정)", "20261119");
		for (int i = 0; i < AnalysisQueueService.MAX_ATTEMPTS; i++) {
			dartServer.expect(requestTo(containsString("rcept_no=20261119000001"))).andRespond(withServerError());
		}

		for (int i = 0; i < AnalysisQueueService.MAX_ATTEMPTS; i++) {
			drain();
			jdbc.sql("UPDATE analysis_job SET run_after = :now WHERE status = 'PENDING'")
				.param("now", OffsetDateTime.ofInstant(NOW, java.time.ZoneOffset.UTC)).update();
		}

		assertThat(job(id, "FETCH_BODY")).containsEntry("status", "FAILED").containsEntry("attempts", 3);
	}

	@Test
	void 풀리지_않는_파일은_저장하지_않고_실패로_센다() {
		long id = save("20261119000001", "주요사항보고서(자기주식처분결정)", "20261119");
		byte[] cut = Arrays.copyOf(BodyFixtures.zip(BodyFixtures.TREASURY_DISPOSAL), 300);
		respond("20261119000001", cut);

		drain();

		assertThat(job(id, "FETCH_BODY")).containsEntry("status", "PENDING").containsEntry("attempts", 1);
		assertThat(lastRun(id)).containsEntry("outcome", "FAILED").containsEntry("error_kind", "PARSE");
		assertThat(count("SELECT count(*) FROM disclosure_body")).isZero();
	}

	@Test
	void 하루_한도에_걸리면_실패로_세지_않고_본문_받기_전체를_다음_자정_뒤로_미룬다() {
		long first = save("20261119000001", "주요사항보고서(자기주식처분결정)", "20261119");
		long second = save("20261119000002", "주요사항보고서(자기주식처분결정)", "20261119");
		respond("20261119000001", "<result><status>020</status><message>사용한도를 초과하였습니다.</message></result>"
			.getBytes(StandardCharsets.UTF_8));

		int processed = drain();

		// 두 번째 공시는 부르지 않는다
		dartServer.verify();
		assertThat(processed).isEqualTo(1);
		Instant nextMidnight = Instant.parse("2026-11-20T15:00:00Z"); // 2026-11-21 00:00 한국 시간
		assertThat(job(first, "FETCH_BODY")).containsEntry("status", "PENDING").containsEntry("attempts", 0)
			.containsEntry("deferrals", 1);
		assertThat(instant(job(first, "FETCH_BODY").get("run_after"))).isEqualTo(nextMidnight);
		assertThat(instant(job(second, "FETCH_BODY").get("run_after"))).isEqualTo(nextMidnight);
		assertThat(lastRun(first)).containsEntry("outcome", "DEFERRED").containsEntry("error_kind", "QUOTA_EXCEEDED")
			.containsEntry("error_code", "020");
		// 쉬는 동안에는 다시 돌려도 부르지 않는다
		jdbc.sql("UPDATE analysis_job SET run_after = :now").param("now", OffsetDateTime.ofInstant(NOW, java.time.ZoneOffset.UTC))
			.update();
		assertThat(drain()).isEqualTo(1);
		dartServer.verify();
	}

	@Test
	void 인증키가_없으면_출처를_부르지_않고_미룬다() {
		long id = save("20261119000001", "주요사항보고서(자기주식처분결정)", "20261119");
		dart = dartClient("");

		drain();

		dartServer.verify();
		assertThat(job(id, "FETCH_BODY")).containsEntry("status", "PENDING").containsEntry("attempts", 0);
		assertThat(lastRun(id)).containsEntry("outcome", "DEFERRED").containsEntry("error_kind", "AUTH");
	}

	@Test
	void 제목으로_호재가_정해진_공시는_표_읽기로_넘긴다() {
		long id = save("20261119800002", "주식소각결정", "20261119");
		respond("20261119800002", BodyFixtures.zip(BodyFixtures.VOLUNTARY));

		drain();

		assertThat(jdbc.sql("SELECT tone FROM disclosure WHERE id = :id").param("id", id).query(String.class).single())
			.isEqualTo("POSITIVE");
		assertThat(job(id, "READ_TABLE")).containsEntry("status", "PENDING");
		assertThat(lastRun(id)).containsEntry("pass_reason", "TONE_BY_TITLE");
	}

	@Test
	void 첨부만_더한_공시는_본문을_저장하고_판정_없이_끝난다() {
		long id = save("20261119000003", "[첨부추가]주요사항보고서(자기주식처분결정)", "20261119");
		respond("20261119000003", BodyFixtures.zip(BodyFixtures.TREASURY_DISPOSAL));

		drain();

		assertThat(job(id, "FETCH_BODY")).containsEntry("status", "DONE").containsEntry("outcome", "ENDED");
		assertThat(count("SELECT count(*) FROM disclosure_body")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM analysis_job")).isEqualTo(1);
	}

	@Test
	void 파일이_여럿인_zip은_파일_수와_함께_그대로_저장한다() throws Exception {
		long id = save("20261119000004", "주요사항보고서(자기주식처분결정)", "20261119");
		byte[] zip = BodyArchiveTest.zip("20261119000004.xml", "<html><body><p>첫 문서</p></body></html>",
			"20261119000004_00760.xml", "<html><body><p>둘째 문서</p></body></html>");
		respond("20261119000004", zip);

		drain();

		assertThat(jdbc.sql("SELECT file_count::int FROM disclosure_body WHERE disclosure_id = :id").param("id", id)
			.query(Integer.class).single()).isEqualTo(2);
		assertThat(reader.read(id).orElseThrow()).extracting(BodyDocument::text).containsExactly("첫 문서", "둘째 문서");
		assertThat(lastRun(id).get("result").toString()).contains("\"files\": 2");
	}

	@Test
	void 접수한_날에_본문이_없다는_답이_오면_본문_없음으로_굳히지_않고_한_시간_뒤에_다시_확인한다() {
		// 오늘은 2026-11-20 (한국 시간)
		long id = save("20261120000001", "주요사항보고서(자기주식처분결정)", "20261120");
		respond("20261120000001", NO_FILE.getBytes(StandardCharsets.UTF_8));

		drain();

		assertThat(job(id, "FETCH_BODY")).containsEntry("status", "PENDING").containsEntry("attempts", 0)
			.containsEntry("deferrals", 1);
		assertThat(instant(job(id, "FETCH_BODY").get("run_after"))).isEqualTo(NOW.plusSeconds(3600));
		assertThat(lastRun(id)).containsEntry("outcome", "DEFERRED").containsEntry("error_code", "014");
	}

	@Test
	void 인증_오류가_나면_실패로_세지_않고_본문_받기_전체를_미룬다() {
		long first = save("20261119000001", "주요사항보고서(자기주식처분결정)", "20261119");
		long second = save("20261119000002", "주요사항보고서(자기주식처분결정)", "20261119");
		respond("20261119000001", "<result><status>010</status><message>등록되지 않은 키입니다.</message></result>"
			.getBytes(StandardCharsets.UTF_8));

		assertThat(drain()).isEqualTo(1);

		dartServer.verify();
		assertThat(job(first, "FETCH_BODY")).containsEntry("status", "PENDING").containsEntry("attempts", 0);
		assertThat(lastRun(first)).containsEntry("outcome", "DEFERRED").containsEntry("error_kind", "AUTH")
			.containsEntry("error_code", "010");
		assertThat(instant(job(second, "FETCH_BODY").get("run_after"))).isEqualTo(NOW.plusSeconds(3600));
	}

	@Test
	void 출처를_부르지_않고_끝났던_공시가_다시_분류되면_본문을_받으러_간다() {
		long id = save("20261119000001", "주요사항보고서(자기주식처분결정)", "20261119");
		jdbc.sql("UPDATE disclosure SET type_code = 'A1', title_rule_id = NULL WHERE id = :id").param("id", id).update();
		drain();
		assertThat(job(id, "FETCH_BODY")).containsEntry("outcome", "ENDED");
		// 대응표를 고쳐 다시 본문을 받는 유형이 됐다
		jdbc.sql("UPDATE disclosure SET type_code = 'E4' WHERE id = :id").param("id", id).update();

		new AnalysisQueueService(jobs, properties, CLOCK).requeueReclassified(List.of(id));

		assertThat(job(id, "FETCH_BODY")).containsEntry("status", "PENDING");
		assertThat(count("SELECT count(*) FROM analysis_job WHERE stage = 'READ_TABLE'")).isZero();
		respond("20261119000001", BodyFixtures.zip(BodyFixtures.TREASURY_DISPOSAL));
		drain();
		assertThat(count("SELECT count(*) FROM disclosure_body")).isEqualTo(1);
		assertThat(job(id, "READ_TABLE")).containsEntry("status", "PENDING");
	}

	// ── 도우미 ──────────────────────────────────────────────────────

	private DartClient dartClient(String apiKey) {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://dart.test");
		dartServer = MockRestServiceServer.bindTo(builder).build();
		DartProperties dartProperties = new DartProperties(apiKey, "https://dart.test", null, null, 100);
		return new DartClient(builder.build(), dartProperties, RateLimiter.ofDefaults("dart-test"));
	}

	private void respond(String receiptNo, byte[] body) {
		dartServer.expect(requestTo(containsString("/api/document.xml")))
			.andExpect(requestTo(containsString("rcept_no=" + receiptNo)))
			.andRespond(withSuccess(body, MediaType.APPLICATION_OCTET_STREAM));
	}

	/** 본문 받기 쪽의 대기 작업을 모두 처리한다. */
	private int drain() {
		BodyFetchHandler handler = new BodyFetchHandler(dart, bodies, pauses, CLOCK);
		return new AnalysisWorker(jobs, List.of(handler), properties, CLOCK).drain(WorkerGroup.BODY);
	}

	/** 삼성전자의 공시 한 건을 저장하고(제목으로 유형이 붙는다) 본문 받기에 줄 세운다. */
	private long save(String receiptNo, String reportName, String day) {
		disclosures.saveListed(List.of(new Item("00126380", "삼성전자", "005930", "Y", reportName, receiptNo, "삼성전자", day, "")));
		new AnalysisQueueService(jobs, properties, CLOCK).enqueueMissing();
		return jdbc.sql("SELECT id FROM disclosure WHERE source_key = :key").param("key", receiptNo).query(Long.class)
			.single();
	}

	private Map<String, Object> job(long disclosureId, String stage) {
		return jdbc.sql("""
				SELECT status, outcome, attempts::int AS attempts, deferrals::int AS deferrals, run_after
				  FROM analysis_job WHERE disclosure_id = :id AND stage = :stage
				""")
			.param("id", disclosureId).param("stage", stage).query().singleRow();
	}

	private Map<String, Object> lastRun(long disclosureId) {
		return jdbc.sql("SELECT * FROM analysis_run WHERE disclosure_id = :id ORDER BY id DESC LIMIT 1")
			.param("id", disclosureId).query().singleRow();
	}

	private static Instant instant(Object timestamp) {
		return ((java.sql.Timestamp) timestamp).toInstant();
	}

	private long count(String sql) {
		return jdbc.sql(sql).query(Long.class).single();
	}
}
