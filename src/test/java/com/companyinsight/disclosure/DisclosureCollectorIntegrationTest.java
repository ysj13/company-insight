package com.companyinsight.disclosure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.companyinsight.TestcontainersConfiguration;
import com.companyinsight.common.dart.DartClient;
import com.companyinsight.common.dart.DisclosureListResponse;
import com.companyinsight.common.dart.DisclosureListResponse.Item;
import com.companyinsight.common.external.ErrorKind;
import com.companyinsight.common.external.ExternalApiException;
import com.companyinsight.common.external.SourcePauseRegistry;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DisclosureCollectorIntegrationTest {

	private static final LocalDate DAY = LocalDate.of(2026, 11, 13);

	@Autowired
	DisclosureCollector collector;

	@Autowired
	JdbcClient jdbc;

	@MockitoBean
	DartClient dart;

	@MockitoBean
	SourcePauseRegistry pauses;

	@BeforeEach
	void setUp() {
		jdbc.sql("DELETE FROM disclosure").update();
		jdbc.sql("DELETE FROM company").update();
		jdbc.sql("DELETE FROM collection_run").update();
		given(dart.isConfigured()).willReturn(true);
		given(pauses.pausedNow(any())).willReturn(java.util.Optional.empty());
	}

	@Test
	void 상장사_공시만_저장하고_회사_시장구분을_갱신한다() {
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 1,
			item("00126380", "005930", "Y", "20261113000003", "주요사항보고서(자기주식취득결정)    "),
			item("00999999", " ", "E", "20261113000002", "비상장사 공시"),
			item("00164779", "000660", "K", "20261113000001", "기업설명회(IR)개최")));

		DisclosureCollector.CollectResult result = collector.reconcile(DAY, DAY);

		assertThat(result.fetched()).isEqualTo(3);
		assertThat(result.saved()).isEqualTo(2);
		assertThat(jdbc.sql("SELECT report_name FROM disclosure WHERE rcept_no = '20261113000003'")
			.query(String.class).single()).isEqualTo("주요사항보고서(자기주식취득결정)");
		Map<String, Object> company = jdbc.sql("SELECT market, last_disclosed_on FROM company WHERE corp_code = '00126380'")
			.query().singleRow();
		assertThat(company.get("market")).isEqualTo("Y");
		assertThat(company.get("last_disclosed_on").toString()).isEqualTo("2026-11-13");
	}

	@Test
	void 같은_공시가_다시_와도_한_번만_저장된다() {
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 1,
			item("00126380", "005930", "Y", "20261113000001", "공시")));

		collector.reconcile(DAY, DAY);
		DisclosureCollector.CollectResult second = collector.reconcile(DAY, DAY);

		assertThat(second.saved()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM disclosure").query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void 보정_수집은_모든_페이지를_읽는다() {
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 2, item("1", "000001", "Y", "20261113000002", "a")));
		given(dart.listDisclosures(DAY, DAY, 2)).willReturn(page(2, 2, item("2", "000002", "K", "20261113000001", "b")));

		DisclosureCollector.CollectResult result = collector.reconcile(DAY, DAY);

		assertThat(result.pages()).isEqualTo(2);
		assertThat(result.saved()).isEqualTo(2);
	}

	@Test
	void 폴링은_새_공시가_없는_페이지에서_멈춘다() {
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 3, item("1", "000001", "Y", "20261113000003", "a")));
		given(dart.listDisclosures(DAY, DAY, 2)).willReturn(page(2, 3, item("2", "000002", "K", "20261113000002", "b")));
		given(dart.listDisclosures(DAY, DAY, 3)).willReturn(page(3, 3));
		collector.reconcile(DAY, DAY);
		clearInvocations(dart);
		given(dart.listDisclosures(DAY, DAY, 1)).willReturn(page(1, 3,
			item("3", "000003", "Y", "20261113000004", "new"),
			item("1", "000001", "Y", "20261113000003", "a")));

		DisclosureCollector.CollectResult result = pollAt(DAY);

		assertThat(result.saved()).isEqualTo(1);
		assertThat(result.pages()).isEqualTo(2);
		verify(dart, never()).listDisclosures(eq(DAY), eq(DAY), eq(3));
	}

	@Test
	void 외부_오류는_분류와_코드를_기록하고_소스를_정지시킨다() {
		ExternalApiException quota = ExternalApiException.of(DartClient.SOURCE, ErrorKind.QUOTA_EXCEEDED, "020", "요청 제한을 초과하였습니다.");
		given(dart.listDisclosures(any(), any(), eq(1))).willThrow(quota);

		assertThatThrownBy(() -> collector.reconcile(DAY, DAY)).isSameAs(quota);

		Map<String, Object> run = jdbc.sql("SELECT source, status, error_kind, error_code, finished_at FROM collection_run")
			.query().singleRow();
		assertThat(run.get("source")).isEqualTo(DisclosureCollector.SOURCE_RECONCILE);
		assertThat(run.get("status")).isEqualTo("FAILED");
		assertThat(run.get("error_kind")).isEqualTo("QUOTA_EXCEEDED");
		assertThat(run.get("error_code")).isEqualTo("020");
		assertThat(run.get("finished_at")).isNotNull();
		verify(pauses).pauseFor(quota);
	}

	@Test
	void 정지된_소스는_호출하지_않는다() {
		given(pauses.pausedNow(DartClient.SOURCE)).willReturn(java.util.Optional.of(
			new SourcePauseRegistry.Pause(java.time.Instant.MAX, ErrorKind.QUOTA_EXCEEDED, "020")));

		assertThat(collector.reconcile(DAY, DAY).skipped()).isTrue();
		verify(dart, never()).listDisclosures(any(), any(), org.mockito.ArgumentMatchers.anyInt());
		assertThat(jdbc.sql("SELECT count(*) FROM collection_run").query(Long.class).single()).isZero();
	}

	@Test
	void 키가_없으면_건너뛴다() {
		given(dart.isConfigured()).willReturn(false);

		assertThat(collector.poll().skipped()).isTrue();
		assertThat(jdbc.sql("SELECT count(*) FROM collection_run").query(Long.class).single()).isZero();
	}

	/** poll()은 Clock 기준 "오늘"을 쓰므로, 테스트 날짜로 reconcile과 같은 경로를 태우기 위해 폴링 모드를 직접 호출한다. */
	private DisclosureCollector.CollectResult pollAt(LocalDate day) {
		return collector.pollDay(day);
	}

	private static DisclosureListResponse page(int pageNo, int totalPage, Item... items) {
		return new DisclosureListResponse("000", "정상", pageNo, items.length, totalPage, List.of(items));
	}

	private static Item item(String corpCode, String stockCode, String cls, String rceptNo, String name) {
		return new Item(corpCode, "회사" + corpCode, stockCode, cls, name, rceptNo, "제출인", "20261113", "");
	}
}
