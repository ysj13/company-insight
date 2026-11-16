package com.companyinsight.disclosure;

import com.companyinsight.common.dart.DartText;
import com.companyinsight.common.dart.DisclosureListResponse.Item;
import com.companyinsight.common.source.Source;
import com.companyinsight.company.Country;
import com.companyinsight.company.Market;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * DART 공시 저장. 대량 insert와 ON CONFLICT를 쓰기 위해 JDBC로 한다.
 * 회사와 공시를 한국(KR), DART 출처로 저장하고, 중복은 "출처 + 출처의 번호"로 막는다.
 */
@Repository
public class DisclosureRepository {

	private static final Logger log = LoggerFactory.getLogger(DisclosureRepository.class);

	// 컬럼 길이. 외부 값이 이보다 길면 잘라서 저장한다. 한 건 때문에 페이지 전체 저장이 실패하면 안 된다.
	private static final int REPORT_NAME_MAX = 300;
	private static final int FILER_NAME_MAX = 200;
	private static final int REMARK_MAX = 20;
	private static final int COMPANY_NAME_MAX = 200;

	/** 이 저장소가 다루는 출처. company.source, disclosure.source에 넣는 값 */
	private static final String SOURCE = Source.DART.name();

	/**
	 * 공시에 나온 회사를 먼저 맞춘다. 고유번호 동기화 전에 새로 상장한 회사의 공시가 올 수 있어
	 * 없으면 넣고, 있으면 시장 구분과 마지막 공시일만 갱신한다.
	 */
	private static final String UPSERT_COMPANY = """
		INSERT INTO company (country, source, source_key, stock_code, name, market, last_disclosed_on)
		VALUES (?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT (source, source_key) DO UPDATE
		   SET market = EXCLUDED.market,
		       last_disclosed_on = GREATEST(company.last_disclosed_on, EXCLUDED.last_disclosed_on),
		       updated_at = now()
		 WHERE company.market IS DISTINCT FROM EXCLUDED.market
		    OR company.last_disclosed_on IS DISTINCT FROM GREATEST(company.last_disclosed_on, EXCLUDED.last_disclosed_on)
		""";

	/**
	 * 같은 공시(출처와 접수번호가 같은 것)가 다시 오면 아무것도 하지 않는다. 반환값 1이면 새로 저장된 것.
	 * 회사는 출처의 번호(고유번호)로 내부 번호를 찾아 참조한다. 회사가 없으면 NOT NULL 위반으로 실패한다.
	 */
	private static final String INSERT_DISCLOSURE = """
		INSERT INTO disclosure (source, source_key, company_id, report_name, filer_name, rcept_date, remark,
		                        kind, tone, impact, classified_by, rule_version, is_correction, is_withdrawn)
		VALUES (?, ?, (SELECT id FROM company WHERE source = ? AND source_key = ?), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT (source, source_key) DO NOTHING
		""";

	/**
	 * 이미 저장한 공시의 비고가 바뀌었으면 맞춘다. 공시를 낸 뒤에 "정"(정정 있음)이나 "철"(철회)이 붙기 때문이다.
	 * 새로 철회된 공시는 중립으로 되돌린다. 철회가 아니면 판정은 건드리지 않는다(LLM이 한 판정을 지우지 않기 위해).
	 */
	private static final String UPDATE_REMARK = """
		UPDATE disclosure
		   SET remark = ?,
		       updated_at = now(),
		       is_withdrawn = is_withdrawn OR ?,
		       tone = CASE WHEN ? THEN 'NEUTRAL' ELSE tone END,
		       impact = CASE WHEN ? THEN NULL ELSE impact END,
		       classified_by = CASE WHEN ? THEN 'RULE' ELSE classified_by END
		 WHERE source = ?
		   AND source_key = ?
		   AND remark IS DISTINCT FROM ?
		""";

	private static final String SELECT_OUTDATED = """
		SELECT id, report_name, remark
		  FROM disclosure
		 WHERE rule_version < ?
		   AND id > ?
		   AND classified_by IS DISTINCT FROM 'LLM'
		 ORDER BY id
		 LIMIT ?
		""";

	private static final String UPDATE_CLASSIFICATION = """
		UPDATE disclosure
		   SET kind = ?, tone = ?, impact = ?, classified_by = ?, rule_version = ?, is_correction = ?, is_withdrawn = ?,
		       updated_at = now()
		 WHERE id = ?
		   AND classified_by IS DISTINCT FROM 'LLM'
		""";

	private final JdbcTemplate jdbc;
	private final DisclosureClassifier classifier;

	public DisclosureRepository(JdbcTemplate jdbc, DisclosureClassifier classifier) {
		this.jdbc = jdbc;
		this.classifier = classifier;
	}

	/**
	 * 상장사 공시만 저장한다. 저장할 때 제목 규칙으로 분류하고, 이미 있는 공시는 비고만 최신 값으로 맞춘다.
	 *
	 * @return 새로 저장된 공시 수 (비고만 바뀐 공시는 세지 않는다)
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
			ps.setString(1, SOURCE);
			ps.setString(2, item.receiptNo());
			ps.setString(3, SOURCE);
			ps.setString(4, item.corpCode());
			String reportName = reportName(item);
			String remark = remark(item);
			Classification c = classifier.classify(reportName, remark);
			ps.setString(5, reportName);
			ps.setString(6, DartText.clean(item.filerName(), FILER_NAME_MAX));
			ps.setDate(7, Date.valueOf(receiptDate(item)));
			ps.setString(8, remark);
			setClassification(ps, 9, c);
		})[0];
		for (int c : counts) {
			if (c > 0) {
				saved += c;
			}
		}

		// 방금 넣은 행은 비고가 같아 WHERE에서 걸러진다. 전에 저장한 공시 중 비고가 바뀐 것만 갱신된다.
		jdbc.batchUpdate(UPDATE_REMARK, listed, listed.size(), (ps, item) -> {
			String remark = remark(item);
			boolean withdrawn = DisclosureClassifier.isWithdrawn(remark);
			ps.setString(1, remark);
			ps.setBoolean(2, withdrawn);
			ps.setBoolean(3, withdrawn);
			ps.setBoolean(4, withdrawn);
			ps.setBoolean(5, withdrawn);
			ps.setString(6, SOURCE);
			ps.setString(7, item.receiptNo());
			ps.setString(8, remark);
		});
		return saved;
	}

	/**
	 * 지금 규칙보다 옛 버전으로 분류된 공시를 id 순서로 가져온다. LLM이 판정한 공시는 대상이 아니다.
	 *
	 * @param ruleVersion 지금 규칙 버전
	 * @param afterId     이 id보다 큰 행부터 (처음이면 0)
	 * @param limit       최대 건수
	 */
	public List<Outdated> findOutdated(int ruleVersion, long afterId, int limit) {
		return jdbc.query(SELECT_OUTDATED,
			(rs, rowNum) -> new Outdated(rs.getLong("id"), rs.getString("report_name"), rs.getString("remark")),
			ruleVersion, afterId, limit);
	}

	/**
	 * 분류 결과를 저장한다. 그 사이 LLM이 판정한 공시는 건드리지 않는다.
	 *
	 * @param ruleVersion 적용한 규칙 버전
	 */
	@Transactional
	public void updateClassifications(Map<Long, Classification> byId, int ruleVersion) {
		List<Map.Entry<Long, Classification>> entries = new ArrayList<>(byId.entrySet());
		jdbc.batchUpdate(UPDATE_CLASSIFICATION, entries, entries.size(), (ps, entry) -> {
			Classification c = entry.getValue();
			ps.setString(1, c.kind().name());
			ps.setString(2, c.tone() == null ? null : c.tone().name());
			ps.setObject(3, c.impact(), Types.SMALLINT);
			ps.setString(4, c.classifiedBy() == null ? null : c.classifiedBy().name());
			ps.setInt(5, ruleVersion);
			ps.setBoolean(6, c.correction());
			ps.setBoolean(7, c.withdrawn());
			ps.setLong(8, entry.getKey());
		});
	}

	/** kind부터 is_withdrawn까지 분류 컬럼 7개를 채운다. */
	private static void setClassification(PreparedStatement ps, int start, Classification c) throws SQLException {
		ps.setString(start, c.kind().name());
		ps.setString(start + 1, c.tone() == null ? null : c.tone().name());
		ps.setObject(start + 2, c.impact(), Types.SMALLINT);
		ps.setString(start + 3, c.classifiedBy() == null ? null : c.classifiedBy().name());
		ps.setInt(start + 4, DisclosureClassifier.RULE_VERSION);
		ps.setBoolean(start + 5, c.correction());
		ps.setBoolean(start + 6, c.withdrawn());
	}

	/** 저장하는 보고서명. 분류도 이 값으로 한다. */
	private static String reportName(Item item) {
		return DartText.clean(DartText.collapseSpaces(item.reportName()), REPORT_NAME_MAX);
	}

	/** 저장하는 비고 */
	private static String remark(Item item) {
		return DartText.clean(DartText.blankToNull(item.rm()), REMARK_MAX);
	}

	/**
	 * 다시 분류할 공시.
	 *
	 * @param id         공시 ID
	 * @param reportName 보고서명
	 * @param remark     비고
	 */
	public record Outdated(long id, String reportName, String remark) {
	}

	/** 공시에 나온 회사를 추가하거나 시장 구분·마지막 공시일을 갱신한다. 공시보다 먼저 실행해야 공시가 회사의 내부 번호를 찾는다. */
	private void upsertCompanies(List<Item> listed) {
		// 같은 회사가 여러 번 나오면 한 번만. 한 배치에서 같은 키를 두 번 ON CONFLICT 하면 오류가 난다.
		Map<String, Item> latestByCorp = new LinkedHashMap<>();
		for (Item item : listed) {
			latestByCorp.merge(item.corpCode(), item,
				(a, b) -> a.receiptDate().compareTo(b.receiptDate()) >= 0 ? a : b); // yyyyMMdd라 문자열 비교가 날짜 순서와 같다
		}
		// 기업 목록 동기화와 같은 순서(고유번호)로 써서 데드락을 피한다.
		List<Item> companies = new ArrayList<>(latestByCorp.values());
		companies.sort(java.util.Comparator.comparing(Item::corpCode));
		jdbc.batchUpdate(UPSERT_COMPANY, companies, companies.size(), (ps, item) -> {
			ps.setString(1, Country.KR.name());
			ps.setString(2, SOURCE);
			ps.setString(3, item.corpCode());
			ps.setString(4, item.stockCode().trim());
			ps.setString(5, DartText.clean(item.corpName(), COMPANY_NAME_MAX));
			ps.setString(6, item.corpCls());
			ps.setDate(7, Date.valueOf(receiptDate(item)));
		});
	}

	/** 상장 시장({@link Market})에 속하고 종목코드(6자리)가 있는 회사의 공시인지. */
	static boolean isListed(Item item) {
		String stock = item.stockCode() == null ? "" : item.stockCode().trim();
		return stock.length() == 6 && Market.fromDartCode(item.corpCls()).isPresent();
	}

	/**
	 * 저장에 꼭 필요한 값(접수번호 14자리, 고유번호 8자리 이내, 접수일, 회사명)이 올바른지.
	 * 형식이 틀린 한 건이 같은 페이지의 나머지 공시 저장까지 막지 않도록, 틀린 건은 건너뛰고 경고를 남긴다.
	 */
	static boolean isStorable(Item item) {
		return item.receiptNo() != null && item.receiptNo().length() == 14
				&& item.corpCode() != null && !item.corpCode().isBlank() && item.corpCode().length() <= 8
				&& item.corpName() != null && !item.corpName().isBlank()
				&& DartText.parseDate(item.receiptDate()).isPresent();
	}

	/** 접수일. {@link #isStorable(Item)}을 통과한 공시에만 쓴다. */
	private static LocalDate receiptDate(Item item) {
		return DartText.parseDate(item.receiptDate()).orElseThrow();
	}
}
