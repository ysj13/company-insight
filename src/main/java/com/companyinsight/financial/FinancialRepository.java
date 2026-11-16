package com.companyinsight.financial;

import com.companyinsight.common.source.Source;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** financial 테이블 접근. 출처에서 받은 재무 응답을 보고서 단위로 저장한다. */
@Repository
public class FinancialRepository {

	/**
	 * 없으면 넣고, 있으면 받은 시각을 고친다. 응답 내용이 바뀐 경우에만 수정 시각을 바꾼다.
	 * 내용은 JSON 값으로 비교한다. 공백이나 키 순서만 다른 응답은 같은 내용이다.
	 *
	 * <p>돌려주는 값은 "새로 저장했거나 내용이 바뀌었는가"다. 이 문장을 실행하기 전의 내용(previous)과 비교하므로
	 * 트랜잭션 안에서 불러도 결과가 같다.
	 */
	private static final String UPSERT = """
		WITH previous AS (
		    SELECT payload FROM financial
		     WHERE company_id = :companyId AND source = :source
		       AND fiscal_year = :fiscalYear AND report_type = :reportType
		)
		INSERT INTO financial AS f (company_id, source, fiscal_year, report_type, payload)
		VALUES (:companyId, :source, :fiscalYear, :reportType, CAST(:payload AS jsonb))
		ON CONFLICT (company_id, source, fiscal_year, report_type) DO UPDATE
		   SET fetched_at = now(),
		       updated_at = CASE WHEN f.payload IS DISTINCT FROM EXCLUDED.payload THEN now() ELSE f.updated_at END,
		       payload = EXCLUDED.payload
		RETURNING NOT EXISTS (SELECT 1 FROM previous WHERE previous.payload = f.payload)
		""";

	private static final RowMapper<FinancialReport> REPORT = (rs, i) -> new FinancialReport(rs.getLong("company_id"),
		Source.valueOf(rs.getString("source")), rs.getInt("fiscal_year"), ReportType.valueOf(rs.getString("report_type")),
		rs.getString("payload"));

	private final JdbcClient jdbcClient;

	public FinancialRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/**
	 * 보고서 한 건의 응답을 저장한다. 여러 번 저장해도 결과가 같다.
	 *
	 * @param report 저장할 응답
	 * @return 새로 저장했거나 내용이 바뀌었으면 true
	 * @throws org.springframework.dao.DataIntegrityViolationException DB가 JSON으로 받지 못하는 내용인 경우 (NUL 문자 등)
	 */
	public boolean upsert(FinancialReport report) {
		return jdbcClient.sql(UPSERT)
			.param("companyId", report.companyId())
			.param("source", report.source().name())
			.param("fiscalYear", report.fiscalYear())
			.param("reportType", report.reportType().name())
			.param("payload", report.payload())
			.query(Boolean.class)
			.single();
	}

	/**
	 * 회사 한 곳의 저장된 응답 전체.
	 *
	 * @param companyId 회사 ID
	 * @return 사업연도, 보고서 종류 순
	 */
	public List<FinancialReport> findByCompany(long companyId) {
		return jdbcClient.sql("""
			SELECT company_id, source, fiscal_year, report_type, payload
			  FROM financial
			 WHERE company_id = :companyId
			 ORDER BY fiscal_year, report_type, source
			""").param("companyId", companyId).query(REPORT).list();
	}
}
