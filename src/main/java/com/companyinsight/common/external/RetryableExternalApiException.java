package com.companyinsight.common.external;

/** {@link ErrorKind#retryable()}인 오류. {@code @Retryable(includes = ...)}의 대상이 되도록 타입을 나눴다. */
public class RetryableExternalApiException extends ExternalApiException {

	RetryableExternalApiException(String source, ErrorKind kind, String code, String message) {
		super(source, kind, code, message);
	}
}
