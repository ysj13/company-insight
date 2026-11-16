package com.companyinsight.disclosure;

import com.companyinsight.company.QCompany;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * 공시 목록 조회. 판정·종류·회사 조건을 조합하므로 QueryDSL로 한다.
 * 저장은 {@link DisclosureRepository}가 맡는다.
 */
@Repository
public class DisclosureQueryRepository {

	private static final QDisclosure DISCLOSURE = QDisclosure.disclosure;
	private static final QCompany COMPANY = QCompany.company;

	private final JPAQueryFactory query;

	public DisclosureQueryRepository(JPAQueryFactory query) {
		this.query = query;
	}

	/**
	 * 조건에 맞는 공시를 접수일이 최근인 순으로 가져온다. 같은 날짜 안에서는 접수번호가 큰 것이 먼저다.
	 * 접수번호는 제출 경로(금융감독원, 거래소 시장별)마다 번호대가 달라 같은 날 안의 순서는 접수 시각 순서가 아니다.
	 * 접수번호가 유일해서 페이지를 넘겨도 순서가 흔들리지 않는다.
	 *
	 * @param offset 건너뛸 건수
	 * @param limit  최대 건수
	 */
	public List<DisclosureSummary> find(DisclosureSearchCondition condition, long offset, int limit) {
		return query
			.select(Projections.constructor(DisclosureSummary.class, DISCLOSURE.receiptNo, DISCLOSURE.receiptDate,
				COMPANY.name, COMPANY.stockCode, DISCLOSURE.reportName, DISCLOSURE.kind, DISCLOSURE.tone,
				DISCLOSURE.impact, DISCLOSURE.correction, DISCLOSURE.withdrawn))
			.from(DISCLOSURE)
			.join(DISCLOSURE.company, COMPANY)
			.where(kindAndKeyword(condition).and(toneIs(condition.tone())))
			.orderBy(DISCLOSURE.receiptDate.desc(), DISCLOSURE.receiptNo.desc())
			.offset(offset)
			.limit(limit)
			.fetch();
	}

	/**
	 * 판정별 건수. 종류와 회사 조건만 적용하고 판정 조건은 적용하지 않는다.
	 * 판정을 고르기 전에 호재·악재·중립·미판정이 각각 몇 건인지 보여주기 위해서다.
	 *
	 * @return 네 가지 판정 모두에 대한 건수. 없는 판정은 0
	 */
	public Map<ToneFilter, Long> countByTone(DisclosureSearchCondition condition) {
		JPAQuery<Tuple> counting = query.select(DISCLOSURE.tone, DISCLOSURE.count()).from(DISCLOSURE);
		if (condition.keyword() != null) {
			// 회사 조건이 없으면 조인하지 않는다. 조건 없는 첫 화면이 가장 자주 열리고 전체 행을 센다
			counting.join(DISCLOSURE.company, COMPANY);
		}
		List<Tuple> rows = counting.where(kindAndKeyword(condition)).groupBy(DISCLOSURE.tone).fetch();
		Map<ToneFilter, Long> counts = new EnumMap<>(ToneFilter.class);
		for (ToneFilter filter : ToneFilter.values()) {
			counts.put(filter, 0L);
		}
		for (Tuple row : rows) {
			counts.put(ToneFilter.of(row.get(DISCLOSURE.tone)), row.get(DISCLOSURE.count()));
		}
		return counts;
	}

	private static BooleanBuilder kindAndKeyword(DisclosureSearchCondition condition) {
		BooleanBuilder where = new BooleanBuilder();
		if (condition.kind() != null) {
			where.and(DISCLOSURE.kind.eq(condition.kind()));
		}
		if (condition.corpCode() != null) {
			// 외래키 컬럼(disclosure.corp_code)으로 비교하므로 company를 조인하지 않아도 된다
			where.and(DISCLOSURE.company.corpCode.eq(condition.corpCode()));
		}
		if (condition.keyword() != null) {
			// contains·startsWith는 검색어의 %, _를 글자 그대로 찾도록 이스케이프한다
			where.and(COMPANY.name.containsIgnoreCase(condition.keyword())
				.or(COMPANY.stockCode.startsWith(condition.keyword())));
		}
		return where;
	}

	private static BooleanBuilder toneIs(ToneFilter filter) {
		BooleanBuilder where = new BooleanBuilder();
		if (filter == null) {
			return where;
		}
		return switch (filter) {
			case POSITIVE -> where.and(DISCLOSURE.tone.eq(Tone.POSITIVE));
			case NEGATIVE -> where.and(DISCLOSURE.tone.eq(Tone.NEGATIVE));
			case NEUTRAL -> where.and(DISCLOSURE.tone.eq(Tone.NEUTRAL));
			case UNDECIDED -> where.and(DISCLOSURE.tone.isNull());
		};
	}
}
