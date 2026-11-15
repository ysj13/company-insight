package com.companyinsight.common.dart;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * OpenDART API 호출.
 *
 * <p>인증키는 쿼리 파라미터로 보내야 해서 요청 URL에 들어간다. Spring의 HTTP 예외 메시지는 URL을 포함하므로
 * 여기서 모두 잡아 URL이 없는 예외로 바꾼다. 로그에 인증키가 남지 않게 하기 위해서다.
 *
 * <p>모든 호출은 {@link RateLimiter}를 거친다. DART는 키 하나당 호출 한도가 있고, 폴링·보정·백필이 같은 키를 나눠 쓴다.
 */
public class DartClient {

	private static final Pattern XML_STATUS = Pattern.compile("<status>\\s*(\\d+)\\s*</status>");
	private static final Pattern XML_MESSAGE = Pattern.compile("<message>\\s*(.*?)\\s*</message>", Pattern.DOTALL);
	private static final Pattern JSON_STATUS = Pattern.compile("\"status\"\\s*:\\s*\"(\\d+)\"");
	private static final Pattern JSON_MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"(.*?)\"");
	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

	static final String STATUS_OK = "000";
	/** 조회된 데이터 없음. 오류가 아니라 빈 결과다. */
	static final String STATUS_NO_DATA = "013";
	/** 시스템 점검 중. 잠시 뒤 다시 시도한다. */
	static final String STATUS_MAINTENANCE = "800";

	/** 공시검색 한 페이지 최대 건수 (DART 제한). */
	public static final int MAX_PAGE_COUNT = 100;

	private final RestClient restClient;
	private final DartProperties properties;
	private final RateLimiter rateLimiter;

	public DartClient(RestClient restClient, DartProperties properties, RateLimiter rateLimiter) {
		this.restClient = restClient;
		this.properties = properties;
		this.rateLimiter = rateLimiter;
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
		byte[] body = get("/api/corpCode.xml", Map.of(), byte[].class);
		if (body == null) {
			body = new byte[0];
		}
		if (isZip(body)) {
			return body;
		}
		throw toApiException(new String(body, StandardCharsets.UTF_8));
	}

	/**
	 * 기간 내 전체 공시 목록 한 페이지. 최신 접수번호가 먼저 온다.
	 * 데이터가 없으면(013) 빈 목록을 돌려준다.
	 */
	@Retryable(includes = DartTransientException.class, maxRetries = 2, delay = 2000, multiplier = 2)
	public DisclosureListResponse listDisclosures(LocalDate from, LocalDate to, int pageNo) {
		Map<String, Object> params = Map.of(
			"bgn_de", from.format(YYYYMMDD),
			"end_de", to.format(YYYYMMDD),
			"page_no", pageNo,
			"page_count", MAX_PAGE_COUNT);
		DisclosureListResponse response = get("/api/list.json", params, DisclosureListResponse.class);
		if (response == null) {
			throw new DartTransientException("DART 공시검색 응답이 비어 있음");
		}
		return switch (response.status()) {
			case STATUS_OK -> response;
			case STATUS_NO_DATA -> new DisclosureListResponse(STATUS_NO_DATA, response.message(), pageNo, 0, 0, java.util.List.of());
			case STATUS_MAINTENANCE -> throw new DartTransientException("DART 시스템 점검 중: " + response.message());
			default -> throw new DartApiException(response.status(), response.message());
		};
	}

	private <T> T get(String path, Map<String, Object> params, Class<T> type) {
		try {
			rateLimiter.acquirePermission();
		}
		catch (RequestNotPermitted e) {
			throw new DartTransientException("DART 호출 속도 제한 대기 시간 초과 (" + path + ")");
		}
		try {
			return restClient.get()
				.uri(uri -> {
					uri.path(path).queryParam("crtfc_key", properties.apiKey());
					params.forEach(uri::queryParam);
					return uri.build();
				})
				.retrieve()
				.body(type);
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
		catch (RestClientException e) {
			// 응답 본문 변환 실패 등. 원래 메시지에 URL이 있을 수 있어 종류만 남긴다.
			throw new DartTransientException("DART 응답 처리 실패 (" + path + "): " + e.getClass().getSimpleName());
		}
	}

	static boolean isZip(byte[] body) {
		return body.length >= 4 && body[0] == 'P' && body[1] == 'K' && body[2] == 3 && body[3] == 4;
	}

	private RuntimeException toApiException(String text) {
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
