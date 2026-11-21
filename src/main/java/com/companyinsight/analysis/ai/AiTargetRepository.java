package com.companyinsight.analysis.ai;

import com.companyinsight.disclosure.ClassifiedBy;
import com.companyinsight.disclosure.JudgeMethod;
import com.companyinsight.disclosure.Tone;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * AI 판정·요약 단계가 공시에서 읽는 값의 조회와, AI가 정한 판정·요약의 저장.
 */
@Repository
public class AiTargetRepository {

	/** 본문을 고친 정정 공시인지 (보고서명의 머리말에 [기재정정]) */
	private static final String IS_BODY_CORRECTION = "position('[기재정정]' in disclosure_prefixes(d.report_name)) > 0";

	private final JdbcClient jdbc;

	public AiTargetRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * AI 단계가 공시 한 건에서 읽는 값. 공시의 지금 분류를 읽는다 (작업이 대기하는 동안 바뀌었을 수 있다).
	 *
	 * @return 그 공시가 없으면 비어 있음
	 */
	public Optional<AiTarget> findTarget(long disclosureId) {
		return jdbc.sql("""
				SELECT d.is_withdrawn, d.superseded_by, d.tone, d.classified_by, d.type_code, d.picked_type_code,
				       d.title_attributes, d.normalized_title, d.report_name, d.rcept_date, d.impact_ratio, c.name AS company_name,
				       COALESCE(r.judge_method, t.judge_method) AS judge_method, t.name AS type_name, pt.name AS picked_type_name,
				       %s AS body_correction
				  FROM disclosure d
				  JOIN company c ON c.id = d.company_id
				  LEFT JOIN disclosure_type t ON t.code = d.type_code
				  LEFT JOIN disclosure_type pt ON pt.code = d.picked_type_code
				  LEFT JOIN title_rule r ON r.id = d.title_rule_id
				 WHERE d.id = :id
				""".formatted(IS_BODY_CORRECTION))
			.param("id", disclosureId)
			.query((rs, n) -> new AiTarget(
				disclosureId,
				rs.getBoolean("is_withdrawn"),
				rs.getObject("superseded_by", Long.class),
				rs.getString("tone") == null ? null : Tone.valueOf(rs.getString("tone")),
				rs.getString("classified_by") == null ? null : ClassifiedBy.valueOf(rs.getString("classified_by")),
				rs.getString("type_code"),
				rs.getString("type_name"),
				rs.getString("picked_type_code"),
				rs.getString("picked_type_name"),
				attributes(rs.getArray("title_attributes")),
				rs.getString("normalized_title"),
				rs.getString("report_name"),
				rs.getObject("rcept_date", LocalDate.class),
				rs.getBigDecimal("impact_ratio"),
				rs.getString("company_name"),
				rs.getString("judge_method") == null ? null : JudgeMethod.valueOf(rs.getString("judge_method")),
				rs.getBoolean("body_correction")))
			.optional();
	}

	/**
	 * 이 공시의 마지막 표 읽기 실행이 남긴 것 (읽은 항목, 정정 표 등).
	 *
	 * @return 표 읽기를 끝낸 적이 없으면 비어 있음
	 */
	public Optional<String> lastTableReadResult(long disclosureId) {
		return jdbc.sql("""
				SELECT result::text
				  FROM analysis_run
				 WHERE disclosure_id = :id AND stage = 'READ_TABLE' AND outcome IN ('ENDED', 'PASSED') AND result IS NOT NULL
				 ORDER BY id DESC
				 LIMIT 1
				""")
			.param("id", disclosureId)
			.query(String.class)
			.optional();
	}

	/** 포괄 제목 등에서 AI가 고를 수 있는 유형 (포괄 제목·꼬리 미상·미분류를 뺀 전체). 보여주는 순서대로. */
	public List<TypeChoice> typeChoices() {
		return jdbc.sql("""
				SELECT code, name, default_tone
				  FROM disclosure_type
				 WHERE code NOT IN ('K1', 'K2', 'Z9')
				 ORDER BY sort_order
				""")
			.query((rs, n) -> new TypeChoice(rs.getString("code"), rs.getString("name"),
				rs.getString("default_tone") == null ? null : Tone.valueOf(rs.getString("default_tone"))))
			.list();
	}

	/**
	 * AI가 정한 판정을 공시에 적는다. 그 사이 제목 규칙이나 표 읽기가 판정했으면 적지 않는다(AI가 적은 판정만 덮어쓴다).
	 *
	 * @param tone         방향
	 * @param impact       크기. 중립이면 null
	 * @param ratio        크기를 정할 때 본 원래 비율(%). null이면 이미 적힌 비율을 그대로 둔다
	 * @param classifiedBy 판정 주체. AI가 정했으면 LLM, AI가 "거둔 정정이 아니다"라고 답해 표의 판정을 살렸으면 TABLE
	 * @param pickedType   AI가 고른 유형. 고르지 않았으면 null
	 * @return 적었으면 true
	 */
	public boolean saveJudgment(long disclosureId, Tone tone, Integer impact, BigDecimal ratio, ClassifiedBy classifiedBy,
			String pickedType) {
		return jdbc.sql("""
				UPDATE disclosure
				   SET tone = :tone, impact = :impact, impact_ratio = COALESCE(CAST(:ratio AS numeric), impact_ratio),
				       classified_by = :classifiedBy, picked_type_code = :pickedType, updated_at = now()
				 WHERE id = :id AND (tone IS NULL OR classified_by = 'LLM')
				""")
			.param("tone", tone.name())
			.param("impact", impact)
			.param("ratio", ratio)
			.param("classifiedBy", classifiedBy.name())
			.param("pickedType", pickedType)
			.param("id", disclosureId)
			.update() > 0;
	}

	/**
	 * 판정을 보류한다. AI가 앞서 적어 둔 판정이 있으면 지운다(다시 돌린 판정이 보류로 끝나면 옛 판정이 회사 판단에 남지 않게).
	 * 다른 주체의 판정은 건드리지 않는다. AI가 고른 유형은 적는다.
	 */
	public void hold(long disclosureId, String pickedType) {
		jdbc.sql("""
				UPDATE disclosure
				   SET tone = CASE WHEN classified_by = 'LLM' THEN NULL ELSE tone END,
				       impact = CASE WHEN classified_by = 'LLM' THEN NULL ELSE impact END,
				       classified_by = CASE WHEN classified_by = 'LLM' THEN NULL ELSE classified_by END,
				       picked_type_code = :pickedType, updated_at = now()
				 WHERE id = :id
				""")
			.param("pickedType", pickedType)
			.param("id", disclosureId)
			.update();
	}

	/**
	 * 요약을 적는다(있으면 덮어쓴다). 쓴 실행은 같은 트랜잭션에서 방금 기록한 그 단계의 실행이다.
	 * 공시가 지금 호재·악재일 때만 적는다(요약은 호재·악재 공시에만 있다. AI를 부르는 동안 중립이 됐을 수 있다).
	 *
	 * @param stage 요약을 쓴 단계 (AI_JUDGE 또는 AI_SUMMARY)
	 * @return 적었으면 true
	 */
	public boolean saveSummary(long disclosureId, String stage, String headline, List<String> lines) {
		return jdbc.sql("""
				INSERT INTO disclosure_summary (disclosure_id, headline, lines, written_by, run_id, created_at, updated_at)
				SELECT :id, :headline, :lines, 'AI',
				       (SELECT max(id) FROM analysis_run WHERE disclosure_id = :id AND stage = :stage), now(), now()
				 WHERE EXISTS (SELECT 1 FROM disclosure WHERE id = :id AND tone IN ('POSITIVE', 'NEGATIVE'))
				ON CONFLICT (disclosure_id) DO UPDATE
				   SET headline = EXCLUDED.headline, lines = EXCLUDED.lines, written_by = EXCLUDED.written_by,
				       run_id = EXCLUDED.run_id, updated_at = EXCLUDED.updated_at
				""")
			.param("id", disclosureId)
			.param("headline", headline == null || headline.isBlank() ? null : headline.strip())
			.param("lines", lines.stream().map(String::strip).toArray(String[]::new))
			.param("stage", stage)
			.update() > 0;
	}

	/** AI가 쓴 요약을 지운다. 중립이 됐거나 판정을 보류한 공시는 요약을 보여주지 않는다. 코드가 쓴 요약은 둔다. */
	public void deleteAiSummary(long disclosureId) {
		jdbc.sql("DELETE FROM disclosure_summary WHERE disclosure_id = :id AND written_by = 'AI'")
			.param("id", disclosureId)
			.update();
	}

	private static Set<String> attributes(Array array) throws SQLException {
		if (array == null) {
			return Set.of();
		}
		return Set.of((String[]) array.getArray());
	}

	/**
	 * AI 단계가 공시 한 건에서 읽는 값.
	 *
	 * @param id             공시 ID
	 * @param withdrawn      철회된 공시인지
	 * @param supersededBy   이 공시를 대신하는 뒤 정정 공시. 없으면 null
	 * @param tone           지금 방향. 정해지지 않았으면 null
	 * @param classifiedBy   지금 방향을 정한 주체. 없으면 null
	 * @param typeCode       대응표가 붙인 유형
	 * @param typeName       그 유형의 이름
	 * @param pickedTypeCode AI가 앞서 고른 유형. 없으면 null
	 * @param pickedTypeName 그 유형의 이름
	 * @param attributes     제목 꼬리의 속성 (SUBSIDIARY, WITHDRAWAL 등)
	 * @param title          머리말을 뗀 제목
	 * @param reportName     보고서명 원문
	 * @param filedOn        접수일
	 * @param impactRatio    표 읽기가 적어 둔 원래 비율(%). 없으면 null
	 * @param companyName    회사 이름
	 * @param judgeMethod    방향을 정하는 방법 (규칙 줄의 값이 먼저)
	 * @param bodyCorrection 본문을 고친 정정 공시인지
	 */
	public record AiTarget(long id, boolean withdrawn, Long supersededBy, Tone tone, ClassifiedBy classifiedBy, String typeCode,
			String typeName, String pickedTypeCode, String pickedTypeName, Set<String> attributes, String title,
			String reportName, LocalDate filedOn, BigDecimal impactRatio, String companyName, JudgeMethod judgeMethod,
			boolean bodyCorrection) {
	}

	/**
	 * AI가 고를 수 있는 유형 하나.
	 *
	 * @param code        유형 번호
	 * @param name        이름
	 * @param defaultTone 제목으로 정해지는 기본 방향. 없으면 null
	 */
	public record TypeChoice(String code, String name, Tone defaultTone) {
	}
}
