package com.companyinsight.analysis.body;

/**
 * 받은 본문 파일을 풀거나 읽지 못했다. 메시지에는 파일의 내용을 넣지 않는다.
 */
public class BodyFormatException extends RuntimeException {

	public BodyFormatException(String message) {
		super(message);
	}
}
