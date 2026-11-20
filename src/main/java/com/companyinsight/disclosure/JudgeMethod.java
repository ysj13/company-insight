package com.companyinsight.disclosure;

/**
 * 공시의 방향(호재·악재·중립)을 정하는 방법. 유형마다 정해져 있고, 제목 규칙 한 줄이 따로 정할 수 있다.
 * DB의 {@code disclosure_type.judge_method}, {@code title_rule.judge_method}에 이름 그대로 저장한다.
 */
public enum JudgeMethod {

	/** 제목으로 정해진다. 본문을 읽지 않아도 방향이 나온다 */
	TITLE,

	/** 본문 표의 정해진 항목을 코드가 읽어 정한다 */
	CODE,

	/** 본문을 AI가 읽어 정한다 */
	AI
}
