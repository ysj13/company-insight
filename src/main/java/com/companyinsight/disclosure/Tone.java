package com.companyinsight.disclosure;

/**
 * 소식 한 건이 회사에 좋은 쪽인지 나쁜 쪽인지에 대한 판정.
 * DB의 {@code disclosure.tone}에 이름 그대로 저장한다.
 */
public enum Tone {

	/** 호재 */
	POSITIVE,

	/** 악재 */
	NEGATIVE,

	/** 중립. 정기보고서나 절차성 공시처럼 방향이 없는 소식 */
	NEUTRAL
}
