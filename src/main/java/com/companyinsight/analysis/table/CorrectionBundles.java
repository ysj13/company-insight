package com.companyinsight.analysis.table;

import com.companyinsight.analysis.body.BodyDocument;
import com.companyinsight.analysis.body.DisclosureBodyReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 정정 공시를 앞 공시(원래 공시나 앞선 정정)와 잇고, 이어진 공시들(묶음)에서 회사 판단에 셀 공시를 정한다.
 *
 * <p>정정 표에는 고친 공시의 제출일이 있다. 거래소 서식("정정관련 공시서류제출일")은 바로 앞 판의 제출일이라 원래 공시일 수도,
 * 앞선 정정일 수도 있다. 금융위원회 서식("정정대상 공시서류의 최초제출일")은 원래 공시의 날이다. 그래서 그날 낸 같은 제목의 공시를
 * 앞 공시로 잇는다. 원래 공시(머리말이 없는 공시)와 본문을 고친 정정([기재정정])만 앞 공시가 될 수 있다
 * (첨부만 고치거나 더한 공시와 철회된 공시는 뺀다).
 *
 * <p>앞 공시를 찾는 순서 (실제 공시에서 본 어긋남을 받는다):
 * <ol>
 * <li>같은 회사, 머리말·공백을 뗀 제목이 같고, 그날 낸 공시
 * <li>없으면 그다음 날 낸 공시 (금융위원회 서식의 최초 제출일이 접수일보다 하루 앞서 적힌 것이 있다)
 * <li>없으면 유형과 속성 꼬리를 뗀 제목이 같은 공시로 1·2를 다시 (정정에 "(임시주주총회)" 같은 꼬리가 더 붙은 것이 있다)
 * </ol>
 * 후보가 여럿이고 서로 다른 묶음이면(같은 날 같은 제목의 다른 계약 등), 정정 표의 "정정 전" 값의 숫자가 모두 본문에 든 후보 하나를
 * 고른다. 그래도 하나로 좁혀지지 않으면 잇지 않는다({@code AMBIGUOUS}).
 *
 * <p>묶음은 앞 공시를 따라 올라간 맨 앞 공시(뿌리)에서 내려가며 모은다. 묶음에서는 가장 늦게 낸 공시(접수번호가 가장 큰 것)가
 * 나머지를 대체한다. 앞선 공시의 판정은 지우지 않고 {@code superseded_by}만 적는다. 회사 판단의 날짜({@code first_filed_on})는
 * 뿌리가 원래 공시면 그 접수일, 뿌리가 정정이면 그 정정이 고친 공시의 제출일이다.
 *
 * <p>한 회사의 묶음을 두 워커가 동시에 고치지 않게 트랜잭션 잠금(회사)을 건다. 표 읽기의 끝을 기록하는 트랜잭션 안에서 부른다.
 */
@Component
public class CorrectionBundles {

	/** 보고서명에서 머리말과 공백을 뗀 제목. 가운뎃점은 DART가 쓰는 글자(ㆍ)로 맞춘다 */
	static final String TITLE_KEY = """
			regexp_replace(regexp_replace(translate(%s, '·・', 'ㆍㆍ'), '[\\s\\u00a0\\u3000]+', '', 'g'), '^(\\[[^]]*\\])+', '')""";

	/** 보고서명 앞의 머리말을 공백 없이 이어 붙인 것. 머리말이 없으면 빈 글 */
	static final String PREFIXES = """
			COALESCE(substring(regexp_replace(%s, '[\\s\\u00a0\\u3000]+', '', 'g') from '^((\\[[^]]*\\])+)'), '')""";

	/** 원래 공시: 머리말이 없거나, 금융당국이 원래 공시에 뒤에 붙이는 머리말뿐이다 */
	static final String IS_ORIGINAL = "replace(replace(" + PREFIXES + ", '[정정명령부과]', ''), '[정정제출요구]', '') = ''";

	/** 본문을 고친 정정 공시 */
	static final String IS_BODY_CORRECTION = "position('[기재정정]' in " + PREFIXES + ") > 0";

	/** 트랜잭션 잠금의 첫 번호. 다른 잠금과 겹치지 않게 정한 값 */
	private static final int LOCK_SPACE = 18_001;

	/** 앞 공시를 거슬러 올라가는 최대 단계. 사슬이 이보다 길면 데이터가 잘못된 것이다 */
	private static final int MAX_DEPTH = 500;

	/** "정정 전" 값에서 견줄 낱말: 숫자가 든 4글자 이상 */
	private static final Pattern TOKEN_SPLIT = Pattern.compile("[\\s\\u00a0\\u3000:：()\\[\\]~]+");

	/** 견줄 낱말의 최대 개수 */
	private static final int TOKENS_MAX = 20;

	private static final Pattern SPACES = Pattern.compile("[\\s\\u00a0\\u3000]+");

	private final JdbcClient jdbc;
	private final DisclosureBodyReader bodies;

	public CorrectionBundles(JdbcClient jdbc, DisclosureBodyReader bodies) {
		this.jdbc = jdbc;
		this.bodies = bodies;
	}

	/**
	 * 정정 공시에서 읽은 날로 앞 공시를 찾아 잇고, 그 공시가 들었던 묶음과 새로 든 묶음을 다시 정한다.
	 * 같은 값으로 다시 불러도 결과가 같다.
	 *
	 * @param disclosureId     본문을 고친 정정 공시
	 * @param correctedFiledOn 정정 표에서 읽은, 고친 공시의 제출일. 읽지 못했으면 null. 정정 공시의 접수일보다 늦으면
	 *                         잘못 적힌 것으로 보고 읽지 못한 것과 같이 다룬다
	 * @param beforeValues     정정 표의 "정정 전" 칸들. 앞 공시 후보가 여럿일 때 고르는 데 쓴다
	 */
	public void link(long disclosureId, LocalDate correctedFiledOn, List<String> beforeValues) {
		Node node = node(disclosureId);
		jdbc.sql("SELECT pg_advisory_xact_lock(:space, :companyId)")
			.param("space", LOCK_SPACE)
			.param("companyId", (int) node.companyId())
			.query((rs, n) -> 1)
			.list();
		long oldRoot = root(disclosureId);
		LocalDate date = correctedFiledOn == null || correctedFiledOn.isAfter(node.receivedOn()) ? null : correctedFiledOn;
		Resolution resolution = date == null ? new Resolution("NO_DATE", null) : resolve(node, date, beforeValues);
		jdbc.sql("""
				UPDATE disclosure
				   SET corrected_filed_on = :date, corrects_id = :corrects, correction_link = :link, updated_at = now()
				 WHERE id = :id
				""")
			.param("date", date)
			.param("corrects", resolution.predecessor())
			.param("link", resolution.link())
			.param("id", disclosureId)
			.update();
		recompute(root(oldRoot));
		recompute(root(disclosureId));
	}

	/** 앞 공시를 찾는다. */
	private Resolution resolve(Node node, LocalDate date, List<String> beforeValues) {
		List<Candidate> candidates = List.of();
		for (boolean sameTitle : new boolean[] {true, false}) {
			for (LocalDate day : List.of(date, date.plusDays(1))) {
				if (day.isAfter(node.receivedOn())) {
					continue;
				}
				candidates = candidates(node, day, sameTitle);
				if (!candidates.isEmpty()) {
					break;
				}
			}
			if (!candidates.isEmpty()) {
				break;
			}
		}
		if (candidates.isEmpty()) {
			return new Resolution("NO_PREDECESSOR", null);
		}
		Optional<Candidate> one = oneBundle(candidates);
		if (one.isPresent()) {
			return new Resolution("LINKED", one.get().id());
		}
		List<String> tokens = tokens(beforeValues);
		if (tokens.isEmpty()) {
			return new Resolution("AMBIGUOUS", null);
		}
		List<Candidate> matched = candidates.stream().filter(candidate -> contains(candidate.id(), tokens)).toList();
		if (matched.isEmpty()) {
			return new Resolution("AMBIGUOUS", null);
		}
		return oneBundle(matched).map(c -> new Resolution("LINKED", c.id())).orElse(new Resolution("AMBIGUOUS", null));
	}

	/** 후보가 모두 한 묶음이면 그중 가장 늦게 낸 것. 여러 묶음이면 비어 있음. */
	private Optional<Candidate> oneBundle(List<Candidate> candidates) {
		Set<Long> roots = new LinkedHashSet<>();
		for (Candidate candidate : candidates) {
			roots.add(root(candidate.id()));
		}
		if (roots.size() != 1) {
			return Optional.empty();
		}
		return candidates.stream().max(Comparator.comparing(Candidate::receiptNo));
	}

	/**
	 * 앞 공시 후보: 같은 회사가 그날 이 정정보다 먼저 낸, 원래 공시나 본문을 고친 정정.
	 *
	 * @param sameTitle true면 머리말·공백을 뗀 제목이 같은 것, false면 유형과 속성 꼬리를 뗀 제목이 같은 것
	 */
	private List<Candidate> candidates(Node node, LocalDate day, boolean sameTitle) {
		String titleMatch = sameTitle ? TITLE_KEY.formatted("d.report_name") + " = " + TITLE_KEY.formatted(":reportName")
				: "d.type_code = :typeCode AND d.normalized_title = :normalizedTitle";
		if (!sameTitle && (node.typeCode() == null || node.normalizedTitle() == null)) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT d.id, d.source_key
				  FROM disclosure d
				 WHERE d.company_id = :companyId AND d.rcept_date = :day AND d.id <> :id AND d.source_key < :receiptNo
				   AND NOT d.is_withdrawn AND (%s OR %s) AND %s
				 ORDER BY d.source_key
				""".formatted(IS_ORIGINAL.formatted("d.report_name"), IS_BODY_CORRECTION.formatted("d.report_name"), titleMatch))
			.param("companyId", node.companyId())
			.param("day", day)
			.param("id", node.id())
			.param("receiptNo", node.receiptNo())
			.param("reportName", node.reportName())
			.param("typeCode", node.typeCode())
			.param("normalizedTitle", node.normalizedTitle())
			.query((rs, n) -> new Candidate(rs.getLong("id"), rs.getString("source_key")))
			.list();
	}

	/** "정정 전" 칸들에서 견줄 낱말: 숫자가 든 4글자 이상(끝의 단위는 뗀다). 공백을 없애고 견준다. */
	static List<String> tokens(Collection<String> beforeValues) {
		Set<String> tokens = new LinkedHashSet<>();
		for (String value : beforeValues) {
			for (String token : TOKEN_SPLIT.split(value == null ? "" : value)) {
				// 단위(%, 원, 주)는 본문에서 따로 된 칸에 적히기도 해 뗀다. 잘린 글("…")은 견주지 않는다
				String trimmed = token.replaceAll("^[.,]+|[.,%원주]+$", "");
				if (trimmed.length() >= 4 && trimmed.chars().anyMatch(Character::isDigit) && !trimmed.contains("…")
						&& tokens.size() < TOKENS_MAX) {
					tokens.add(trimmed);
				}
			}
		}
		return List.copyOf(tokens);
	}

	/** 후보의 저장한 본문에 낱말이 모두 들었는지. 본문이 없으면 false. */
	private boolean contains(long candidateId, List<String> tokens) {
		Optional<List<BodyDocument>> documents = bodies.read(candidateId);
		if (documents.isEmpty()) {
			return false;
		}
		StringBuilder text = new StringBuilder();
		documents.get().forEach(document -> text.append(SPACES.matcher(document.text()).replaceAll("")));
		String compact = text.toString();
		return tokens.stream().allMatch(compact::contains);
	}

	/** 앞 공시를 따라 올라간 맨 앞 공시의 id. */
	private long root(long disclosureId) {
		return jdbc.sql("""
				WITH RECURSIVE up (id, corrects_id, depth) AS (
				    SELECT id, corrects_id, 0 FROM disclosure WHERE id = :id
				    UNION ALL
				    SELECT d.id, d.corrects_id, up.depth + 1 FROM disclosure d JOIN up ON d.id = up.corrects_id WHERE up.depth < :max
				)
				SELECT id FROM up ORDER BY depth DESC LIMIT 1
				""")
			.param("id", disclosureId)
			.param("max", MAX_DEPTH)
			.query(Long.class)
			.single();
	}

	/** 뿌리에서 내려가며 묶음을 모아, 대체 관계와 최초 제출일을 다시 적는다. */
	private void recompute(long root) {
		List<Member> members = jdbc.sql("""
				WITH RECURSIVE down (id, depth) AS (
				    SELECT id, 0 FROM disclosure WHERE id = :root
				    UNION ALL
				    SELECT d.id, down.depth + 1 FROM disclosure d JOIN down ON d.corrects_id = down.id WHERE down.depth < :max
				)
				SELECT d.id, d.source_key, d.rcept_date, d.corrected_filed_on, (%s) AS original
				  FROM disclosure d
				 WHERE d.id IN (SELECT id FROM down)
				 ORDER BY d.source_key, d.id
				""".formatted(IS_ORIGINAL.formatted("d.report_name")))
			.param("root", root)
			.param("max", MAX_DEPTH)
			.query((rs, n) -> new Member(rs.getLong("id"), rs.getObject("rcept_date", LocalDate.class),
					rs.getObject("corrected_filed_on", LocalDate.class), rs.getBoolean("original")))
			.list();
		Member first = members.stream().filter(m -> m.id() == root).findFirst().orElseThrow();
		LocalDate firstFiledOn = first.original() ? (members.size() > 1 ? first.receivedOn() : null) : first.correctedFiledOn();
		List<Long> ids = new ArrayList<>(members.stream().map(Member::id).toList());
		if (members.size() == 1) {
			jdbc.sql("""
					UPDATE disclosure SET bundle_id = NULL, superseded_by = NULL, first_filed_on = :first, updated_at = now()
					 WHERE id = :id
					""")
				.param("first", firstFiledOn)
				.param("id", root)
				.update();
			return;
		}
		long latest = members.getLast().id();
		jdbc.sql("""
				UPDATE disclosure
				   SET bundle_id = :root, first_filed_on = :first, superseded_by = CASE WHEN id = :latest THEN NULL ELSE :latest END,
				       updated_at = now()
				 WHERE id IN (:ids)
				""")
			.param("root", root)
			.param("first", firstFiledOn)
			.param("latest", latest)
			.param("ids", ids)
			.update();
	}

	private Node node(long disclosureId) {
		return jdbc.sql("""
				SELECT id, company_id, source_key, rcept_date, report_name, type_code, normalized_title FROM disclosure WHERE id = :id
				""")
			.param("id", disclosureId)
			.query((rs, n) -> new Node(rs.getLong("id"), rs.getLong("company_id"), rs.getString("source_key"),
					rs.getObject("rcept_date", LocalDate.class), rs.getString("report_name"), rs.getString("type_code"),
					rs.getString("normalized_title")))
			.optional()
			.orElseThrow(() -> new IllegalStateException("공시가 없습니다: " + disclosureId));
	}

	/**
	 * 공시 한 건의 묶음 상태. 테스트와 확인에 쓴다.
	 *
	 * @return 공시가 없으면 비어 있음
	 */
	public Optional<BundleState> find(long disclosureId) {
		return jdbc.sql("""
				SELECT corrected_filed_on, corrects_id, correction_link, bundle_id, superseded_by, first_filed_on
				  FROM disclosure WHERE id = :id
				""")
			.param("id", disclosureId)
			.query((rs, n) -> new BundleState(rs.getObject("corrected_filed_on", LocalDate.class),
					(Long) rs.getObject("corrects_id"), rs.getString("correction_link"), (Long) rs.getObject("bundle_id"),
					(Long) rs.getObject("superseded_by"), rs.getObject("first_filed_on", LocalDate.class)))
			.optional();
	}

	/**
	 * 공시 한 건의 묶음 상태.
	 *
	 * @param correctedFiledOn 정정 표에서 읽은, 고친 공시의 제출일
	 * @param correctsId       이 정정이 고친 앞 공시의 id
	 * @param correctionLink   앞 공시를 찾은 결과
	 * @param bundleId         묶음 번호 (뿌리의 id)
	 * @param supersededBy     이 공시를 대체한 공시의 id
	 * @param firstFiledOn     회사 판단에 쓰는 최초 제출일
	 */
	public record BundleState(LocalDate correctedFiledOn, Long correctsId, String correctionLink, Long bundleId,
			Long supersededBy, LocalDate firstFiledOn) {
	}

	private record Node(long id, long companyId, String receiptNo, LocalDate receivedOn, String reportName, String typeCode,
			String normalizedTitle) {
	}

	private record Candidate(long id, String receiptNo) {
	}

	private record Member(long id, LocalDate receivedOn, LocalDate correctedFiledOn, boolean original) {
	}

	private record Resolution(String link, Long predecessor) {
	}
}
