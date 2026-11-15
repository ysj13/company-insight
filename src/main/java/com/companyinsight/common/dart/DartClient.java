package com.companyinsight.common.dart;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * OpenDART API 호출.
 *
 * <p>인증키는 쿼리 파라미터로 보내야 해서 요청 URL에 들어간다. Spring의 HTTP 예외 메시지는 URL을 포함하므로
 * 여기서 모두 잡아 URL이 없는 예외로 바꾼다. 로그에 인증키가 남지 않게 하기 위해서다.
 */
public class DartClient {

	private static final Pattern XML_STATUS = Pattern.compile("<status>\\s*(\\d+)\\s*</status>");
	private static final Pattern XML_MESSAGE = Pattern.compile("<message>\\s*(.*?)\\s*</message>", Pattern.DOTALL);
	private static final Pattern JSON_STATUS = Pattern.compile("\"status\"\\s*:\\s*\"(\\d+)\"");
	private static final Pattern JSON_MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"(.*?)\"");

	/** 시스템 점검 중. 잠시 뒤 다시 시도한다. */
	private static final String STATUS_MAINTENANCE = "800";

	private final RestClient restClient;
	private final DartProperties properties;

	public DartClient(RestClient restClient, DartProperties properties) {
		this.restClient = restClient;
		this.properties = properties;
	}

	public boolean isConfigured() {
		return properties.hasApiKey();
	}

	/**
	 * 전체 기업 고유번호 파일(zip, 안에 CORPCODE.xml)을 받는다.
	 * 정상이면 zip 바이트를, DART가 오류를 돌려주면 {@link DartApiException}을 던진다.
	 */
	@Retryable(includes = DartTransientException.class, maxRetries = 2, delay = 2000, multiplier = 2)
	public byte[] downloadCorpCodeZip() {
		byte[] body = get("/api/corpCode.xml");
		if (isZip(body)) {
			return body;
		}
		throw toApiException(body);
	}

	private byte[] get(String path) {
		try {
			byte[] body = restClient.get()
				.uri(uri -> uri.path(path).queryParam("crtfc_key", properties.apiKey()).build())
				.retrieve()
				.body(byte[].class);
			return body == null ? new byte[0] : body;
		}
		catch (ResourceAccessException e) {
			throw new DartTransientException("DART 연결 실패 (" + path + "): " + e.getClass().getSimpleName());
		}
		catch (HttpServerErrorException e) {
			throw new DartTransientException("DART 서버 오류 (" + path + "): HTTP " + e.getStatusCode().value());
		}
		catch (HttpClientErrorException e) {
			throw new DartApiException("HTTP" + e.getStatusCode().value(), "요청 오류 (" + path + ")");
		}
	}

	static boolean isZip(byte[] body) {
		return body.length >= 4 && body[0] == 'P' && body[1] == 'K' && body[2] == 3 && body[3] == 4;
	}

	private RuntimeException toApiException(byte[] body) {
		String text = new String(body, StandardCharsets.UTF_8);
		String status = firstGroup(text, XML_STATUS, JSON_STATUS, "unknown");
		String message = firstGroup(text, XML_MESSAGE, JSON_MESSAGE, "알 수 없는 응답");
		if (STATUS_MAINTENANCE.equals(status)) {
			return new DartTransientException("DART 시스템 점검 중: " + message);
		}
		return new DartApiException(status, message);
	}

	private static String firstGroup(String text, Pattern first, Pattern second, String fallback) {
		Matcher m = first.matcher(text);
		if (m.find()) {
			return m.group(1);
		}
		m = second.matcher(text);
		return m.find() ? m.group(1) : fallback;
	}
}
