package com.companyinsight.financial;

import com.companyinsight.common.source.Source;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** financial_raw 테이블 접근. 출처에서 받은 재무 응답의 원본을 보고서 단위로 저장한다. */
@Repository
public class FinancialRawRepository {

	/**
	 * 없으면 넣고, 있으면 받은 시각을 고친다. 응답 내용이 바뀐 경우에만 내용과 수정 시각을 바꾼다.
	 * 돌려주는 값은 내용이 새로 들어갔거나 바뀌었는지다.
	 */
	private static final String UPSERT = """
		INSERT INTO financial_raw AS r (company_id, source, fiscal_year, report_type, payload)
		VALUES (:companyId, :source, :fiscalYear, :reportType, CAST(:payload AS jsonb))
		ON CONFLICT (company_id, source, fiscal_year, report_type) DO UPDATE
		   SET fetched_at = now(),
		       updated_at = CASE WHEN r.payload IS DISTINCT FROM EXCLUDED.payload THEN now() ELSE r.updated_at END,
		       payload = EXCLUDED.payload
		RETURNING (xmax = 0 OR updated_at = now())
		""";

	private static final RowMapper<FinancialRaw> RAW = (rs, i) -> new FinancialRaw(rs.getLong("company_id"),
		Source.valueOf(rs.getString("source")), rs.getInt("fiscal_year"), ReportType.valueOf(rs.getString("report_type")),
		rs.getString("payload"));

	private final JdbcClient jdbcClient;

	public FinancialRawRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/**
	 * 보고서 한 건의 응답 원본을 저장한다. 여러 번 저장해도 결과가 같다.
	 *
	 * @param raw 저장할 원본
	 * @return 새로 저장했거나 내용이 바뀌었으면 true
	 */
	public boolean upsert(FinancialRaw raw) {
		return jdbcClient.sql(UPSERT)
			.param("companyId", raw.companyId())
			.param("source", raw.source().name())
			.param("fiscalYear", raw.fiscalYear())
			.param("reportType", raw.reportType().name())
			.param("payload", raw.payload())
			.query(Boolean.class)
			.single();
	}

	/**
	 * 회사 한 곳의 원본 전체.
	 *
	 * @param companyId 회사 ID
	 * @return 사업연도, 보고서 종류 순
	 */
	public List<FinancialRaw> findByCompany(long companyId) {
		return jdbcClient.sql("""
			SELECT company_id, source, fiscal_year, report_type, payload
			  FROM financial_raw
			 WHERE company_id = :companyId
			 ORDER BY fiscal_year, report_type, source
			""").param("companyId", companyId).query(RAW).list();
	}

	/** 원본이 저장된 회사의 ID. 오름차순. */
	public List<Long> findCompanyIds() {
		return jdbcClient.sql("SELECT DISTINCT company_id FROM financial_raw ORDER BY company_id")
			.query(Long.class)
			.list();
	}
}
