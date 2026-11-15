package com.companyinsight.common.dart;

/**
 * DART가 오류 상태를 돌려준 경우. 인증키 오류, 호출 한도 초과처럼 다시 시도해도 결과가 같은 오류다.
 * 메시지에 요청 URL을 넣지 않는다. URL에 인증키가 들어 있기 때문이다.
 */
public class DartApiException extends RuntimeException {

	private final String status;

	public DartApiException(String status, String message) {
		super("DART 오류 " + status + ": " + message);
		this.status = status;
	}

	public String status() {
		return status;
	}
}
