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

	@Test
	void 속도_제한_허가를_못_받으면_호출하지_않고_RATE_LIMITED() {
		io.github.resilience4j.ratelimiter.RateLimiter oneCall = io.github.resilience4j.ratelimiter.RateLimiter.of("test",
			io.github.resilience4j.ratelimiter.RateLimiterConfig.custom()
				.limitForPeriod(1)
				.limitRefreshPeriod(java.time.Duration.ofMinutes(1))
				.timeoutDuration(java.time.Duration.ZERO)
				.build());
		RestClient.Builder builder = RestClient.builder().baseUrl("https://dart.test");
		MockRestServiceServer limitedServer = MockRestServiceServer.bindTo(builder).build();
		DartClient limited = new DartClient(builder.build(), new DartProperties(SECRET, "https://dart.test", null, null, 1), oneCall);
		limitedServer.expect(org.springframework.test.web.client.ExpectedCount.once(),
				requestTo(org.hamcrest.Matchers.startsWith("https://dart.test/api/list.json")))
			.andRespond(withSuccess("{\"status\":\"013\",\"message\":\"없음\"}", MediaType.APPLICATION_JSON));
		java.time.LocalDate day = java.time.LocalDate.of(2026, 11, 13);

		limited.listDisclosures(day, day, 1);

		assertThatThrownBy(() -> limited.listDisclosures(day, day, 1))
			.isInstanceOfSatisfying(RetryableExternalApiException.class,
				e -> assertThat(e.kind()).isEqualTo(ErrorKind.RATE_LIMITED));
		limitedServer.verify();
	}

	@Test
	void 주요계정을_읽는다() throws Exception {
		// 삼성전자 2025년 3분기보고서의 실제 응답에서 손익계산서 계정과 자산총계만 남긴 것
		String body = new org.springframework.core.io.ClassPathResource("dart/major-accounts-00126380-2025-q3.json")
			.getContentAsString(StandardCharsets.UTF_8);
		server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://dart.test/api/fnlttSinglAcnt.json")))
			.andExpect(queryParam("crtfc_key", SECRET))
			.andExpect(queryParam("corp_code", "00126380"))
			.andExpect(queryParam("bsns_year", "2025"))
			.andExpect(queryParam("reprt_code", "11014"))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		String json = client.fetchMajorAccounts("00126380", 2025, "11014").orElseThrow();

		// 응답 원문을 그대로 돌려준다. 우리가 읽지 않는 필드(기간 문자열, 당기명)도 남아 있다
		assertThat(json).isEqualTo(body);
		assertThat(json).contains("\"thstrm_dt\"").contains("2025.01.01 ~ 2025.09.30").contains("제 57 기3분기");
		MajorAccountResponse response = MajorAccountResponse.fromJson(json);
		assertThat(response.items()).hasSize(14);
		assertThat(response.items()).filteredOn(i -> "CFS".equals(i.statementDiv()) && "매출액".equals(i.accountName()))
			.singleElement()
			.satisfies(item -> {
				assertThat(item.receiptNo()).isEqualTo("20251114002447");
				assertThat(item.sheetDiv()).isEqualTo("IS");
				assertThat(item.currentAmount()).isEqualTo("86,061,747,000,000");
				assertThat(item.currentAddAmount()).isEqualTo("239,768,567,000,000");
				assertThat(item.priorAmount()).isEqualTo("79,098,731,000,000");
				assertThat(item.priorAddAmount()).isEqualTo("225,082,634,000,000");
				assertThat(item.order()).isEqualTo("23");
				assertThat(item.currency()).isEqualTo("KRW");
			});
	}

	@Test
	void 주요계정_응답의_한글은_헤더에_문자_집합이_없어도_깨지지_않는다() {
		String body = "{\"status\":\"000\",\"message\":\"정상\",\"list\":[{\"account_nm\":\"매출액\"}]}";
		server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://dart.test/api/fnlttSinglAcnt.json")))
			.andRespond(withSuccess(body.getBytes(StandardCharsets.UTF_8), MediaType.TEXT_PLAIN));

		assertThat(client.fetchMajorAccounts("00126380", 2025, "11014")).contains(body);
	}

	@Test
	void 주요계정_응답_앞의_BOM은_떼고_돌려준다() {
		String body = "{\"status\":\"000\",\"message\":\"정상\",\"list\":[{\"account_nm\":\"매출액\"}]}";
		server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://dart.test/api/fnlttSinglAcnt.json")))
			.andRespond(withSuccess(("\uFEFF" + body).getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON));

		assertThat(client.fetchMajorAccounts("00126380", 2025, "11014")).contains(body);
	}

	@Test
	void 주요계정_응답이_JSON이_아니면_PARSE_오류() {
		server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://dart.test/api/fnlttSinglAcnt.json")))
			.andRespond(withSuccess("<html>점검 중</html>", MediaType.TEXT_HTML));

		assertThatThrownBy(() -> client.fetchMajorAccounts("00126380", 2025, "11014"))
			.isInstanceOfSatisfying(ExternalApiException.class, e -> {
				assertThat(e.kind()).isEqualTo(ErrorKind.PARSE);
				assertThat(e).isNotInstanceOf(RetryableExternalApiException.class);
			})
			.message().doesNotContain(SECRET);
	}

	@Test
	void 제출되지_않은_보고서의_주요계정은_빈_목록() {
		// 실제 응답: 아직 나오지 않은 2026년 사업보고서를 조회했을 때
		server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://dart.test/api/fnlttSinglAcnt.json")))
			.andRespond(withSuccess("{\"status\":\"013\",\"message\":\"조회된 데이타가 없습니다.\"}", MediaType.APPLICATION_JSON));

		assertThat(client.fetchMajorAccounts("00126380", 2026, "11011")).isEmpty();
	}

	@Test
	void 주요계정_한도_초과는_QUOTA_EXCEEDED() {
		server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://dart.test/api/fnlttSinglAcnt.json")))
			.andRespond(withSuccess("{\"status\":\"020\",\"message\":\"요청 제한을 초과하였습니다.\"}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.fetchMajorAccounts("00126380", 2025, "11014"))
			.isInstanceOfSatisfying(ExternalApiException.class, e -> assertThat(e.kind()).isEqualTo(ErrorKind.QUOTA_EXCEEDED));
	}
}
