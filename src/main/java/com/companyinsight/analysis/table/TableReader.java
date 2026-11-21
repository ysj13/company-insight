package com.companyinsight.analysis.table;

import com.companyinsight.disclosure.Tone;
import java.util.Set;

/**
 * 한 유형(또는 표가 같은 몇 유형)의 본문 표를 읽는 규칙. 유형마다 하나를 빈으로 등록한다.
 * 규칙이 없는 유형의 공시는 표 읽기에서 읽는 것 없이 지나간다.
 *
 * <p>읽는 규칙은 방향을 추측하지 않는다. 방향을 정하는 항목이 서식과 다르게 적혀 있으면
 * {@link TableReading.Verdict#UNREADABLE}로 답해 AI가 본문을 읽게 한다.
 *
 * <p>제목으로 방향이 정해지는 유형(공급계약, 주식 소각 등)의 규칙은 방향을 정하지 않고 크기만 정한다.
 * 그런 규칙은 {@link ReadTarget#tone()}(제목이 정한 방향)을 그대로 돌려주고, 크기의 하한 아래면 중립으로 답한다.
 */
interface TableReader {

	/** 이 규칙이 읽는 유형 번호. */
	Set<String> typeCodes();

	/**
	 * 본문 표를 읽는다.
	 *
	 * @param target 읽을 공시
	 * @param tables 정정 표를 뺀 본문의 표
	 */
	TableReading read(ReadTarget target, BodyTables tables);

	/**
	 * 제목이 방향을 정한 공시의 크기만 정하는 규칙인지. 그런 규칙은 제목으로 판정하는 유형에서만 쓰고,
	 * 방향을 정하는 규칙은 코드로 판정하는 유형에서만 쓴다(대응표가 바뀌어 갈래가 어긋나면 규칙이 없는 것으로 본다).
	 */
	default boolean sizeOnly() {
		return false;
	}

	/**
	 * 이 유형의 요약을 AI가 아니라 코드가 읽은 항목으로 쓰는지. 그런 유형은 방향이 호재·악재로 정해져도 AI 요약으로 넘기지 않는다.
	 */
	default boolean summaryByCode() {
		return false;
	}

	/**
	 * 읽을 공시에서 규칙이 보는 값.
	 *
	 * @param typeCode   유형 번호. {@link #typeCodes()} 중 하나
	 * @param subsidiary 자회사(종속회사)의 일을 모회사가 알리는 공시인지
	 * @param title      머리말([기재정정] 등)을 뗀 공시 제목. 한 유형에 서식이 여럿일 때 서식을 가린다. 없으면 빈 글
	 * @param tone       제목이 정한 방향(호재 또는 악재). 제목으로 방향이 정해지는 유형에서만 있고, 나머지는 null
	 */
	record ReadTarget(String typeCode, boolean subsidiary, String title, Tone tone) {

		public ReadTarget {
			title = title == null ? "" : title;
		}

		/** 제목이 방향을 정하지 않는 유형에 쓴다. */
		public ReadTarget(String typeCode, boolean subsidiary, String title) {
			this(typeCode, subsidiary, title, null);
		}

		/** 제목을 보지 않는 규칙에 쓴다. */
		public ReadTarget(String typeCode, boolean subsidiary) {
			this(typeCode, subsidiary, "", null);
		}
	}
}
