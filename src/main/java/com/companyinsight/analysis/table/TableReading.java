package com.companyinsight.analysis.table;

import com.companyinsight.disclosure.Tone;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 본문 표를 읽은 결과.
 *
 * @param verdict  방향을 어떻게 끝냈는지
 * @param tone     코드가 정한 방향. {@link Verdict#JUDGED}일 때만 있다
 * @param impact   크기(1 이상). 방향이 호재·악재일 때만 있다
 * @param ratio    크기를 정할 때 본 원래 비율(%). 읽지 못했거나 비율로 정하지 않으면 null
 * @param caseCode 표에서 읽은 경우의 이름 (예: 흑자 전환, 적자 폭 확대). 실행 기록에 남긴다. 없으면 null
 * @param items    읽은 항목. 이름 → 본문에 적힌 글 그대로
 * @param unread   읽으려 했으나 읽지 못한 항목의 이름
 */
record TableReading(Verdict verdict, Tone tone, Integer impact, BigDecimal ratio, String caseCode, Map<String, String> items,
		List<String> unread) {

	/** 방향을 어떻게 끝냈는지. */
	enum Verdict {

		/** 코드가 방향을 정했다 */
		JUDGED,

		/** 표는 읽었고, 읽은 값에 따라 이 공시의 방향은 AI가 정해야 한다 (예: 제3자배정 유상증자) */
		NEEDS_AI,

		/** 방향을 정하는 항목을 읽지 못했다. 서식이 다르다 */
		UNREADABLE
	}

	TableReading {
		items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
		unread = List.copyOf(unread);
		if ((verdict == Verdict.JUDGED) != (tone != null)) {
			throw new IllegalArgumentException("방향은 코드가 정했을 때만 있습니다: " + verdict + ", " + tone);
		}
		if (impact != null && (tone == null || tone == Tone.NEUTRAL || impact < 1)) {
			throw new IllegalArgumentException("크기는 호재·악재일 때만, 1 이상으로 있습니다: " + tone + ", " + impact);
		}
		if (impact == null && tone != null && tone != Tone.NEUTRAL) {
			throw new IllegalArgumentException("호재·악재에는 크기가 있어야 합니다: " + tone);
		}
	}

	/** 중립으로 정했다. */
	static TableReading neutral(String caseCode, BigDecimal ratio, Map<String, String> items, List<String> unread) {
		return new TableReading(Verdict.JUDGED, Tone.NEUTRAL, null, ratio, caseCode, items, unread);
	}

	/** 호재 또는 악재로 정했다. */
	static TableReading judged(Tone tone, int impact, String caseCode, BigDecimal ratio, Map<String, String> items,
			List<String> unread) {
		return new TableReading(Verdict.JUDGED, tone, impact, ratio, caseCode, items, unread);
	}

	/** 표는 읽었지만 방향은 AI가 정한다. */
	static TableReading needsAi(String caseCode, BigDecimal ratio, Map<String, String> items, List<String> unread) {
		return new TableReading(Verdict.NEEDS_AI, null, null, ratio, caseCode, items, unread);
	}

	/** 방향을 정하는 항목을 읽지 못했다. */
	static TableReading unreadable(String caseCode, Map<String, String> items, List<String> unread) {
		return new TableReading(Verdict.UNREADABLE, null, null, null, caseCode, items, unread);
	}

	/** 크기를 바꾼 사본. 방향이 호재·악재일 때만 쓴다. */
	TableReading withImpact(int newImpact) {
		return new TableReading(verdict, tone, newImpact, ratio, caseCode, items, unread);
	}
}
