package com.companyinsight.disclosure;

/**
 * 공시의 호재·악재를 누가 판정했는지.
 * DB의 {@code disclosure.classified_by}에 이름 그대로 저장한다.
 */
public enum ClassifiedBy {

	/** 보고서명 규칙 ({@link DisclosureClassifier}) */
	RULE,

	/** LLM. 제목만으로 방향을 알 수 없어 본문을 읽고 판정한 경우 */
	LLM
}
