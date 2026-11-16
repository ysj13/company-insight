package com.companyinsight.company;

import com.companyinsight.common.source.Source;
import java.sql.Date;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
		INSERT INTO company (country, source, source_key, stock_code, name, modify_date)
		VALUES (?, ?, ?, ?, ?, ?)
		ON CONFLICT (source, source_key) DO UPDATE
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
		SELECT id, stock_code, name, market
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

	/** market에는 DART 법인 구분 코드가 들어 있다. 최근 공시가 없는 회사는 NULL이다. */
	private static final RowMapper<CompanySummary> SUMMARY = (rs, i) -> new CompanySummary(rs.getLong("id"),
		rs.getString("stock_code"), rs.getString("name"), Market.fromDartCode(rs.getString("market")).orElse(null));

	private final JdbcTemplate jdbcTemplate;
	private final JdbcClient jdbcClient;

	public CompanyRepository(JdbcTemplate jdbcTemplate, JdbcClient jdbcClient) {
		this.jdbcTemplate = jdbcTemplate;
		this.jdbcClient = jdbcClient;
	}

	/**
	 * DART 고유번호 파일의 회사 목록을 500건씩 묶어 추가·갱신한다. 한국(KR), DART 출처로 저장한다.
	 *
	 * @return 실제로 추가되거나 바뀐 행 수
	 */
	public int upsertAll(List<CorpCode> rows) {
		// 공시 수집도 company를 upsert한다. 둘이 겹칠 때 서로 다른 순서로 행을 잠그면 데드락이 나므로 항상 고유번호 순으로 쓴다.
		List<CorpCode> sorted = rows.stream().sorted(java.util.Comparator.comparing(CorpCode::corpCode)).toList();
		int changed = 0;
		for (int[] batch : jdbcTemplate.batchUpdate(UPSERT, sorted, 500, (ps, row) -> {
			ps.setString(1, Country.KR.name());
			ps.setString(2, Source.DART.name());
			ps.setString(3, row.corpCode());
			ps.setString(4, row.stockCode());
			ps.setString(5, row.name());
			ps.setDate(6, row.modifyDate() == null ? null : Date.valueOf(row.modifyDate()));
		})) {
			for (int count : batch) {
				if (count > 0) {
					changed += count;
				}
			}
		}
		return changed;
	}

	/** 저장된 회사 수. */
	public long count() {
		return jdbcClient.sql("SELECT count(*) FROM company").query(Long.class).single();
	}

	/**
	 * 회사명 일부 또는 종목코드 앞부분으로 찾는다.
	 *
	 * @param keyword 검색어. 앞뒤 공백을 없앤 값이어야 한다.
	 * @return 최대 20건. 정확히 일치 → 앞부분 일치 → 포함 순.
	 */
	public List<CompanySummary> search(String keyword) {
		return jdbcClient.sql(SEARCH)
			.param("q", escapeLike(keyword))
			.param("raw", keyword)
			.param("limit", SEARCH_LIMIT)
			.query(SUMMARY)
			.list();
	}

	/**
	 * 회사 ID로 회사 한 곳을 찾는다.
	 *
	 * @return 없으면 비어 있음
	 */
	public Optional<CompanySummary> findById(long id) {
		return jdbcClient.sql("SELECT id, stock_code, name, market FROM company WHERE id = :id")
			.param("id", id)
			.query(SUMMARY)
			.optional();
	}

	/**
	 * 회사가 지정한 출처에서 쓰는 번호. 출처의 API를 부를 때 쓴다.
	 *
	 * @param id     회사 ID
	 * @param source 출처
	 * @return 출처에서의 회사 번호(DART는 고유번호). 회사가 없거나 다른 출처의 회사면 비어 있음
	 */
	public Optional<String> findSourceKey(long id, Source source) {
		return jdbcClient.sql("SELECT source_key FROM company WHERE id = :id AND source = :source")
			.param("id", id)
			.param("source", source.name())
			.query(String.class)
			.optional();
	}

	/**
	 * 종목코드가 정확히 일치하는 회사의 ID.
	 *
	 * @param stockCode 종목코드
	 * @return 회사 ID. 상장폐지된 회사의 종목코드가 남아 있어 여러 건일 수 있다
	 */
	public List<Long> findIdsByStockCode(String stockCode) {
		return jdbcClient.sql("SELECT id FROM company WHERE stock_code = :stockCode ORDER BY id")
			.param("stockCode", stockCode)
			.query(Long.class)
			.list();
	}

	/** LIKE 특수문자(%, _, \)를 글자 그대로 찾도록 이스케이프. PostgreSQL LIKE의 기본 이스케이프 문자는 \. */
	static String escapeLike(String s) {
		return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}
