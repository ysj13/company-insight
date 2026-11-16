package com.companyinsight.financial;

/** 전년 같은 기간과 비교한 금액의 변화. */
public enum Change {

	/** 늘었다 */
	INCREASED,

	/** 줄었다 */
	DECREASED,

	/** 같다 */
	UNCHANGED,

	/** 흑자 전환. 전년은 적자, 올해는 흑자 */
	TURNED_TO_PROFIT,

	/** 적자 전환. 전년은 흑자, 올해는 적자 */
	TURNED_TO_LOSS,

	/** 적자 지속. 전년도 올해도 적자 */
	LOSS_CONTINUED
}
