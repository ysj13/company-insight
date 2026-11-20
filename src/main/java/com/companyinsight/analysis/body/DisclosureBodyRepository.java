package com.companyinsight.analysis.body;

import com.companyinsight.common.source.Source;
import com.companyinsight.disclosure.JudgeMethod;
import com.companyinsight.disclosure.Tone;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공시 본문 원문(disclosure_body)의 저장과, 본문 받기가 공시에서 읽는 값의 조회.
 */
@Repository
public class DisclosureBodyRepository {

	private final JdbcClient jdbc;

	public DisclosureBodyRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 본문 받기가 공시 한 건에서 읽는 값. 공시의 지금 분류를 읽는다 (작업이 대기하는 동안 바뀌었을 수 있다).
	 *
	 * @return 그 공시가 없으면 비어 있음
	 */
	public Optional<BodyTarget> findTarget(long disclosureId) {
		return jdbc.sql("""
				SELECT d.source, d.source_key, d.report_name, d.is_withdrawn, d.tone, d.type_code, t.fetch_body,
				       COALESCE(r.judge_method, t.judge_method) AS judge_method
				  FROM disclosure d
				  LEFT JOIN disclosure_type t ON t.code = d.type_code
				  LEFT JOIN title_rule r ON r.id = d.title_rule_id
				 WHERE d.id = :id
				""")
			.param("id", disclosureId)
			.query((rs, n) -> new BodyTarget(
				Source.valueOf(rs.getString("source")),
				rs.getString("source_key"),
				rs.getString("report_name"),
				rs.getBoolean("is_withdrawn"),
				rs.getString("tone") == null ? null : Tone.valueOf(rs.getString("tone")),
				rs.getString("type_code"),
				rs.getBoolean("fetch_body"),
				rs.getString("judge_method") == null ? null : JudgeMethod.valueOf(rs.getString("judge_method"))))
			.optional();
	}

	/**
	 * 이미 저장한 본문의 크기와 파일 수.
	 *
	 * @return 저장한 본문이 없으면 비어 있음
	 */
	public Optional<StoredBody> findStored(long disclosureId) {
		return jdbc.sql("SELECT byte_size, file_count FROM disclosure_body WHERE disclosure_id = :id")
			.param("id", disclosureId)
			.query((rs, n) -> new StoredBody(rs.getInt("byte_size"), rs.getInt("file_count")))
			.optional();
	}

	/**
	 * 저장한 본문 원문(압축 파일 그대로).
	 *
	 * @return 저장한 본문이 없으면 비어 있음
	 */
	public Optional<byte[]> findContent(long disclosureId) {
		return jdbc.sql("SELECT content FROM disclosure_body WHERE disclosure_id = :id")
			.param("id", disclosureId)
			.query((rs, n) -> rs.getBytes("content"))
			.optional();
	}

	/**
	 * 본문 원문을 저장한다. 같은 공시를 다시 저장하면 덮어쓴다 (같은 작업이 두 번 실행돼도 결과가 같다).
	 *
	 * @param content   출처가 준 압축 파일 그대로
	 * @param fileCount 압축 파일 안의 문서 파일 수
	 */
	@Transactional
	public void save(long disclosureId, byte[] content, int fileCount, Instant fetchedAt) {
		jdbc.sql("""
				INSERT INTO disclosure_body (disclosure_id, content, byte_size, file_count, fetched_at)
				VALUES (:id, :content, :byteSize, :fileCount, :fetchedAt)
				ON CONFLICT (disclosure_id) DO UPDATE
				   SET content = EXCLUDED.content, byte_size = EXCLUDED.byte_size, file_count = EXCLUDED.file_count,
				       fetched_at = EXCLUDED.fetched_at
				""")
			.param("id", disclosureId)
			.param("content", content)
			.param("byteSize", content.length)
			.param("fileCount", fileCount)
			.param("fetchedAt", OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC))
			.update();
	}

	/**
	 * 본문 받기가 공시 한 건에서 읽는 값.
	 *
	 * @param source      공시를 받은 출처
	 * @param sourceKey   출처의 공시 번호 (DART는 접수번호)
	 * @param reportName  보고서명 (머리말이 붙은 그대로)
	 * @param withdrawn   철회된 공시인지
	 * @param tone        지금 방향. 아직 정해지지 않았으면 null
	 * @param typeCode    유형 번호. 아직 분류하지 않았으면 null
	 * @param fetchBody   그 유형이 본문을 받는 유형인지. 유형이 없으면 false
	 * @param judgeMethod 방향을 정하는 방법 (규칙 줄의 값이 먼저, 없으면 유형의 값). 유형이 없으면 null
	 */
	public record BodyTarget(Source source, String sourceKey, String reportName, boolean withdrawn, Tone tone,
			String typeCode, boolean fetchBody, JudgeMethod judgeMethod) {
	}

	/**
	 * 저장한 본문의 크기.
	 *
	 * @param byteSize  압축 파일의 크기(바이트)
	 * @param fileCount 압축 파일 안의 문서 파일 수
	 */
	public record StoredBody(int byteSize, int fileCount) {
	}
}
