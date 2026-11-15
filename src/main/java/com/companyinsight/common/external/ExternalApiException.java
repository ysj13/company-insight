package com.companyinsight.common.external;

/**
 * 외부 API 호출 실패.
 *
 * <p>메시지에 요청 URL이나 헤더를 넣지 않는다. 인증키가 들어 있을 수 있기 때문이다. 같은 이유로 HTTP 라이브러리의
 * 원인 예외를 연결하지 않는다.
 */
public class ExternalApiException extends RuntimeException {

	private final String source;
	private final ErrorKind kind;
	private final String code;

	public ExternalApiException(String source, ErrorKind kind, String code, String message) {
		super(source + " " + kind + " [" + code + "]: " + message);
		this.source = source;
		this.kind = kind;
		this.code = code;
	}

	/** 다시 시도할 수 있는 오류인지에 따라 알맞은 예외 타입을 만든다. */
	public static ExternalApiException of(String source, ErrorKind kind, String code, String message) {
		return kind.retryable()
				? new RetryableExternalApiException(source, kind, code, message)
				: new ExternalApiException(source, kind, code, message);
	}

	public String source() {
		return source;
	}

	public ErrorKind kind() {
		return kind;
	}

	/** 소스의 원래 오류 코드 (예: DART "020", HTTP "429"). */
	public String code() {
		return code;
	}
}
