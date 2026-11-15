package com.companyinsight.common.dart;

import com.companyinsight.common.external.ErrorKind;
import com.companyinsight.common.external.ExternalApiException;
import com.companyinsight.common.external.RetryableExternalApiException;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
 * <p>DART는 오류도 HTTP 200으로 주고 본문의 status로 구분한다. 응답을 {@link ErrorKind}로 바꿔
 * {@link ExternalApiException}으로 던진다. 매핑은 {@link #classify(String)}.
 *
 * <p>인증키는 쿼리 파라미터로 보내야 해서 요청 URL에 들어간다. Spring의 HTTP 예외 메시지는 URL을 포함하므로
 * 여기서 모두 잡아 URL이 없는 예외로 바꾼다.
 *
 * <p>모든 호출은 {@link RateLimiter}를 거친다. 폴링·보정·백필이 같은 키를 나눠 쓴다.
 */
public class DartClient {

	public static final String SOURCE = "DART";

	private static final Pattern XML_STATUS = Pattern.compile("<status>\\s*(\\d+)\\s*</status>");
	private static final Pattern XML_MESSAGE = Pattern.compile("<message>\\s*(.*?)\\s*</message>", Pattern.DOTALL);
	private static final Pattern JSON_STATUS = Pattern.compile("\"status\"\\s*:\\s*\"(\\d+)\"");
	private static final Pattern JSON_MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"(.*?)\"");
	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

	static final String STATUS_OK = "000";

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
	 * DART 상태 코드 → 오류 분류. null이면 "데이터 없음"으로 빈 결과 처리.
	 * 코드표: https://opendart.fss.or.kr 개발가이드 "메시지 설명"
	 */
	static ErrorKind classify(String status) {
		return switch (status) {
			case "013", "014" -> null; // 조회된 데이터 없음, 파일 없음
			case "020", "021" -> ErrorKind.QUOTA_EXCEEDED; // 요청 제한 초과, 조회 가능 회사 수 초과
			case "010", "011", "012", "901" -> ErrorKind.AUTH; // 미등록 키, 사용 불가 키, 접근 불가 IP, 보유기간 만료
			case "100", "101" -> ErrorKind.BAD_REQUEST; // 필드 오류, 부적절한 접근
			case "800", "900" -> ErrorKind.TRANSIENT; // 시스템 점검, 정의되지 않은 오류
			default -> ErrorKind.UNKNOWN;
		};
	}

	/** 전체 기업 고유번호 파일(zip, 안에 CORPCODE.xml)을 받는다. */
	@Retryable(includes = RetryableExternalApiException.class, maxRetries = 2, delay = 2000, multiplier = 2, jitter = 500)
	public byte[] downloadCorpCodeZip() {
		byte[] body = get("/api/corpCode.xml", Map.of(), byte[].class);
		if (body == null) {
			body = new byte[0];
		}
		if (isZip(body)) {
			return body;
		}
		String text = new String(body, StandardCharsets.UTF_8);
		String status = firstGroup(text, XML_STATUS, JSON_STATUS, "unknown");
		String message = firstGroup(text, XML_MESSAGE, JSON_MESSAGE, "zip이 아닌 응답");
		ErrorKind kind = classify(status);
		throw ExternalApiException.of(SOURCE, kind == null ? ErrorKind.UNKNOWN : kind, status, message);
	}

	/** 기간 내 전체 공시 목록 한 페이지. 최신 접수번호가 먼저 온다. 데이터가 없으면 빈 목록. */
	@Retryable(includes = RetryableExternalApiException.class, maxRetries = 2, delay = 2000, multiplier = 2, jitter = 500)
	public DisclosureListResponse listDisclosures(LocalDate from, LocalDate to, int pageNo) {
		Map<String, Object> params = Map.of(
			"bgn_de", from.format(YYYYMMDD),
			"end_de", to.format(YYYYMMDD),
			"page_no", pageNo,
			"page_count", MAX_PAGE_COUNT);
		DisclosureListResponse response = get("/api/list.json", params, DisclosureListResponse.class);
		if (response == null || response.status() == null) {
			throw ExternalApiException.of(SOURCE, ErrorKind.PARSE, "empty", "공시검색 응답이 비어 있음");
		}
		if (STATUS_OK.equals(response.status())) {
			return response;
		}
		ErrorKind kind = classify(response.status());
		if (kind == null) {
			return new DisclosureListResponse(response.status(), response.message(), pageNo, 0, 0, List.of());
		}
		throw ExternalApiException.of(SOURCE, kind, response.status(), response.message());
	}

	private <T> T get(String path, Map<String, Object> params, Class<T> type) {
		try {
			rateLimiter.acquirePermission();
		}
		catch (RequestNotPermitted e) {
			throw ExternalApiException.of(SOURCE, ErrorKind.RATE_LIMITED, "local", "호출 속도 제한 대기 시간 초과 " + path);
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
			throw ExternalApiException.of(SOURCE, ErrorKind.TRANSIENT, "IO", "연결 실패 " + path + ": " + e.getClass().getSimpleName());
		}
		catch (HttpServerErrorException e) {
			int status = e.getStatusCode().value();
			throw ExternalApiException.of(SOURCE, ErrorKind.TRANSIENT, "HTTP" + status, "서버 오류 " + path);
		}
		catch (HttpClientErrorException e) {
			int status = e.getStatusCode().value();
			ErrorKind kind = switch (status) {
				case 401, 403 -> ErrorKind.AUTH;
				case 429 -> ErrorKind.RATE_LIMITED;
				default -> ErrorKind.BAD_REQUEST;
			};
			throw ExternalApiException.of(SOURCE, kind, "HTTP" + status, "요청 오류 " + path);
		}
		catch (RestClientException e) {
			// 응답 본문 변환 실패 등. 원래 메시지에 URL이 있을 수 있어 종류만 남긴다.
			throw ExternalApiException.of(SOURCE, ErrorKind.PARSE, "convert", "응답 처리 실패 " + path + ": " + e.getClass().getSimpleName());
		}
	}

	static boolean isZip(byte[] body) {
		return body.length >= 4 && body[0] == 'P' && body[1] == 'K' && body[2] == 3 && body[3] == 4;
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
