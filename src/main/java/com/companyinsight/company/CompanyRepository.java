package com.companyinsight.company;

import java.sql.Date;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * company 테이블 접근. 대량 upsert와 검색은 PostgreSQL 문법(ON CONFLICT, ILIKE)을 그대로 쓰기 위해 JDBC로 한다.
 */
@Repository
public class CompanyRepository {

	private static final int SEARCH_LIMIT = 20;

	/**
	 * 없으면 넣고, 있으면 이름·종목코드·수정일이 바뀐 경우에만 갱신한다.
	 * WHERE 조건 덕분에 바뀌지 않은 행은 UPDATE되지 않아 불필요한 쓰기(죽은 행)가 생기지 않는다.
	 */
	private static final String UPSERT = """
		INSERT INTO company (corp_code, stock_code, name, modify_date)
		VALUES (?, ?, ?, ?)
		ON CONFLICT (corp_code) DO UPDATE
		   SET stock_code = EXCLUDED.stock_code,
		       name = EXCLUDED.name,
		       modify_date = EXCLUDED.modify_date,
		       updated_at = now()
		 WHERE (company.stock_code, company.name, company.modify_date)
		       IS DISTINCT FROM (EXCLUDED.stock_code, EXCLUDED.name, EXCLUDED.modify_date)
		""";

	/**
	 * 정확히 일치 → 앞부분 일치 → 포함 순으로, 같은 순위면 짧은 이름 먼저.
	 * 종목코드는 앞부분 일치로 찾는다.
	 */
	private static final String SEARCH = """
		SELECT corp_code, stock_code, name
		  FROM company
		 WHERE name ILIKE '%' || :q || '%' OR stock_code LIKE :q || '%'
		 ORDER BY CASE
		            WHEN lower(name) = lower(:raw) OR stock_code = :raw THEN 0
		            WHEN name ILIKE :q || '%' THEN 1
		            ELSE 2
		          END,
		          length(name), name
		 LIMIT :limit
		""";

	private final JdbcTemplate jdbcTemplate;
	private final JdbcClient jdbcClient;

	public CompanyRepository(JdbcTemplate jdbcTemplate, JdbcClient jdbcClient) {
		this.jdbcTemplate = jdbcTemplate;
		this.jdbcClient = jdbcClient;
	}

	/** @return 실제로 추가되거나 바뀐 행 수 */
	public int upsertAll(List<CorpCode> rows) {
		int changed = 0;
		for (int[] batch : jdbcTemplate.batchUpdate(UPSERT, rows, 500, (ps, row) -> {
			ps.setString(1, row.corpCode());
			ps.setString(2, row.stockCode());
			ps.setString(3, row.name());
			ps.setDate(4, row.modifyDate() == null ? null : Date.valueOf(row.modifyDate()));
		})) {
			for (int count : batch) {
				if (count > 0) {
					changed += count;
				}
			}
		}
		return changed;
	}

	public long count() {
		return jdbcClient.sql("SELECT count(*) FROM company").query(Long.class).single();
	}

	public List<CompanySummary> search(String keyword) {
		return jdbcClient.sql(SEARCH)
			.param("q", escapeLike(keyword))
			.param("raw", keyword)
			.param("limit", SEARCH_LIMIT)
			.query((rs, i) -> new CompanySummary(rs.getString("corp_code"), rs.getString("stock_code"), rs.getString("name")))
			.list();
	}

	/** LIKE 특수문자(%, _, \)를 글자 그대로 찾도록 이스케이프. PostgreSQL LIKE의 기본 이스케이프 문자는 \. */
	static String escapeLike(String s) {
		return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}
