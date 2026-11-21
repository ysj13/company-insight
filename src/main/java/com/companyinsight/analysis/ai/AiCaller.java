package com.companyinsight.analysis.ai;

import com.companyinsight.analysis.AiUsage;
import com.companyinsight.analysis.StageFailedException;
import com.companyinsight.analysis.StageResult;
import com.companyinsight.common.external.ErrorKind;
import com.companyinsight.common.external.ExternalApiException;
import com.companyinsight.common.gemini.GeminiClient;
import com.companyinsight.common.gemini.GeminiProperties;
import com.companyinsight.common.gemini.GeminiReply;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 단계가 Gemini를 부르는 곳. 호출 오류를 분석 작업 큐의 행동으로 바꾼다.
 *
 * <ul>
 * <li>과부하(5xx), 연결 실패: 이 작업만 잠시 뒤로 미룬다. 실패로 세지 않는다
 * <li>분당 한도: AI 쪽 대기 작업을 모두 1분 뒤로 미룬다
 * <li>하루 한도: AI 쪽 대기 작업을 모두 한도가 풀리는 시각(설정한 시간대의 다음 자정) 뒤로 미룬다
 * <li>키 오류: AI 쪽 대기 작업을 모두 1시간 뒤로 미룬다(키를 고칠 때까지 실패가 쌓여 멈추지 않게)
 * <li>그 밖(요청 오류, 답이 비었거나 JSON이 아님): 실패로 센다
 * </ul>
 */
@Component
public class AiCaller {

	private static final Logger log = LoggerFactory.getLogger(AiCaller.class);

	/** 과부하·연결 실패 뒤 다시 꺼내기까지 */
	static final Duration TRANSIENT_DELAY = Duration.ofMinutes(2);

	/** 분당 한도 뒤 다시 꺼내기까지 */
	static final Duration RATE_LIMIT_DELAY = Duration.ofMinutes(1);

	/** 키 오류 뒤 다시 꺼내기까지 */
	static final Duration AUTH_DELAY = Duration.ofHours(1);

	/** 하루 한도가 풀리는 자정에서 조금 더 기다리는 시간 */
	static final Duration QUOTA_MARGIN = Duration.ofMinutes(5);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final GeminiClient gemini;
	private final GeminiProperties properties;
	private final Clock clock;

	public AiCaller(GeminiClient gemini, GeminiProperties properties, Clock clock) {
		this.gemini = gemini;
		this.properties = properties;
		this.clock = clock;
	}

	/** 쓰는 모델 이름. 설정 버전에 넣는다. */
	public String model() {
		return gemini.model();
	}

	/**
	 * 지시문과 입력을 보내 JSON 답을 받는다.
	 *
	 * @param versionId 이 호출의 설정 버전 (실행 기록에 남긴다)
	 * @throws Postpone           실패로 세지 않고 미뤄야 할 때. 담긴 결과를 그대로 돌려준다
	 * @throws StageFailedException 답이 JSON이 아닐 때 (실패로 센다. 받은 글은 실행 기록에 남긴다)
	 * @throws ExternalApiException 그 밖의 호출 실패 (실패로 센다)
	 */
	public Answer call(String system, String user, Map<String, Object> schema, long versionId) {
		if (!gemini.configured()) {
			throw new Postpone(StageResult.deferredAll(clock.instant().plus(AUTH_DELAY), ErrorKind.AUTH, "no-key",
					"Gemini 인증키가 없어 AI 단계를 쉰다"));
		}
		GeminiReply reply;
		try {
			reply = gemini.generate(system, user, schema);
		}
		catch (ExternalApiException e) {
			StageResult.Deferred deferred = deferredFor(e, clock.instant());
			if (deferred == null) {
				throw e;
			}
			throw new Postpone(deferred.withAiUsage(AiUsage.withoutTokens(versionId)));
		}
		AiUsage usage = new AiUsage(versionId, reply.inputTokens(), reply.outputTokens());
		JsonNode answer;
		try {
			answer = JSON.readTree(reply.text());
		}
		catch (RuntimeException e) {
			throw new StageFailedException("PARSE", "AI의 답이 JSON이 아님",
					JSON.writeValueAsString(Map.of("raw", cut(reply.text(), 4000))), usage);
		}
		if (answer == null || !answer.isObject()) {
			throw new StageFailedException("PARSE", "AI의 답이 JSON 객체가 아님",
					JSON.writeValueAsString(Map.of("raw", cut(reply.text(), 4000))), usage);
		}
		return new Answer(answer, usage);
	}

	/** 호출 오류에 맞는 미룸. 실패로 셀 오류면 null. */
	StageResult.Deferred deferredFor(ExternalApiException e, Instant now) {
		ErrorKind kind = e.kind();
		return switch (kind) {
			case TRANSIENT -> StageResult.deferred(now.plus(TRANSIENT_DELAY), kind, e.code(), e.getMessage());
			case RATE_LIMITED -> StageResult.deferredAll(now.plus(RATE_LIMIT_DELAY), kind, e.code(), e.getMessage());
			case QUOTA_EXCEEDED -> {
				Instant until = nextQuotaReset(now);
				log.warn("Gemini 하루 한도에 걸렸습니다. AI 단계를 {}까지 쉽니다", until);
				yield StageResult.deferredAll(until, kind, e.code(), e.getMessage());
			}
			case AUTH -> {
				log.error("Gemini 인증 오류입니다. 키를 확인하세요. AI 단계를 1시간 쉽니다: {}", e.getMessage());
				yield StageResult.deferredAll(now.plus(AUTH_DELAY), kind, e.code(), e.getMessage());
			}
			default -> null;
		};
	}

	/** 하루 한도가 풀리는 시각: 설정한 시간대의 다음 자정 + 여유. */
	Instant nextQuotaReset(Instant now) {
		ZonedDateTime local = now.atZone(properties.quotaResetZone());
		LocalDate tomorrow = local.toLocalDate().plusDays(1);
		return tomorrow.atStartOfDay(properties.quotaResetZone()).toInstant().plus(QUOTA_MARGIN);
	}

	private static String cut(String text, int max) {
		return text == null || text.length() <= max ? text : text.substring(0, max);
	}

	/**
	 * AI의 답.
	 *
	 * @param json  답 (JSON 객체)
	 * @param usage 호출 기록
	 */
	public record Answer(JsonNode json, AiUsage usage) {
	}

	/** 실패로 세지 않고 미뤄야 한다. 담긴 결과를 단계의 결과로 돌려준다. */
	public static final class Postpone extends RuntimeException {

		private final transient StageResult.Deferred deferred;

		Postpone(StageResult.Deferred deferred) {
			super(deferred.message(), null, false, false);
			this.deferred = deferred;
		}

		/** 돌려줄 결과. */
		public StageResult.Deferred deferred() {
			return deferred;
		}
	}
}
