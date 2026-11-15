package com.companyinsight.disclosure;

import com.companyinsight.common.dart.DisclosureListResponse.Item;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 공시 저장. 대량 insert와 ON CONFLICT를 쓰기 위해 JDBC로 한다. */
@Repository
public class DisclosureRepository {

	private static final Logger log = LoggerFactory.getLogger(DisclosureRepository.class);

	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

	// 컬럼 길이. 외부 값이 이보다 길면 잘라서 저장한다. 한 건 때문에 페이지 전체 저장이 실패하면 안 된다.
	private static final int REPORT_NAME_MAX = 300;
	private static final int FILER_NAME_MAX = 200;
	private static final int REMARK_MAX = 20;
	private static final int COMPANY_NAME_MAX = 200;

	/**
	 * 공시에 나온 회사를 먼저 맞춘다. 고유번호 동기화 전에 새로 상장한 회사의 공시가 올 수 있어
	 * 없으면 넣고, 있으면 시장 구분과 마지막 공시일만 갱신한다.
	 */
	private static final String UPSERT_COMPANY = """
		INSERT INTO company (corp_code, stock_code, name, market, last_disclosed_on)
		VALUES (?, ?, ?, ?, ?)
		ON CONFLICT (corp_code) DO UPDATE
		   SET market = EXCLUDED.market,
		       last_disclosed_on = GREATEST(company.last_disclosed_on, EXCLUDED.last_disclosed_on),
		       updated_at = now()
		 WHERE company.market IS DISTINCT FROM EXCLUDED.market
		    OR company.last_disclosed_on IS DISTINCT FROM GREATEST(company.last_disclosed_on, EXCLUDED.last_disclosed_on)
		""";

	/** 같은 공시가 다시 오면 아무것도 하지 않는다. 반환값 1이면 새로 저장된 것. */
	private static final String INSERT_DISCLOSURE = """
		INSERT INTO disclosure (rcept_no, corp_code, report_name, filer_name, rcept_date, remark)
		VALUES (?, ?, ?, ?, ?, ?)
		ON CONFLICT (rcept_no) DO NOTHING
		""";

	private final JdbcTemplate jdbc;

	public DisclosureRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 상장사 공시만 저장한다.
	 *
	 * @return 새로 저장된 공시 수
	 */
	@Transactional
	public int saveListed(List<Item> items) {
		List<Item> listed = items.stream().filter(DisclosureRepository::isListed).filter(item -> {
			boolean ok = isStorable(item);
			if (!ok) {
				log.warn("형식이 올바르지 않은 공시를 건너뜁니다: rcept_no={}, corp_code={}, rcept_dt={}", item.receiptNo(),
					item.corpCode(), item.receiptDate());
			}
			return ok;
		}).toList();
		if (listed.isEmpty()) {
			return 0;
		}
		upsertCompanies(listed);

		int saved = 0;
		int[] counts = jdbc.batchUpdate(INSERT_DISCLOSURE, listed, listed.size(), (ps, item) -> {
			ps.setString(1, item.receiptNo());
			ps.setString(2, item.corpCode());
			ps.setString(3, truncate(normalize(item.reportName()), REPORT_NAME_MAX));
			ps.setString(4, truncate(item.filerName(), FILER_NAME_MAX));
			ps.setDate(5, Date.valueOf(parseDate(item.receiptDate())));
			ps.setString(6, truncate(emptyToNull(item.rm()), REMARK_MAX));
		})[0];
		for (int c : counts) {
			if (c > 0) {
				saved += c;
			}
		}
		return saved;
	}


	private void upsertCompanies(List<Item> listed) {
		// 같은 회사가 여러 번 나오면 한 번만. 한 배치에서 같은 키를 두 번 ON CONFLICT 하면 오류가 난다.
		Map<String, Item> latestByCorp = new LinkedHashMap<>();
		for (Item item : listed) {
			latestByCorp.merge(item.corpCode(), item,
				(a, b) -> a.receiptDate().compareTo(b.receiptDate()) >= 0 ? a : b);
		}
		// 기업 목록 동기화와 같은 순서(corp_code)로 써서 데드락을 피한다.
		List<Item> companies = new ArrayList<>(latestByCorp.values());
		companies.sort(java.util.Comparator.comparing(Item::corpCode));
		jdbc.batchUpdate(UPSERT_COMPANY, companies, companies.size(), (ps, item) -> {
			ps.setString(1, item.corpCode());
			ps.setString(2, item.stockCode().trim());
			ps.setString(3, truncate(item.corpName(), COMPANY_NAME_MAX));
			ps.setString(4, item.corpCls());
			ps.setDate(5, Date.valueOf(parseDate(item.receiptDate())));
		});
	}

	/** 유가증권(Y), 코스닥(K), 코넥스(N)이고 종목코드가 있는 회사. */
	static boolean isListed(Item item) {
		String cls = item.corpCls();
		String stock = item.stockCode() == null ? "" : item.stockCode().trim();
		return stock.length() == 6 && ("Y".equals(cls) || "K".equals(cls) || "N".equals(cls));
	}

	/**
	 * 저장에 꼭 필요한 값(접수번호 14자리, 고유번호 8자리 이내, 접수일, 회사명)이 올바른지.
	 * 형식이 틀린 한 건이 같은 페이지의 나머지 공시 저장까지 막지 않도록, 틀린 건은 건너뛰고 경고를 남긴다.
	 */
	static boolean isStorable(Item item) {
		return item.receiptNo() != null && item.receiptNo().length() == 14
				&& item.corpCode() != null && !item.corpCode().isBlank() && item.corpCode().length() <= 8
				&& item.corpName() != null && !item.corpName().isBlank()
				&& isDate(item.receiptDate());
	}

	private static boolean isDate(String yyyymmdd) {
		if (yyyymmdd == null) {
			return false;
		}
		try {
			parseDate(yyyymmdd);
			return true;
		}
		catch (java.time.format.DateTimeParseException e) {
			return false;
		}
	}

	/** 길이에 맞춰 자른다. PostgreSQL 문자열은 NUL 문자를 저장하지 못하므로 함께 지운다. */
	static String truncate(String s, int max) {
		if (s == null) {
			return null;
		}
		String clean = s.indexOf(0) < 0 ? s : s.replace("\u0000", "");
		return clean.length() <= max ? clean : clean.substring(0, max);
	}

	/** DART 공시 제목에는 뒤쪽 공백이 많이 붙어 온다. 연속 공백을 하나로 줄인다. */
	static String normalize(String reportName) {
		return reportName == null ? "" : reportName.strip().replaceAll("\\s+", " ");
	}

	private static LocalDate parseDate(String yyyymmdd) {
		return LocalDate.parse(yyyymmdd, YYYYMMDD);
	}

	private static String emptyToNull(String s) {
		return s == null || s.isBlank() ? null : s.strip();
	}
}
