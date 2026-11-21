package com.companyinsight.common.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.companyinsight.common.external.ErrorKind;
import com.companyinsight.common.external.ExternalApiException;
import org.junit.jupiter.api.Test;

/** Gemini 응답 읽기와 오류 분류. 응답 모양은 2026-11-19 시험 호출에서 받은 것과 같다. */
class GeminiClientTest {

	@Test
	void 생각_부분을_빼고_답_글과_토큰_수를_읽는다() {
		String body = """
				{"candidates":[{"content":{"parts":[{"text":"생각 중","thought":true},{"text":"{\\"direction\\":\\"악재\\"}"}],
				"role":"model"},"finishReason":"STOP"}],
				"usageMetadata":{"promptTokenCount":2600,"candidatesTokenCount":205,"thoughtsTokenCount":40,"totalTokenCount":2845}}
				""";
		GeminiReply reply = GeminiClient.reply(body);
		assertThat(reply.text()).isEqualTo("{\"direction\":\"악재\"}");
		assertThat(reply.finishReason()).isEqualTo("STOP");
		assertThat(reply.inputTokens()).isEqualTo(2600);
		assertThat(reply.outputTokens()).isEqualTo(245);
	}

	@Test
	void 답_글이_없으면_끝난_이유를_코드로_던진다() {
		String body = """
				{"candidates":[{"finishReason":"RECITATION"}],"usageMetadata":{"promptTokenCount":10}}
				""";
		assertThatThrownBy(() -> GeminiClient.reply(body)).isInstanceOfSatisfying(ExternalApiException.class, e -> {
			assertThat(e.kind()).isEqualTo(ErrorKind.PARSE);
			assertThat(e.code()).isEqualTo("RECITATION");
		});
	}

	@Test
	void 한도_이름에_PerDay가_있는_429는_하루_한도다() {
		String daily = """
				{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure",
				"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}
				""";
		String perMinute = """
				{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[{"violations":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]}]}}
				""";
		assertThat(GeminiClient.error(new GeminiClient.Raw(429, daily)).kind()).isEqualTo(ErrorKind.QUOTA_EXCEEDED);
		assertThat(GeminiClient.error(new GeminiClient.Raw(429, perMinute)).kind()).isEqualTo(ErrorKind.RATE_LIMITED);
	}

	@Test
	void 서버_오류는_잠시_뒤_다시_할_오류이고_키_오류는_인증_오류다() {
		assertThat(GeminiClient.error(new GeminiClient.Raw(503, "{}")).kind()).isEqualTo(ErrorKind.TRANSIENT);
		assertThat(GeminiClient.error(new GeminiClient.Raw(500, "{}")).kind()).isEqualTo(ErrorKind.TRANSIENT);
		assertThat(GeminiClient.error(new GeminiClient.Raw(403, "{}")).kind()).isEqualTo(ErrorKind.AUTH);
		assertThat(GeminiClient.error(new GeminiClient.Raw(400, "{}")).kind()).isEqualTo(ErrorKind.BAD_REQUEST);
	}

	@Test
	void 오류_메시지에_응답_글을_넣지_않는다() {
		ExternalApiException e = GeminiClient.error(new GeminiClient.Raw(400, "{\"error\":{\"message\":\"secret-like text\"}}"));
		assertThat(e.getMessage()).doesNotContain("secret-like");
	}
}
