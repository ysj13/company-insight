package com.companyinsight.financial;

import com.companyinsight.common.source.Source;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
	 * 보고서 한 건의 재무 행을 원본에서 뽑은 결과로 맞춘다. 뽑은 행은 추가·갱신하고,
	 * 같은 보고서의 기존 행 중 원본에 계정 줄이 없는 것은 지운다.
	 *
	 * @param companyId   회사 ID
	 * @param source      원본의 출처. 지우는 대상은 이 출처에서 받아 저장한 행이다
	 * @param fiscalYear  사업연도
	 * @param reportType  보고서 종류
	 * @param records     원본에서 뽑은 행. 모두 이 사업연도, 보고서 종류여야 한다
	 * @param presentKeys 원본에 줄이 있는 계정의 키({@link FinancialRecord#key()}). 여기에 없는 기존 행을 지운다.
	 *                    비어 있으면 아무것도 지우지 않는다
	 * @return 추가·변경한 행 수와 지운 행 수
	 * @throws IllegalArgumentException 다른 보고서의 행이 섞여 있는 경우
	 */
	@Transactional
	public Replaced replaceReport(long companyId, Source source, int fiscalYear, ReportType reportType,
			List<FinancialRecord> records, Set<String> presentKeys) {
		for (FinancialRecord record : records) {
			if (record.fiscalYear() != fiscalYear || record.reportType() != reportType) {
				throw new IllegalArgumentException("다른 보고서의 재무 행이 섞여 있음: " + record.fiscalYear() + " "
						+ record.reportType());
			}
		}
		int upserted = upsertAll(companyId, records);
		if (presentKeys.isEmpty()) {
			return new Replaced(upserted, 0);
		}
		Set<String> keep = new HashSet<>(presentKeys);
		records.forEach(record -> keep.add(record.key()));
		int deleted = jdbcClient.sql("""
			DELETE FROM financial
			 WHERE company_id = :companyId AND source = :source
			   AND fiscal_year = :fiscalYear AND report_type = :reportType
			   AND (statement_type || '/' || account) NOT IN (:keys)
			""")
			.param("companyId", companyId)
			.param("source", source.name())
			.param("fiscalYear", fiscalYear)
			.param("reportType", reportType.name())
			.param("keys", keep)
			.update();
		return new Replaced(upserted, deleted);
	}

	/**
	 * 보고서 한 건을 맞춘 결과.
	 *
	 * @param upserted 새로 저장하거나 값이 바뀐 행 수
	 * @param deleted  원본에 계정 줄이 없어 지운 행 수
	 */
	public record Replaced(int upserted, int deleted) {
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
