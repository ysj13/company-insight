package com.companyinsight.common.gemini;

import com.companyinsight.common.external.ErrorKind;
import com.companyinsight.common.external.ExternalApiException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gemini API 클라이언트. 지시문과 입력을 보내고, 정해진 모양(JSON 스키마)의 답을 받는다.
 *
 * <p>호출 사이에 {@link GeminiProperties#minInterval()}만큼 간격을 둔다(무료 구간의 분당 한도). 재시도는 하지 않는다.
 * 과부하(5xx)와 한도(429)는 오류 분류로 바꿔 던지고, 다시 할지는 부른 쪽(분석 작업 큐)이 정한다.
 * 큐는 과부하와 한도를 실패로 세지 않고 다음 실행 시각만 미룬다.
 *
 * <p>오류 메시지에 인증키, 요청 글, 응답 글을 넣지 않는다. 응답 글은 하루 한도인지 가를 때만 읽는다.
 */
public class GeminiClient {

	/** 외부 소스 이름 */
	public static final String SOURCE = "GEMINI";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** 틀린 키에 Gemini가 400 응답의 오류 이유로 적는 말 */
	static final String INVALID_KEY_REASON = "API_KEY_INVALID";

	private final RestClient restClient;
	private final GeminiProperties properties;
	private final Clock clock;
	private Instant lastCallAt = Instant.MIN;

	public GeminiClient(RestClient restClient, GeminiProperties properties, Clock clock) {
		this.restClient = restClient;
		this.properties = properties;
		this.clock = clock;
	}

	/** 쓰는 모델 이름. */
	public String model() {
		return properties.model();
	}

	/** 인증키가 있는지. 없으면 AI 단계를 돌리지 않는다. */
	public boolean configured() {
		return properties.hasApiKey();
	}

	/**
	 * 지시문과 입력을 보내 JSON 답을 받는다.
	 *
	 * @param systemInstruction 지시문 (역할과 규칙)
	 * @param userText          입력 (공시 본문 등)
	 * @param responseSchema    답의 모양. Gemini의 스키마 형식(OBJECT, STRING …)
	 * @throws ExternalApiException 호출 실패. 과부하·연결 실패는 TRANSIENT, 분당 한도는 RATE_LIMITED, 하루 한도는 QUOTA_EXCEEDED,
	 *                              키 오류는 AUTH, 답이 비었거나 읽지 못하면 PARSE
	 */
	public synchronized GeminiReply generate(String systemInstruction, String userText, Map<String, Object> responseSchema) {
		if (!configured()) {
			throw ExternalApiException.of(SOURCE, ErrorKind.AUTH, "no-key", "Gemini 인증키가 없음");
		}
		waitForInterval();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction))));
		body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userText)))));
		body.put("generationConfig", Map.of("responseMimeType", "application/json", "responseSchema", responseSchema));
		Raw raw;
		try {
			raw = restClient.post()
				.uri("/v1beta/models/{model}:generateContent", properties.model())
				.header("x-goog-api-key", properties.apiKey())
				.contentType(MediaType.APPLICATION_JSON)
				.body(JSON.writeValueAsString(body))
				.exchange((request, response) -> new Raw(response.getStatusCode().value(),
						new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
		}
		catch (ResourceAccessException e) {
			throw ExternalApiException.of(SOURCE, ErrorKind.TRANSIENT, "IO", "연결 실패: " + e.getClass().getSimpleName());
		}
		catch (RestClientException e) {
			throw ExternalApiException.of(SOURCE, ErrorKind.UNKNOWN, "client", "호출 실패: " + e.getClass().getSimpleName());
		}
		finally {
			lastCallAt = clock.instant();
		}
		if (raw.status() != 200) {
			throw error(raw);
		}
		return reply(raw.body());
	}

	/** 앞선 호출에서 정해진 간격이 지날 때까지 기다린다. */
	private void waitForInterval() {
		if (lastCallAt == Instant.MIN) {
			return;
		}
		Duration wait = Duration.between(clock.instant(), lastCallAt.plus(properties.minInterval()));
		if (wait.isNegative() || wait.isZero()) {
			return;
		}
		try {
			Thread.sleep(wait);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw ExternalApiException.of(SOURCE, ErrorKind.TRANSIENT, "interrupted", "호출 간격을 기다리다 멈춤");
		}
	}

	/** 오류 응답을 분류한다. 429는 응답 글에 하루 한도의 표시가 있으면 하루 한도다. */
	static ExternalApiException error(Raw raw) {
		int status = raw.status();
		String code = "HTTP" + status;
		if (status == 429) {
			return isDailyQuota(raw.body())
					? ExternalApiException.of(SOURCE, ErrorKind.QUOTA_EXCEEDED, code, "하루 한도 소진")
					: ExternalApiException.of(SOURCE, ErrorKind.RATE_LIMITED, code, "호출 한도 (분당)");
		}
		if (status >= 500) {
			return ExternalApiException.of(SOURCE, ErrorKind.TRANSIENT, code, "서버 오류");
		}
		if (status == 400 && raw.body() != null && raw.body().contains(INVALID_KEY_REASON)) {
			// Gemini는 틀린 키에 401/403이 아니라 400으로 답한다
			return ExternalApiException.of(SOURCE, ErrorKind.AUTH, code, "인증키가 틀림");
		}
		if (status == 404) {
			// 모델 이름이 없다. 설정 오류라 다음 작업을 꺼내도 같은 답이 온다
			return ExternalApiException.of(SOURCE, ErrorKind.AUTH, code, "모델을 찾지 못함 (설정의 모델 이름 확인)");
		}
		ErrorKind kind = switch (status) {
			case 401, 403 -> ErrorKind.AUTH;
			case 400 -> ErrorKind.BAD_REQUEST;
			default -> ErrorKind.UNKNOWN;
		};
		return ExternalApiException.of(SOURCE, kind, code, "요청 오류");
	}

	/**
	 * 429 응답이 하루 한도인지. 한도 이름(quotaId)에 "PerDay"가 들어 있다
	 * (예: GenerateRequestsPerDayPerProjectPerModel-FreeTier). 분당 한도는 "PerMinute"다.
	 */
	static boolean isDailyQuota(String body) {
		String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
		return lower.contains("perday") || lower.contains("per day") || lower.contains("per_day");
	}

	/** 성공 응답에서 답 글과 토큰 수를 꺼낸다. 생각(thought) 부분의 글은 뺀다. */
	static GeminiReply reply(String body) {
		JsonNode root;
		try {
			root = JSON.readTree(body);
		}
		catch (RuntimeException e) {
			throw ExternalApiException.of(SOURCE, ErrorKind.PARSE, "json", "응답이 JSON이 아님");
		}
		JsonNode candidate = root.path("candidates").path(0);
		String finishReason = candidate.path("finishReason").isMissingNode() ? null : candidate.path("finishReason").asString();
		StringBuilder text = new StringBuilder();
		for (JsonNode part : candidate.path("content").path("parts")) {
			if (!part.path("thought").asBoolean(false) && part.has("text")) {
				text.append(part.path("text").asString());
			}
		}
		JsonNode usage = root.path("usageMetadata");
		Integer input = usage.has("promptTokenCount") ? usage.path("promptTokenCount").asInt() : null;
		Integer output = null;
		if (usage.has("candidatesTokenCount") || usage.has("thoughtsTokenCount")) {
			output = usage.path("candidatesTokenCount").asInt(0) + usage.path("thoughtsTokenCount").asInt(0);
		}
		if (text.isEmpty()) {
			// 안전 거름, 원문 인용 거름(RECITATION), 길이 초과 등으로 답 글이 없다
			throw ExternalApiException.of(SOURCE, ErrorKind.PARSE, finishReason == null ? "empty" : finishReason,
				"답 글이 비어 있음");
		}
		return new GeminiReply(text.toString(), finishReason, input, output);
	}

	/** 받은 응답 그대로. */
	record Raw(int status, String body) {
	}
}
