package com.companyinsight.common.dart;

import com.companyinsight.common.external.ErrorKind;
import com.companyinsight.common.external.ExternalApiException;
import com.companyinsight.common.external.RetryableExternalApiException;
import io.github.resilience4j.ratelimiter.RateLimiter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

	static final String STATUS_OK = "000";

	/** 재시도 간격. 2초 → 4초, ±0.5초. 테스트에서 줄일 수 있게 설정으로 뺐다. */
	private static final String RETRY_DELAY = "${app.dart.retry-delay:2s}";
	private static final String RETRY_JITTER = "${app.dart.retry-jitter:500ms}";

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

	/** 인증키가 설정되어 있는지. 없으면 호출하지 말고 건너뛴다. */
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
	@Retryable(includes = RetryableExternalApiException.class, maxRetries = 2,
			delayString = RETRY_DELAY, jitterString = RETRY_JITTER, multiplier = 2)
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
	@Retryable(includes = RetryableExternalApiException.class, maxRetries = 2,
			delayString = RETRY_DELAY, jitterString = RETRY_JITTER, multiplier = 2)
	public DisclosureListResponse listDisclosures(LocalDate from, LocalDate to, int pageNo) {
		return listDisclosures(Map.of(
			"bgn_de", DartText.formatDate(from),
			"end_de", DartText.formatDate(to),
			"page_no", pageNo,
			"page_count", MAX_PAGE_COUNT), pageNo);
	}

	/**
	 * 회사 한 곳의 기간 내 공시 목록 한 페이지. 최신 접수번호가 먼저 온다. 데이터가 없으면 빈 목록.
	 *
	 * <p>회사를 지정하면 검색 기간이 3개월로 제한되지 않아 1년 이상을 한 번에 조회할 수 있다.
	 *
	 * @param corpCode DART 고유번호 (8자리)
	 * @param from     시작일 (포함)
	 * @param to       종료일 (포함)
	 * @param pageNo   페이지 번호 (1부터)
	 */
	@Retryable(includes = RetryableExternalApiException.class, maxRetries = 2,
			delayString = RETRY_DELAY, jitterString = RETRY_JITTER, multiplier = 2)
	public DisclosureListResponse listDisclosures(String corpCode, LocalDate from, LocalDate to, int pageNo) {
		return listDisclosures(Map.of(
			"corp_code", corpCode,
			"bgn_de", DartText.formatDate(from),
			"end_de", DartText.formatDate(to),
			"page_no", pageNo,
			"page_count", MAX_PAGE_COUNT), pageNo);
	}

	private DisclosureListResponse listDisclosures(Map<String, Object> params, int pageNo) {
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

	/**
	 * 회사 한 곳의 보고서 한 건에 실린 주요 재무 계정(매출액, 영업이익, 당기순이익 등)의 응답 원문.
	 *
	 * <p>응답을 그대로 저장할 수 있게 해석한 객체가 아니라 응답 본문(JSON)을 돌려준다.
	 * 필요한 값은 {@link MajorAccountResponse#fromJson(String)}으로 읽는다.
	 *
	 * @param corpCode     DART 고유번호 (8자리)
	 * @param businessYear 사업연도. 2015년 이후만 제공된다
	 * @param reportCode   보고서 코드. 11013 1분기, 11012 반기, 11014 3분기, 11011 사업보고서
	 * @return 응답 본문(JSON). 아직 제출되지 않은 보고서처럼 데이터가 없으면 비어 있음
	 */
	@Retryable(includes = RetryableExternalApiException.class, maxRetries = 2,
			delayString = RETRY_DELAY, jitterString = RETRY_JITTER, multiplier = 2)
	public Optional<String> fetchMajorAccounts(String corpCode, int businessYear, String reportCode) {
		Map<String, Object> params = Map.of(
			"corp_code", corpCode,
			"bsns_year", businessYear,
			"reprt_code", reportCode);
		// 문자열로 바로 받으면 응답 헤더에 문자 집합이 없을 때 한글이 깨진다. DART는 UTF-8로 준다
		byte[] bytes = get("/api/fnlttSinglAcnt.json", params, byte[].class);
		if (bytes == null || bytes.length == 0) {
			throw ExternalApiException.of(SOURCE, ErrorKind.PARSE, "empty", "주요계정 응답이 비어 있음");
		}
		String body = new String(bytes, StandardCharsets.UTF_8);
		if (body.startsWith("\uFEFF")) {
			body = body.substring(1); // UTF-8 BOM
		}
		MajorAccountResponse response;
		try {
			response = MajorAccountResponse.fromJson(body);
		}
		catch (RuntimeException e) {
			throw ExternalApiException.of(SOURCE, ErrorKind.PARSE, "convert", "주요계정 응답 처리 실패: " + e.getClass().getSimpleName());
		}
		if (response == null || response.status() == null) {
			throw ExternalApiException.of(SOURCE, ErrorKind.PARSE, "empty", "주요계정 응답에 상태 코드가 없음");
		}
		if (STATUS_OK.equals(response.status())) {
			return Optional.of(body);
		}
		ErrorKind kind = classify(response.status());
		if (kind == null) {
			return Optional.empty();
		}
		throw ExternalApiException.of(SOURCE, kind, response.status(), response.message());
	}

	/**
	 * 공시 한 건의 본문 원문 파일(zip. 안에 문서 파일이 하나 이상 있다)을 받는다.
	 *
	 * @param receiptNo 접수번호 (14자리)
	 * @return 받은 zip 그대로. 본문이 없는 공시(첨부만 고친 정정 등)면 비어 있음
	 */
	@Retryable(includes = RetryableExternalApiException.class, maxRetries = 2,
			delayString = RETRY_DELAY, jitterString = RETRY_JITTER, multiplier = 2)
	public Optional<byte[]> downloadDocument(String receiptNo) {
		byte[] body = get("/api/document.xml", Map.of("rcept_no", receiptNo), byte[].class);
		if (body == null || body.length == 0) {
			throw ExternalApiException.of(SOURCE, ErrorKind.PARSE, "empty", "공시 원문 응답이 비어 있음");
		}
		if (isZip(body)) {
			return Optional.of(body);
		}
		// 오류와 "파일 없음"은 zip이 아니라 상태 코드가 든 짧은 XML로 온다
		String text = new String(body, 0, Math.min(body.length, 2000), StandardCharsets.UTF_8);
		String status = firstGroup(text, XML_STATUS, JSON_STATUS, "unknown");
		if (STATUS_OK.equals(status)) {
			throw ExternalApiException.of(SOURCE, ErrorKind.PARSE, "not-zip", "공시 원문 응답이 zip이 아님");
		}
		ErrorKind kind = classify(status);
		if (kind == null) {
			return Optional.empty();
		}
		throw ExternalApiException.of(SOURCE, kind, status, firstGroup(text, XML_MESSAGE, JSON_MESSAGE, "zip이 아닌 응답"));
	}

	private <T> T get(String path, Map<String, Object> params, Class<T> type) {
		// acquirePermission()은 예외를 던지지 않고, 대기 시간 안에 허가를 못 받으면 false를 돌려준다.
		if (!rateLimiter.acquirePermission()) {
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
