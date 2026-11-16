package com.companyinsight.financial;

import com.companyinsight.common.source.Source;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** financial 테이블 접근. 저장은 PostgreSQL의 ON CONFLICT를 쓰기 위해 JDBC로 한다. */
@Repository
public class FinancialRepository {

	/**
	 * 없으면 넣고, 있으면 금액·통화·근거 보고서가 바뀐 경우에만 갱신한다.
	 * 정정 보고서가 나와 출처의 값이 바뀌면 다시 받을 때 반영된다.
	 */
	private static final String UPSERT = """
		INSERT INTO financial (company_id, fiscal_year, report_type, statement_type, account,
		                       amount, cumulative_amount, prior_amount, prior_cumulative_amount,
		                       currency, source, source_key)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT (company_id, fiscal_year, report_type, statement_type, account) DO UPDATE
		   SET amount = EXCLUDED.amount,
		       cumulative_amount = EXCLUDED.cumulative_amount,
		       prior_amount = EXCLUDED.prior_amount,
		       prior_cumulative_amount = EXCLUDED.prior_cumulative_amount,
		       currency = EXCLUDED.currency,
		       source = EXCLUDED.source,
		       source_key = EXCLUDED.source_key,
		       updated_at = now()
		 WHERE (financial.amount, financial.cumulative_amount, financial.prior_amount,
		        financial.prior_cumulative_amount, financial.currency, financial.source, financial.source_key)
		       IS DISTINCT FROM
		       (EXCLUDED.amount, EXCLUDED.cumulative_amount, EXCLUDED.prior_amount,
		        EXCLUDED.prior_cumulative_amount, EXCLUDED.currency, EXCLUDED.source, EXCLUDED.source_key)
		""";

	private static final String FIND_BY_COMPANY = """
		SELECT fiscal_year, report_type, statement_type, account, amount, cumulative_amount,
		       prior_amount, prior_cumulative_amount, currency, source, source_key
		  FROM financial
		 WHERE company_id = :companyId
		""";

	private static final RowMapper<FinancialRecord> RECORD = (rs, i) -> new FinancialRecord(rs.getInt("fiscal_year"),
		ReportType.valueOf(rs.getString("report_type")), StatementType.valueOf(rs.getString("statement_type")),
		AccountType.valueOf(rs.getString("account")), rs.getBigDecimal("amount"),
		rs.getBigDecimal("cumulative_amount"), rs.getBigDecimal("prior_amount"),
		rs.getBigDecimal("prior_cumulative_amount"), rs.getString("currency"), Source.valueOf(rs.getString("source")),
		rs.getString("source_key"));

	/** 같은 회사를 동시에 저장해도 행을 잠그는 순서가 같도록 unique 키 순으로 쓴다. */
	private static final Comparator<FinancialRecord> KEY_ORDER = Comparator.comparingInt(FinancialRecord::fiscalYear)
		.thenComparing(FinancialRecord::reportType)
		.thenComparing(FinancialRecord::statementType)
		.thenComparing(FinancialRecord::account);

	private final JdbcTemplate jdbcTemplate;
	private final JdbcClient jdbcClient;

	public FinancialRepository(JdbcTemplate jdbcTemplate, JdbcClient jdbcClient) {
		this.jdbcTemplate = jdbcTemplate;
		this.jdbcClient = jdbcClient;
	}

	/**
	 * 회사 한 곳의 재무 행을 추가·갱신한다. 여러 번 저장해도 결과가 같다.
	 *
	 * @param companyId 회사 ID
	 * @param records   저장할 행
	 * @return 새로 저장하거나 값이 바뀐 행 수
	 */
	public int upsertAll(long companyId, List<FinancialRecord> records) {
		List<FinancialRecord> sorted = records.stream().sorted(KEY_ORDER).toList();
		int changed = 0;
		for (int[] batch : jdbcTemplate.batchUpdate(UPSERT, sorted, 100, (ps, row) -> {
			ps.setLong(1, companyId);
			ps.setInt(2, row.fiscalYear());
			ps.setString(3, row.reportType().name());
			ps.setString(4, row.statementType().name());
			ps.setString(5, row.account().name());
			ps.setBigDecimal(6, row.amount());
			ps.setBigDecimal(7, row.cumulativeAmount());
			ps.setBigDecimal(8, row.priorAmount());
			ps.setBigDecimal(9, row.priorCumulativeAmount());
			ps.setString(10, row.currency());
			ps.setString(11, row.source().name());
			ps.setString(12, row.sourceKey());
		})) {
			for (int count : batch) {
				if (count > 0) {
					changed += count;
				}
			}
		}
		return changed;
	}

	/**
	 * 회사 한 곳의 재무 행 전체.
	 *
	 * @param companyId 회사 ID
	 * @return 저장된 행. 순서는 정해져 있지 않다
	 */
	public List<FinancialRecord> findByCompany(long companyId) {
		return jdbcClient.sql(FIND_BY_COMPANY).param("companyId", companyId).query(RECORD).list();
	}
}
