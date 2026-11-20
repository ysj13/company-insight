package com.companyinsight.analysis.table;

import com.companyinsight.analysis.table.BodyTables.Found;
import com.companyinsight.disclosure.Tone;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * 감사보고서 제출(B5)의 표를 읽는다.
 *
 * <p>당해 사업연도의 네 항목을 본다. 연결과 개별(별도) 재무제표의 것이 따로 적혀 있으면 모두 본다.
 * <ul>
 * <li>감사의견: 적정이 아니면(한정, 부적정, 의견거절) 악재
 * <li>계속기업 존속불확실성 사유 해당여부: 해당이면 악재
 * <li>감사의견과 관련 없는 계속기업 존속 불확실성 기재여부: 기재면 악재 (계속기업 불확실성으로 함께 센다)
 * <li>내부회계관리제도 감사(검토)의견 비적정 등 여부: 해당이면 악재
 * </ul>
 * 하나도 해당하지 않으면 중립이다. 여럿에 해당하면 크기는 그중 큰 값이다.
 *
 * <p>정해진 낱말이 아닌 값이 적혀 있으면 추측하지 않고 읽지 못한 것으로 답한다.
 */
@Component
class AuditReportReader implements TableReader {

	static final String TYPE = "B5";

	private static final String OPINION = "감사의견";
	private static final String GOING_CONCERN = "계속기업 존속불확실성 사유 해당여부";
	private static final String GOING_CONCERN_NOTE = "감사의견과 관련 없는 계속기업 존속 불확실성 기재여부";
	private static final String INTERNAL_CONTROL = "내부회계관리제도 감사(검토)의견 비적정 등 여부";

	private static final Set<String> CLEAN_OPINION = Set.of("적정");
	private static final Set<String> NOT_CLEAN_OPINION = Set.of("한정", "부적정", "의견거절");
	private static final Set<String> YES = Set.of("해당", "기재");
	private static final Set<String> NO = Set.of("미해당", "미기재", "해당사항없음", "해당없음");

	private final ImpactProperties.Audit sizes;

	AuditReportReader(ImpactProperties properties) {
		this.sizes = properties.audit();
	}

	@Override
	public Set<String> typeCodes() {
		return Set.of(TYPE);
	}

	@Override
	public TableReading read(ReadTarget target, BodyTables tables) {
		Map<String, String> items = new LinkedHashMap<>();
		List<String> unread = new ArrayList<>();

		List<String> opinions = values(tables, label -> label.equals("감사의견"));
		List<String> goingConcern = values(tables, label -> label.equals("계속기업존속불확실성사유해당여부"));
		List<String> goingConcernNote = values(tables, label -> label.equals("감사의견과관련없는계속기업존속불확실성기재여부"));
		List<String> internalControl = values(tables, label -> label.startsWith("내부회계관리제도") && label.contains("비적정"));
		record(items, OPINION, opinions);
		record(items, GOING_CONCERN, goingConcern);
		record(items, GOING_CONCERN_NOTE, goingConcernNote);
		record(items, INTERNAL_CONTROL, internalControl);

		if (opinions.isEmpty()) {
			unread.add(OPINION);
			return TableReading.unreadable("NO_OPINION", items, unread);
		}
		Boolean opinionNotClean = any(opinions, NOT_CLEAN_OPINION, CLEAN_OPINION);
		Boolean concern = any(goingConcern, YES, NO);
		Boolean concernNote = any(goingConcernNote, YES, NO);
		Boolean control = any(internalControl, YES, NO);
		if (opinionNotClean == null) {
			unread.add(OPINION);
		}
		if (concern == null) {
			unread.add(GOING_CONCERN);
		}
		if (concernNote == null) {
			unread.add(GOING_CONCERN_NOTE);
		}
		if (control == null) {
			unread.add(INTERNAL_CONTROL);
		}
		if (!unread.isEmpty()) {
			return TableReading.unreadable("UNKNOWN_WORD", items, unread);
		}

		int impact = 0;
		List<String> cases = new ArrayList<>();
		if (opinionNotClean) {
			impact = Math.max(impact, sizes.opinionNotClean());
			cases.add("OPINION_NOT_CLEAN");
		}
		if (concern || concernNote) {
			impact = Math.max(impact, sizes.goingConcern());
			cases.add("GOING_CONCERN");
		}
		if (control) {
			impact = Math.max(impact, sizes.internalControl());
			cases.add("INTERNAL_CONTROL");
		}
		if (cases.isEmpty()) {
			return TableReading.neutral("CLEAN", null, items, unread);
		}
		return TableReading.judged(Tone.NEGATIVE, impact, String.join("+", cases), null, items, unread);
	}

	/** 항목 이름이 조건에 맞는 줄마다 당해 사업연도 값(이름 뒤 첫 칸). 값이 빈 줄은 뺀다. */
	private static List<String> values(BodyTables tables, Predicate<String> labelMatches) {
		List<String> values = new ArrayList<>();
		for (Found found : tables.allRows(row -> !row.isEmpty() && labelMatches.test(Cells.label(row.getFirst())))) {
			String value = found.firstValue();
			if (!Cells.isBlank(value)) {
				values.add(value);
			}
		}
		return values;
	}

	/**
	 * 값 가운데 "예"에 해당하는 낱말이 하나라도 있는지.
	 *
	 * @return 정해진 낱말이 아닌 값이 있으면 null
	 */
	private static Boolean any(List<String> values, Set<String> yes, Set<String> no) {
		boolean found = false;
		for (String value : values) {
			String compact = Cells.compact(value);
			if (yes.contains(compact)) {
				found = true;
			}
			else if (!no.contains(compact)) {
				return null;
			}
		}
		return found;
	}

	private static void record(Map<String, String> items, String name, List<String> values) {
		if (!values.isEmpty()) {
			items.put(name, String.join(" / ", values));
		}
	}
}
