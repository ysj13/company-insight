package com.companyinsight.analysis.table;

import com.companyinsight.disclosure.ClassifiedBy;
import com.companyinsight.disclosure.JudgeMethod;
import com.companyinsight.disclosure.Tone;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 본문 표 읽기가 공시에서 읽는 값의 조회와, 표로 정한 판정의 저장.
 */
@Repository
public class TableReadRepository {

	private final JdbcClient jdbc;

	public TableReadRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 표 읽기가 공시 한 건에서 읽는 값. 공시의 지금 분류를 읽는다 (작업이 대기하는 동안 바뀌었을 수 있다).
	 *
	 * @return 그 공시가 없으면 비어 있음
	 */
	public Optional<TableTarget> findTarget(long disclosureId) {
		return jdbc.sql("""
				SELECT d.is_withdrawn, d.tone, d.classified_by, d.type_code, d.title_attributes,
				       COALESCE(r.judge_method, t.judge_method) AS judge_method
				  FROM disclosure d
				  LEFT JOIN disclosure_type t ON t.code = d.type_code
				  LEFT JOIN title_rule r ON r.id = d.title_rule_id
				 WHERE d.id = :id
				""")
			.param("id", disclosureId)
			.query((rs, n) -> new TableTarget(
				rs.getBoolean("is_withdrawn"),
				rs.getString("tone") == null ? null : Tone.valueOf(rs.getString("tone")),
				rs.getString("classified_by") == null ? null : ClassifiedBy.valueOf(rs.getString("classified_by")),
				rs.getString("type_code"),
				attributes(rs.getArray("title_attributes")),
				rs.getString("judge_method") == null ? null : JudgeMethod.valueOf(rs.getString("judge_method"))))
			.optional();
	}

	/**
	 * 표로 정한 판정을 공시에 적는다. 같은 값을 다시 적어도 결과가 같다.
	 *
	 * <p>앞선 판정이 누구의 것이든 덮어쓴다. 코드가 방향을 정하는 유형에서는 표를 읽은 결과가 기준이다
	 * (AI가 판정해 둔 공시는 그때 표를 읽지 못했던 것이고, 읽는 규칙을 고쳐 다시 돌렸더니 읽힌 경우다).
	 *
	 * @param tone   방향
	 * @param impact 크기. 중립이면 null
	 * @param ratio  크기를 정할 때 본 원래 비율(%). 없으면 null
	 */
	public void saveJudgment(long disclosureId, Tone tone, Integer impact, BigDecimal ratio) {
		jdbc.sql("""
				UPDATE disclosure
				   SET tone = :tone, impact = :impact, impact_ratio = :ratio, classified_by = 'TABLE', updated_at = now()
				 WHERE id = :id
				""")
			.param("tone", tone.name())
			.param("impact", impact)
			.param("ratio", ratio)
			.param("id", disclosureId)
			.update();
	}

	/**
	 * 코드가 방향을 정하지 못한 공시의 비율만 적는다. 앞선 실행이 표로 정해 둔 판정이 있으면 지운다
	 * (읽는 규칙을 고친 뒤 다시 돌렸을 때 옛 판정이 남지 않게). 다른 주체의 판정은 건드리지 않는다.
	 *
	 * @param ratio 크기를 정할 때 쓸 원래 비율(%). 없으면 null (이때 AI가 판정해 둔 공시의 비율은 그대로 둔다)
	 */
	public void saveRatioOnly(long disclosureId, BigDecimal ratio) {
		jdbc.sql("""
				UPDATE disclosure
				   SET impact_ratio = CASE WHEN CAST(:ratio AS numeric) IS NULL AND classified_by = 'LLM' THEN impact_ratio
				                           ELSE CAST(:ratio AS numeric) END,
				       tone = CASE WHEN classified_by = 'TABLE' THEN NULL ELSE tone END,
				       impact = CASE WHEN classified_by = 'TABLE' THEN NULL ELSE impact END,
				       classified_by = CASE WHEN classified_by = 'TABLE' THEN NULL ELSE classified_by END,
				       updated_at = now()
				 WHERE id = :id
				""")
			.param("ratio", ratio)
			.param("id", disclosureId)
			.update();
	}

	/**
	 * 앞선 실행이 표로 정해 둔 판정이 있으면 지운다. 표 읽기가 이 공시를 더는 판정하지 않게 됐을 때 쓴다
	 * (철회됨, AI가 방향을 정하는 쪽으로 바뀜). 다른 주체의 판정은 건드리지 않는다.
	 */
	public void clearTableJudgment(long disclosureId) {
		jdbc.sql("""
				UPDATE disclosure
				   SET tone = NULL, impact = NULL, classified_by = NULL, updated_at = now()
				 WHERE id = :id AND classified_by = 'TABLE'
				""")
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
	 * 표 읽기가 공시 한 건에서 읽는 값.
	 *
	 * @param withdrawn    철회된 공시인지 (출처의 비고)
	 * @param tone         지금 방향. 아직 정해지지 않았으면 null
	 * @param classifiedBy 지금 방향을 정한 주체. 정해지지 않았으면 null
	 * @param typeCode     유형 번호. 아직 분류하지 않았으면 null
	 * @param attributes   제목 꼬리가 붙인 속성 (SUBSIDIARY, WITHDRAWAL 등)
	 * @param judgeMethod  방향을 정하는 방법 (규칙 줄의 값이 먼저, 없으면 유형의 값). 유형이 없으면 null
	 */
	public record TableTarget(boolean withdrawn, Tone tone, ClassifiedBy classifiedBy, String typeCode, Set<String> attributes,
			JudgeMethod judgeMethod) {
	}
}
