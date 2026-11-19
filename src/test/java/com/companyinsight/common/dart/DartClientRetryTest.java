package com.companyinsight.common.dart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.companyinsight.common.external.ErrorKind;
import com.companyinsight.common.external.ExternalApiException;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@code @Retryable}이 프록시를 통해 실제로 동작하는지 확인한다.
 * {@link DartClientTest}는 객체를 직접 만들어 프록시를 거치지 않으므로 재시도를 검증하지 못한다.
 */
@SpringJUnitConfig(DartClientRetryTest.Config.class)
@TestPropertySource(properties = { "app.dart.retry-delay=10ms", "app.dart.retry-jitter=0ms" })
class DartClientRetryTest {

	private static final String LIST_URL = "https://dart.test/api/list.json";
	private static final LocalDate DAY = LocalDate.of(2026, 11, 13);

	@Autowired
	DartClient client;

	@Autowired
	MockRestServiceServer server;

	@AfterEach
	void reset() {
		server.reset();
	}

	@Test
	void 일시_오류는_두_번까지_다시_시도한다() {
		server.expect(times(2), requestTo(startsWith(LIST_URL))).andRespond(withServerError());
		server.expect(once(), requestTo(startsWith(LIST_URL)))
			.andRespond(withSuccess("{\"status\":\"013\",\"message\":\"조회된 데이타가 없습니다.\"}", MediaType.APPLICATION_JSON));

		DisclosureListResponse response = client.listDisclosures(DAY, DAY, 1);

		assertThat(response.items()).isEmpty();
		server.verify();
	}

	@Test
	void 회사를_지정한_공시목록도_일시_오류는_두_번까지_다시_시도한다() {
		server.expect(times(2), requestTo(startsWith(LIST_URL))).andRespond(withServerError());
		server.expect(once(), requestTo(startsWith(LIST_URL)))
			.andRespond(withSuccess("{\"status\":\"013\",\"message\":\"조회된 데이타가 없습니다.\"}", MediaType.APPLICATION_JSON));

		DisclosureListResponse response = client.listDisclosures("00126380", DAY.minusYears(1).plusDays(1), DAY, 1);

		assertThat(response.items()).isEmpty();
		server.verify();
	}

	@Test
	void 세_번_모두_실패하면_예외를_던진다() {
		server.expect(times(3), requestTo(startsWith(LIST_URL))).andRespond(withServerError());

		assertThatThrownBy(() -> client.listDisclosures(DAY, DAY, 1))
			.isInstanceOfSatisfying(ExternalApiException.class, e -> assertThat(e.kind()).isEqualTo(ErrorKind.TRANSIENT));
		server.verify();
	}

	@Test
	void 한도_초과는_다시_시도하지_않는다() {
		server.expect(once(), requestTo(startsWith(LIST_URL)))
			.andRespond(withSuccess("{\"status\":\"020\",\"message\":\"요청 제한을 초과하였습니다.\"}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.listDisclosures(DAY, DAY, 1))
			.isInstanceOfSatisfying(ExternalApiException.class, e -> assertThat(e.kind()).isEqualTo(ErrorKind.QUOTA_EXCEEDED));
		server.verify();
	}

	@Configuration(proxyBeanMethods = false)
	@EnableResilientMethods
	static class Config {

		@Bean
		RestClient.Builder restClientBuilder() {
			return RestClient.builder().baseUrl("https://dart.test");
		}

		@Bean
		MockRestServiceServer mockServer(RestClient.Builder builder) {
			return MockRestServiceServer.bindTo(builder).build();
		}

		@Bean
		DartClient dartClient(RestClient.Builder builder, MockRestServiceServer mockServer) {
			DartProperties properties = new DartProperties("test-key", "https://dart.test", null, null, 1000);
			return new DartClient(builder.build(), properties, DartConfig.dartRateLimiter(properties));
		}
	}
}
