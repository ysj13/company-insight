package com.companyinsight.disclosure;

import com.companyinsight.common.source.Source;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 대응표(유형, 제목 규칙, 속성 꼬리, 버전)를 DB에서 읽는다. 쓰지는 않는다. 값은 마이그레이션으로만 바뀐다.
 */
@Repository
public class TitleRuleRepository {

	private final JdbcTemplate jdbc;

	public TitleRuleRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 한 출처의 대응표 전체를 읽는다.
	 *
	 * @param source 공시의 출처
	 * @throws IllegalStateException 대응표 버전이 한 줄도 없거나 대응표의 앞뒤가 맞지 않을 때
	 */
	public TitleRuleBook load(Source source) {
		Integer version = jdbc.queryForObject("SELECT max(version) FROM title_rule_version", Integer.class);
		if (version == null) {
			throw new IllegalStateException("대응표 버전(title_rule_version)이 비어 있습니다");
		}
		List<TitleRuleBook.Type> types = jdbc.query("SELECT code, judge_method, default_tone FROM disclosure_type",
			(rs, n) -> new TitleRuleBook.Type(rs.getString("code"), JudgeMethod.valueOf(rs.getString("judge_method")),
				toneOf(rs.getString("default_tone"))));
		List<TitleRuleBook.Rule> rules = jdbc.query("""
			SELECT id, match_kind, pattern, type_code, tone, judge_method, case_code, tail_word
			  FROM title_rule
			 WHERE source = ?
			""",
			(rs, n) -> new TitleRuleBook.Rule(rs.getLong("id"), TitleRuleBook.MatchKind.valueOf(rs.getString("match_kind")),
				rs.getString("pattern"), rs.getString("type_code"), toneOf(rs.getString("tone")),
				rs.getString("judge_method") == null ? null : JudgeMethod.valueOf(rs.getString("judge_method")),
				rs.getString("case_code"), rs.getString("tail_word")),
			source.name());
		List<TitleRuleBook.Attribute> attributes = jdbc.query(
			"SELECT token, attribute FROM title_attribute WHERE source = ?",
			(rs, n) -> new TitleRuleBook.Attribute(rs.getString("token"), rs.getString("attribute")), source.name());
		return new TitleRuleBook(version, types, rules, attributes);
	}

	private static Tone toneOf(String name) {
		return name == null ? null : Tone.valueOf(name);
	}
}
