package com.companyinsight.common.dart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import com.companyinsight.common.external.ErrorKind;
import com.companyinsight.common.external.ExternalApiException;
import com.companyinsight.common.external.RetryableExternalApiException;
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
		DartProperties props = new DartProperties(SECRET, "https://dart.test", null, null, 100);
		client = new DartClient(builder.build(), props, DartConfig.dartRateLimiter(props));
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
			.isInstanceOfSatisfying(ExternalApiException.class, e -> {
				assertThat(e.kind()).isEqualTo(ErrorKind.AUTH);
				assertThat(e.code()).isEqualTo("010");
				assertThat(e).isNotInstanceOf(RetryableExternalApiException.class);
			})
			.hasMessageContaining("등록되지 않은 키");
	}

	@Test
	void 시스템_점검은_재시도_대상_예외() {
		String body = "{\"status\":\"800\",\"message\":\"시스템 점검 중입니다.\"}";
		server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://dart.test/api/corpCode.xml")))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		assertThatThrownBy(client::downloadCorpCodeZip)
			.isInstanceOfSatisfying(RetryableExternalApiException.class, e -> assertThat(e.kind()).isEqualTo(ErrorKind.TRANSIENT));
	}

	@Test
	void 서버_오류_메시지에_인증키가_없다() {
		server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://dart.test/api/corpCode.xml")))
			.andRespond(withServerError());

		assertThatThrownBy(client::downloadCorpCodeZip)
			.isInstanceOf(RetryableExternalApiException.class)
			.hasNoCause()
			.message().doesNotContain(SECRET);
	}

	@Test
	void 공시목록을_읽는다() {
		String body = """
			{"status":"000","message":"정상","page_no":1,"page_count":100,"total_count":2,"total_page":1,
			 "list":[{"corp_code":"00126380","corp_name":"삼성전자","stock_code":"005930","corp_cls":"Y",
			          "report_nm":"주요사항보고서(자기주식취득결정)   ","rcept_no":"20261113000001","flr_nm":"삼성전자",
			          "rcept_dt":"20261113","rm":"유"}]}
			""";
		server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://dart.test/api/list.json")))
			.andExpect(queryParam("bgn_de", "20261113"))
			.andExpect(queryParam("end_de", "20261113"))
			.andExpect(queryParam("page_no", "1"))
			.andExpect(queryParam("page_count", "100"))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		DisclosureListResponse response = client.listDisclosures(java.time.LocalDate.of(2026, 11, 13),
			java.time.LocalDate.of(2026, 11, 13), 1);

		assertThat(response.totalPage()).isEqualTo(1);
		assertThat(response.items()).singleElement()
			.satisfies(item -> {
				assertThat(item.receiptNo()).isEqualTo("20261113000001");
				assertThat(item.corpCls()).isEqualTo("Y");
			});
	}

	@Test
	void 공시가_없으면_빈_목록() {
		server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://dart.test/api/list.json")))
			.andRespond(withSuccess("{\"status\":\"013\",\"message\":\"조회된 데이타가 없습니다.\"}", MediaType.APPLICATION_JSON));

		DisclosureListResponse response = client.listDisclosures(java.time.LocalDate.of(2026, 11, 15),
			java.time.LocalDate.of(2026, 11, 15), 1);

		assertThat(response.items()).isEmpty();
		assertThat(response.totalPage()).isZero();
	}

	@Test
	void 한도_초과는_QUOTA_EXCEEDED() {
		server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://dart.test/api/list.json")))
			.andRespond(withSuccess("{\"status\":\"020\",\"message\":\"요청 제한을 초과하였습니다.\"}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.listDisclosures(java.time.LocalDate.of(2026, 11, 13),
			java.time.LocalDate.of(2026, 11, 13), 1))
			.isInstanceOfSatisfying(ExternalApiException.class, e -> {
				assertThat(e.kind()).isEqualTo(ErrorKind.QUOTA_EXCEEDED);
				assertThat(e.code()).isEqualTo("020");
				assertThat(e).isNotInstanceOf(RetryableExternalApiException.class);
			});
	}

	@Test
	void HTTP_429는_RATE_LIMITED이고_재시도_대상() {
		server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://dart.test/api/list.json")))
			.andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
				.withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));

		assertThatThrownBy(() -> client.listDisclosures(java.time.LocalDate.of(2026, 11, 13),
			java.time.LocalDate.of(2026, 11, 13), 1))
			.isInstanceOfSatisfying(RetryableExternalApiException.class,
				e -> assertThat(e.kind()).isEqualTo(ErrorKind.RATE_LIMITED));
	}

	@Test
	void 상태코드_분류표() {
		assertThat(DartClient.classify("013")).isNull();
		assertThat(DartClient.classify("014")).isNull();
		assertThat(DartClient.classify("020")).isEqualTo(ErrorKind.QUOTA_EXCEEDED);
		assertThat(DartClient.classify("021")).isEqualTo(ErrorKind.QUOTA_EXCEEDED);
		assertThat(DartClient.classify("010")).isEqualTo(ErrorKind.AUTH);
		assertThat(DartClient.classify("011")).isEqualTo(ErrorKind.AUTH);
		assertThat(DartClient.classify("012")).isEqualTo(ErrorKind.AUTH);
		assertThat(DartClient.classify("901")).isEqualTo(ErrorKind.AUTH);
		assertThat(DartClient.classify("100")).isEqualTo(ErrorKind.BAD_REQUEST);
		assertThat(DartClient.classify("800")).isEqualTo(ErrorKind.TRANSIENT);
		assertThat(DartClient.classify("900")).isEqualTo(ErrorKind.TRANSIENT);
		assertThat(DartClient.classify("999")).isEqualTo(ErrorKind.UNKNOWN);
	}
}
