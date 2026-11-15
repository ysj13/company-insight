package com.companyinsight.common.dart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DartClientTest {

	private static final String SECRET = "secret-key-123";

	private MockRestServiceServer server;
	private DartClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://dart.test");
		server = MockRestServiceServer.bindTo(builder).build();
		client = new DartClient(builder.build(), new DartProperties(SECRET, "https://dart.test", null, null));
	}

	@Test
	void zip이면_그대로_돌려준다() {
		byte[] zip = { 'P', 'K', 3, 4, 1, 2, 3 };
		server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://dart.test/api/corpCode.xml")))
			.andExpect(queryParam("crtfc_key", SECRET))
			.andRespond(withSuccess(zip, MediaType.APPLICATION_OCTET_STREAM));

		assertThat(client.downloadCorpCodeZip()).isEqualTo(zip);
	}

	@Test
	void 오류_XML이면_상태코드와_메시지로_예외() {
		String body = "<result><status>010</status><message>등록되지 않은 키입니다.</message></result>";
		server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://dart.test/api/corpCode.xml")))
			.andRespond(withSuccess(body.getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_XML));

		assertThatThrownBy(client::downloadCorpCodeZip)
			.isInstanceOf(DartApiException.class)
			.hasMessageContaining("010")
			.hasMessageContaining("등록되지 않은 키");
	}

	@Test
	void 시스템_점검은_재시도_대상_예외() {
		String body = "{\"status\":\"800\",\"message\":\"시스템 점검 중입니다.\"}";
		server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://dart.test/api/corpCode.xml")))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		assertThatThrownBy(client::downloadCorpCodeZip).isInstanceOf(DartTransientException.class);
	}

	@Test
	void 서버_오류_메시지에_인증키가_없다() {
		server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://dart.test/api/corpCode.xml")))
			.andRespond(withServerError());

		assertThatThrownBy(client::downloadCorpCodeZip)
			.isInstanceOf(DartTransientException.class)
			.hasNoCause()
			.message().doesNotContain(SECRET);
	}
}
