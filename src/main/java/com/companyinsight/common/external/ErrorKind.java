package com.companyinsight.common.external;

/**
 * 외부 API 오류 분류. 소스마다 응답 형식이 달라도 클라이언트가 이 분류로 바꿔서 던진다.
 * 서비스 코드는 분류만 보고 행동을 정한다. 규칙은 docs/CONVENTIONS.md "외부 API 오류 처리".
 */
public enum ErrorKind {

	/** 네트워크, 타임아웃, 5xx, 점검. 잠시 뒤 다시 하면 된다. */
	TRANSIENT(true, false),

	/** 짧은 시간에 너무 많이 호출. 더 길게 기다린 뒤 다시. */
	RATE_LIMITED(true, false),

	/** 하루 한도 소진. 자정(KST)까지 그 소스를 쉰다. */
	QUOTA_EXCEEDED(false, true),

	/** 키 없음·틀림·만료·IP 차단. 다시 해도 소용없다. */
	AUTH(false, true),

	/** 요청 파라미터 오류. 코드 버그다. */
	BAD_REQUEST(false, false),

	/** 응답 형식을 해석하지 못함. API가 바뀌었을 수 있다. */
	PARSE(false, false),

	/** 분류할 수 없는 응답. */
	UNKNOWN(false, false);

	private final boolean retryable;
	private final boolean pausesSource;

	ErrorKind(boolean retryable, boolean pausesSource) {
		this.retryable = retryable;
		this.pausesSource = pausesSource;
	}

	public boolean retryable() {
		return retryable;
	}

	/** 이 오류가 나면 같은 소스의 다른 호출도 한동안 하지 않는다. */
	public boolean pausesSource() {
		return pausesSource;
	}
}
