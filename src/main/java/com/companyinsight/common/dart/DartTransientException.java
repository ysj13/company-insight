package com.companyinsight.common.dart;

/**
 * 네트워크 오류, 타임아웃, DART 서버 오류(5xx), 시스템 점검처럼 잠시 뒤 다시 시도하면 성공할 수 있는 오류.
 * 원래 예외 메시지에는 인증키가 포함된 URL이 들어 있으므로 원인 예외를 연결하지 않고 종류만 남긴다.
 */
public class DartTransientException extends RuntimeException {

	public DartTransientException(String message) {
		super(message);
	}
}
