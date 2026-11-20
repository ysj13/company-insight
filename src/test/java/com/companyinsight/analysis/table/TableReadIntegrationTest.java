package com.companyinsight.analysis.table;

import static org.assertj.core.api.Assertions.assertThat;

import com.companyinsight.TestcontainersConfiguration;
import com.companyinsight.analysis.AnalysisJobRepository;
import com.companyinsight.analysis.AnalysisProperties;
import com.companyinsight.analysis.AnalysisQueueService;
import com.companyinsight.analysis.AnalysisStage;
import com.companyinsight.analysis.AnalysisWorker;
import com.companyinsight.analysis.WorkerGroup;
import com.companyinsight.analysis.body.DisclosureBodyRepository;
import com.companyinsight.common.dart.DisclosureListResponse.Item;
import com.companyinsight.disclosure.DisclosureClassificationService;
import com.companyinsight.disclosure.DisclosureRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 본문 표 읽기 단계를 큐와 실제 PostgreSQL 위에서 확인한다. 본문은 실제로 받아 둔 공시 원문이다({@link TableFixtures}).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TableReadIntegrationTest {

	/** 2026-11-20 10:00 (한국 시간) */
	private static final Instant NOW = Instant.parse("2026-11-20T01:00:00Z");

	private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));

	@Autowired
	AnalysisJobRepository jobs;

	@Autowired
	DisclosureRepository disclosures;

	@Autowired
	DisclosureBodyRepository bodies;

	@Autowired
	DisclosureClassificationService classification;

	@Autowired
	TableReadHandler handler;

	@Autowired
	JdbcClient jdbc;

	private final AnalysisProperties properties = new AnalysisProperties(List.of(), null, null, null);

	@BeforeEach
	@AfterEach
	void clean() {
		jdbc.sql("DELETE FROM disclosure_body").update();
		jdbc.sql("DELETE FROM analysis_run").update();
		jdbc.sql("DELETE FROM analysis_job").update();
		jdbc.sql("DELETE FROM disclosure").update();
		jdbc.sql("DELETE FROM company").update();
	}

	// ── 코드가 방향을 정하는 유형 ────────────────────────────────────

	@Test
	void 잠정실적을_읽어_호재와_크기를_적고_요약으로_넘긴다() {
		long id = save(TableFixtures.PRELIM_UP, "연결재무제표기준영업(잠정)실적(공정공시)");

		assertThat(drain()).isEqualTo(1);

		assertThat(disclosure(id)).containsEntry("tone", "POSITIVE").containsEntry("impact", 3)
			.containsEntry("classified_by", "TABLE");
		assertThat((BigDecimal) disclosure(id).get("impact_ratio")).isEqualByComparingTo("632.1");
		assertThat(job(id, "READ_TABLE")).containsEntry("status", "DONE").containsEntry("outcome", "PASSED");
		assertThat(job(id, "AI_SUMMARY")).containsEntry("status", "PENDING");
		Map<String, Object> run = lastRun(id);
		assertThat(run).containsEntry("pass_reason", "NEEDS_SUMMARY");
		assertThat(run.get("result").toString()).contains("\"영업이익\": \"83,333,818\"", "\"tone\": \"POSITIVE\"",
			"\"impact\": 3", "\"ratio\": 632.1", "\"case\": \"PROFIT_UP\"", "\"unread\": []");
	}

	@Test
	void 중립으로_정해지면_요약_없이_끝나고_비율은_남는다() {
		long id = save(TableFixtures.PRELIM_SMALL, "연결재무제표기준영업(잠정)실적(공정공시)");

		drain();

		assertThat(disclosure(id)).containsEntry("tone", "NEUTRAL").containsEntry("impact", null)
			.containsEntry("classified_by", "TABLE");
		assertThat((BigDecimal) disclosure(id).get("impact_ratio")).isEqualByComparingTo("5.5");
		assertThat(job(id, "READ_TABLE")).containsEntry("status", "DONE").containsEntry("outcome", "ENDED");
		assertThat(count("SELECT count(*) FROM analysis_job")).isEqualTo(1);
	}

	@Test
	void 정정_공시도_새_공시처럼_읽는다() {
		long id = save(TableFixtures.PRELIM_CORRECTION, "[기재정정]연결재무제표기준영업(잠정)실적(공정공시)");

		drain();

		assertThat(disclosure(id)).containsEntry("tone", "POSITIVE").containsEntry("impact", 3);
		assertThat((BigDecimal) disclosure(id).get("impact_ratio")).isEqualByComparingTo("209.17");
	}

	@Test
	void 제3자배정_유상증자는_비율만_적고_AI_판정으로_넘긴다() {
		long id = save(TableFixtures.RIGHTS_THIRD_PARTY, "주요사항보고서(유상증자결정)");

		drain();

		assertThat(disclosure(id)).containsEntry("tone", null).containsEntry("impact", null)
			.containsEntry("classified_by", null);
		assertThat((BigDecimal) disclosure(id).get("impact_ratio")).isEqualByComparingTo("2.4961");
		assertThat(job(id, "READ_TABLE")).containsEntry("outcome", "PASSED");
		assertThat(job(id, "AI_JUDGE")).containsEntry("status", "PENDING");
		assertThat(lastRun(id)).containsEntry("pass_reason", "TONE_BY_AI");
		assertThat(lastRun(id).get("result").toString()).contains("\"증자방식\": \"제3자배정증자\"", "\"case\": \"THIRD_PARTY\"");
	}

	@Test
	void 자회사의_유상증자는_주주배정이어도_AI_판정으로_넘긴다() {
		long id = save(TableFixtures.RIGHTS_SUBSIDIARY, "유상증자결정(종속회사의주요경영사항)");

		drain();

		assertThat(disclosure(id)).containsEntry("tone", null).containsEntry("impact_ratio", null);
		assertThat(job(id, "AI_JUDGE")).containsEntry("status", "PENDING");
		assertThat(lastRun(id).get("result").toString()).contains("\"case\": \"SUBSIDIARY\"", "\"증자방식\": \"주주배정증자\"");
	}

	@Test
	void 방향을_정하는_항목을_읽지_못하면_실패가_아니라_AI_판정으로_넘긴다() {
		// 감사보고서 제출인데 본문에 감사의견 표가 없다 (다른 공시의 본문을 넣었다)
		long id = save("20261119800001", "감사보고서제출", TableFixtures.zip(TableFixtures.CHANGE_UP_101));

		drain();

		assertThat(disclosure(id)).containsEntry("tone", null).containsEntry("classified_by", null);
		assertThat(job(id, "READ_TABLE")).containsEntry("status", "DONE").containsEntry("outcome", "PASSED")
			.containsEntry("attempts", 0);
		assertThat(job(id, "AI_JUDGE")).containsEntry("status", "PENDING");
		assertThat(lastRun(id)).containsEntry("pass_reason", "UNREADABLE");
		assertThat(lastRun(id).get("result").toString()).contains("\"unread\": [\"감사의견\"]");
	}

	@Test
	void 자회사의_공시는_크기를_한_단계_낮춘다() {
		// 실제 공시가 아니다. 감사의견이 의견거절인 감사보고서 제출의 표를 직접 만들었다 (세 회사의 1년치에 없다)
		byte[] zip = zip("""
			<html><body><table>
			<tr><td>구분</td><td>당해 사업연도</td><td>직전사업연도</td></tr>
			<tr><td>가. 감사의견</td><td>의견거절</td><td>적정</td></tr>
			<tr><td>- 계속기업 존속불확실성 사유 해당여부</td><td>해당</td><td>미해당</td></tr>
			</table></body></html>
			""");
		long own = save("20261119800002", "감사보고서제출", zip);
		long subsidiary = save("20261119800003", "감사보고서제출(자회사의주요경영사항)", zip);

		drain();

		assertThat(disclosure(own)).containsEntry("tone", "NEGATIVE").containsEntry("impact", 3);
		assertThat(disclosure(subsidiary)).containsEntry("tone", "NEGATIVE").containsEntry("impact", 2);
	}

	@Test
	void 지주회사가_알린_자회사의_잠정실적은_크기를_한_단계_낮춘다() {
		// HD현대: 영업이익 371.2% 증가는 3인데 자회사의 공시라 2
		long id = save(TableFixtures.PRELIM_OF_SUBSIDIARY, "연결재무제표기준영업(잠정)실적(공정공시)(자회사의주요경영사항)");

		drain();

		assertThat(disclosure(id)).containsEntry("tone", "POSITIVE").containsEntry("impact", 2);
		assertThat((BigDecimal) disclosure(id).get("impact_ratio")).isEqualByComparingTo("371.2");
	}

	@Test
	void 읽는_규칙이_아직_없는_유형은_판정_없이_끝난다() {
		long id = save("20261119000001", "임원ㆍ주요주주특정증권등소유상황보고서", null);

		drain();

		assertThat(disclosure(id)).containsEntry("tone", null);
		assertThat(job(id, "READ_TABLE")).containsEntry("status", "DONE").containsEntry("outcome", "ENDED");
		assertThat(lastRun(id).get("result").toString()).contains("NO_READER");
		assertThat(count("SELECT count(*) FROM analysis_job")).isEqualTo(1);
	}

	// ── 코드가 방향을 정하지 않는 유형 ─────────────────────────────────

	@Test
	void AI가_방향을_정하는_유형은_AI_판정으로_넘긴다() {
		long id = save("20261119000002", "주요사항보고서(자기주식처분결정)", null);

		drain();

		assertThat(job(id, "AI_JUDGE")).containsEntry("status", "PENDING");
		assertThat(lastRun(id)).containsEntry("pass_reason", "TONE_BY_AI");
	}

	@Test
	void 제목으로_호재가_정해진_공시는_판정을_그대로_두고_요약으로_넘긴다() {
		long id = save("20261119800004", "주식소각결정", null);

		drain();

		assertThat(disclosure(id)).containsEntry("tone", "POSITIVE").containsEntry("classified_by", "RULE");
		assertThat(job(id, "AI_SUMMARY")).containsEntry("status", "PENDING");
		assertThat(lastRun(id)).containsEntry("pass_reason", "NEEDS_SUMMARY");
	}

	@Test
	void 제목으로_중립인_정정_공시는_읽을_표가_없어_끝난다() {
		long id = save("20261119800005", "[기재정정]주주총회소집결의", null);

		drain();

		assertThat(disclosure(id)).containsEntry("tone", "NEUTRAL").containsEntry("classified_by", "RULE");
		assertThat(job(id, "READ_TABLE")).containsEntry("outcome", "ENDED");
		assertThat(count("SELECT count(*) FROM analysis_job")).isEqualTo(1);
	}

	@Test
	void 제목_꼬리에_철회가_든_공시는_AI_판정으로_넘긴다() {
		// 제목 단계가 철회·취하 꼬리에 붙이는 속성과 빈 방향을 직접 넣었다
		long id = save("20261119800006", "주식소각결정", null);
		jdbc.sql("UPDATE disclosure SET title_attributes = '{WITHDRAWAL}', tone = NULL, classified_by = NULL WHERE id = :id")
			.param("id", id).update();

		drain();

		assertThat(disclosure(id)).containsEntry("tone", null);
		assertThat(job(id, "AI_JUDGE")).containsEntry("status", "PENDING");
		assertThat(lastRun(id)).containsEntry("pass_reason", "WITHDRAWAL_TAIL");
	}

	@Test
	void 줄_선_뒤에_철회된_공시는_읽지_않고_끝난다() {
		long id = save(TableFixtures.PRELIM_UP, "연결재무제표기준영업(잠정)실적(공정공시)");
		jdbc.sql("UPDATE disclosure SET is_withdrawn = true WHERE id = :id").param("id", id).update();

		drain();

		assertThat(disclosure(id)).containsEntry("tone", null);
		assertThat(job(id, "READ_TABLE")).containsEntry("outcome", "ENDED");
		assertThat(lastRun(id).get("result").toString()).contains("WITHDRAWN");
	}

	@Test
	void 읽어야_하는데_저장한_본문이_없으면_실패로_센다() {
		long id = save("20261119800007", "감사보고서제출", null);

		drain();

		assertThat(job(id, "READ_TABLE")).containsEntry("status", "PENDING").containsEntry("attempts", 1);
		assertThat(lastRun(id)).containsEntry("outcome", "FAILED");
	}

	// ── 다시 돌리기와 재분류 ─────────────────────────────────────────

	@Test
	void 두_번_읽어도_결과가_같다() {
		long id = save(TableFixtures.CHANGE_DOWN_19, "매출액또는손익구조30%(대규모법인은15%)이상변경");
		drain();
		Map<String, Object> first = disclosure(id);

		queue().rerun(List.of(id), AnalysisStage.READ_TABLE);
		drain();

		assertThat(first).containsEntry("tone", "NEGATIVE").containsEntry("impact", 1);
		assertThat(disclosure(id)).isEqualTo(first);
		assertThat(count("SELECT count(*) FROM analysis_run WHERE stage = 'READ_TABLE'")).isEqualTo(2);
	}

	@Test
	void 다시_읽어_방향을_정하지_못하면_앞서_표로_정한_판정을_지운다() {
		long id = save(TableFixtures.CHANGE_DOWN_19, "매출액또는손익구조30%(대규모법인은15%)이상변경");
		drain();
		assertThat(disclosure(id)).containsEntry("classified_by", "TABLE");

		// 읽는 규칙을 고친 것을 흉내 낸다: 같은 공시의 본문을 읽히지 않는 것으로 바꾼다
		bodies.save(id, TableFixtures.zip(TableFixtures.AUDIT_CLEAN), 1, NOW);
		queue().rerun(List.of(id), AnalysisStage.READ_TABLE);
		drain();

		assertThat(disclosure(id)).containsEntry("tone", null).containsEntry("impact", null)
			.containsEntry("classified_by", null).containsEntry("impact_ratio", null);
		assertThat(job(id, "AI_JUDGE")).containsEntry("status", "PENDING");
	}

	@Test
	void 대응표_버전이_올라도_표로_정한_판정은_그대로이고_다시_줄_서지_않는다() {
		long id = save(TableFixtures.PRELIM_DOWN_39, "연결재무제표기준영업(잠정)실적(공정공시)");
		drain();
		jdbc.sql("UPDATE disclosure SET title_rule_version = 0 WHERE id = :id").param("id", id).update();

		assertThat(classification.reclassifyOutdated()).isEqualTo(1);

		assertThat(disclosure(id)).containsEntry("tone", "NEGATIVE").containsEntry("impact", 2)
			.containsEntry("classified_by", "TABLE").containsEntry("type_code", "B1");
		assertThat((BigDecimal) disclosure(id).get("impact_ratio")).isEqualByComparingTo("-39.9");
		assertThat(job(id, "READ_TABLE")).containsEntry("status", "DONE");
	}

	@Test
	void 재분류로_유형이_바뀌면_표로_정한_판정을_비우고_표_읽기를_다시_지난다() {
		long id = save(TableFixtures.PRELIM_DOWN_39, "연결재무제표기준영업(잠정)실적(공정공시)");
		// 큐는 본문 받기를 마친 공시만 표 읽기로 다시 보낸다. 본문 받기가 끝난 줄을 넣는다
		queue().rerun(List.of(id), AnalysisStage.FETCH_BODY);
		jdbc.sql("UPDATE analysis_job SET status = 'DONE', outcome = 'PASSED' WHERE stage = 'FETCH_BODY'").update();
		drain();
		// 옛 대응표가 이 공시를 다른 유형(손익구조 변동)으로 붙였던 것을 흉내 낸다. 지금 대응표는 잠정실적으로 붙인다
		jdbc.sql("UPDATE disclosure SET title_rule_version = 0, type_code = 'B4' WHERE id = :id").param("id", id).update();

		classification.reclassifyOutdated();

		assertThat(disclosure(id)).containsEntry("type_code", "B1").containsEntry("tone", null)
			.containsEntry("impact", null).containsEntry("classified_by", null).containsEntry("impact_ratio", null);
		assertThat(job(id, "READ_TABLE")).containsEntry("status", "PENDING");
	}

	@Test
	void 정정_표에_철회가_적힌_정정_공시는_코드가_방향을_정하지_않는다() {
		// 실제 공시가 아니다. 감사의견을 한정으로 고쳤다가 "취소"가 적힌 정정 표를 붙여 만들었다
		byte[] zip = zip("""
			<html><body>
			<table><tr><td>정정항목</td><td>정정전</td><td>정정후</td></tr>
			<tr><td>가. 감사의견</td><td>적정</td><td>제출 취소</td></tr></table>
			<table>
			<tr><td>구분</td><td>당해 사업연도</td><td>직전사업연도</td></tr>
			<tr><td>가. 감사의견</td><td>한정</td><td>적정</td></tr>
			</table></body></html>
			""");
		long id = save("20261119800008", "[기재정정]감사보고서제출", zip);

		drain();

		assertThat(disclosure(id)).containsEntry("tone", null).containsEntry("classified_by", null);
		assertThat(job(id, "AI_JUDGE")).containsEntry("status", "PENDING");
		assertThat(lastRun(id).get("result").toString()).contains("\"case\": \"CORRECTION_MAY_WITHDRAW\"");
	}

	@Test
	void 표로_판정한_뒤_철회된_공시를_다시_돌리면_표_판정을_지운다() {
		long id = save(TableFixtures.PRELIM_DOWN_39, "연결재무제표기준영업(잠정)실적(공정공시)");
		drain();
		assertThat(disclosure(id)).containsEntry("classified_by", "TABLE");

		jdbc.sql("UPDATE disclosure SET is_withdrawn = true WHERE id = :id").param("id", id).update();
		queue().rerun(List.of(id), AnalysisStage.READ_TABLE);
		drain();

		assertThat(disclosure(id)).containsEntry("tone", null).containsEntry("impact", null).containsEntry("classified_by", null);
	}

	@Test
	void AI가_판정해_둔_공시를_다시_읽어_못_읽으면_AI의_판정과_비율을_그대로_둔다() {
		long id = save("20261119800009", "감사보고서제출", TableFixtures.zip(TableFixtures.CHANGE_UP_101));
		jdbc.sql("UPDATE disclosure SET tone = 'NEGATIVE', impact = 2, impact_ratio = 12.5, classified_by = 'LLM' WHERE id = :id")
			.param("id", id).update();

		drain();

		assertThat(lastRun(id)).containsEntry("pass_reason", "UNREADABLE");
		assertThat(disclosure(id)).containsEntry("tone", "NEGATIVE").containsEntry("impact", 2).containsEntry("classified_by", "LLM");
		assertThat((BigDecimal) disclosure(id).get("impact_ratio")).isEqualByComparingTo("12.5");
	}

	@Test
	void 표를_읽어_방향이_정해지면_AI가_판정해_둔_것을_덮어쓴다() {
		long id = save(TableFixtures.PRELIM_DOWN_39, "연결재무제표기준영업(잠정)실적(공정공시)");
		jdbc.sql("UPDATE disclosure SET tone = 'POSITIVE', impact = 1, classified_by = 'LLM' WHERE id = :id").param("id", id).update();

		drain();

		assertThat(disclosure(id)).containsEntry("tone", "NEGATIVE").containsEntry("impact", 2).containsEntry("classified_by", "TABLE");
	}

	@Test
	void 재분류로_제목_속성이_바뀌면_표_읽기를_다시_지난다() {
		long id = save(TableFixtures.RIGHTS_SUBSIDIARY, "유상증자결정(종속회사의주요경영사항)");
		queue().rerun(List.of(id), AnalysisStage.FETCH_BODY);
		jdbc.sql("UPDATE analysis_job SET status = 'DONE', outcome = 'PASSED' WHERE stage = 'FETCH_BODY'").update();
		drain();
		// 옛 대응표가 자회사 꼬리를 속성으로 떼지 못했던 것을 흉내 낸다
		jdbc.sql("UPDATE disclosure SET title_rule_version = 0, title_attributes = '{}' WHERE id = :id").param("id", id).update();

		classification.reclassifyOutdated();

		assertThat(job(id, "READ_TABLE")).containsEntry("status", "PENDING");
	}

	@Test
	void 재분류로_같은_유형이_제목으로_방향을_정하게_되면_표_판정을_버리고_제목의_방향을_쓴다() {
		// 실제 대응표에는 없는 경우다. 저장한 SQL의 조건을 직접 본다: 유형은 그대로이고 제목이 방향을 정한 분류를 넣는다
		long id = save(TableFixtures.PRELIM_DOWN_39, "연결재무제표기준영업(잠정)실적(공정공시)");
		drain();
		assertThat(disclosure(id)).containsEntry("tone", "NEGATIVE").containsEntry("impact", 2);

		disclosures.updateClassifications(java.util.Map.of(id, new com.companyinsight.disclosure.Classification("B1", null, null,
			"연결재무제표기준영업(잠정)실적", List.of(), com.companyinsight.disclosure.Tone.NEGATIVE,
			com.companyinsight.disclosure.ClassifiedBy.RULE, false, false)), 99);

		assertThat(disclosure(id)).containsEntry("tone", "NEGATIVE").containsEntry("impact", null)
			.containsEntry("classified_by", "RULE");
	}

	// ── 도우미 ──────────────────────────────────────────────────────

	/** 표 읽기 쪽의 대기 작업을 모두 처리한다. */
	private int drain() {
		return new AnalysisWorker(jobs, List.of(handler), properties, CLOCK).drain(WorkerGroup.CODE);
	}

	private AnalysisQueueService queue() {
		return new AnalysisQueueService(jobs, properties, CLOCK);
	}

	/** 받아 둔 실제 공시를 그 접수번호로 저장하고 표 읽기에 줄 세운다. */
	private long save(String receiptNo, String reportName) {
		return save(receiptNo, reportName, TableFixtures.zip(receiptNo));
	}

	/**
	 * SK하이닉스의 공시 한 건을 저장하고(제목으로 유형이 붙는다) 본문을 넣은 뒤 표 읽기에 줄 세운다.
	 *
	 * @param zip 본문 원문. null이면 본문을 넣지 않는다
	 */
	private long save(String receiptNo, String reportName, byte[] zip) {
		disclosures
			.saveListed(List.of(new Item("00164779", "SK하이닉스", "000660", "Y", reportName, receiptNo, "SK하이닉스", "20261119", "")));
		long id = jdbc.sql("SELECT id FROM disclosure WHERE source_key = :key").param("key", receiptNo).query(Long.class).single();
		if (zip != null) {
			bodies.save(id, zip, 1, NOW);
		}
		queue().rerun(List.of(id), AnalysisStage.READ_TABLE);
		return id;
	}

	private Map<String, Object> disclosure(long id) {
		return jdbc.sql("""
				SELECT type_code, tone, impact::int AS impact, impact_ratio, classified_by FROM disclosure WHERE id = :id
				""")
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

	private long count(String sql) {
		return jdbc.sql(sql).query(Long.class).single();
	}

	/** 문서 하나가 든 본문 원문을 만든다. */
	private static byte[] zip(String html) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("20261119800002.html"));
			zip.write(html.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return out.toByteArray();
	}
}
