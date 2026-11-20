package com.companyinsight.disclosure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.companyinsight.analysis.AnalysisQueueService;
import com.companyinsight.common.config.ClockConfig;
import com.companyinsight.common.schedule.ExclusiveJobRunner;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class DisclosureSchedulerTest {

	private final DisclosureCollector collector = mock(DisclosureCollector.class);
	private final DisclosureClassificationService classification = mock(DisclosureClassificationService.class);
	private final AnalysisQueueService analysisQueue = mock(AnalysisQueueService.class);

	@Test
	void 시작할_때_설정한_종목코드의_최근_12개월_공시를_재분류_뒤에_받는다() {
		// 2026-11-20(한국 시간)에 띄우면 2025-11-21부터 2026-11-20까지 365일이다
		DisclosureScheduler scheduler = scheduler("2026-11-20T09:00:00+09:00", true,
			new DisclosureCollectProperties(List.of("005930", "000660", "005380"), 12));

		scheduler.runStartupJobs();

		InOrder order = inOrder(classification, collector, analysisQueue);
		order.verify(classification).reclassifyOutdated();
		order.verify(collector).collectByStockCodes(List.of("005930", "000660", "005380"), LocalDate.of(2025, 11, 21),
			LocalDate.of(2026, 11, 20));
		// 유형이 붙고 공시가 들어온 뒤에 분석 작업 큐에 줄 세운다
		order.verify(analysisQueue).enqueueMissing();
	}

	@Test
	void 폴링과_보정_수집_뒤에도_분석_작업_큐에_줄_세운다() {
		DisclosureScheduler scheduler = scheduler("2026-11-20T09:00:00+09:00", true,
			new DisclosureCollectProperties(List.of(), null));

		scheduler.poll();
		scheduler.reconcile();

		InOrder order = inOrder(collector, classification, analysisQueue);
		order.verify(collector).poll();
		order.verify(analysisQueue).enqueueMissing();
		order.verify(collector).reconcile(LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 19));
		order.verify(classification).reclassifyOutdated();
		order.verify(analysisQueue).enqueueMissing();
	}

	@Test
	void 종목코드_설정이_비어_있으면_공시를_받지_않는다() {
		DisclosureScheduler scheduler = scheduler("2026-11-20T09:00:00+09:00", true,
			new DisclosureCollectProperties(List.of(), null));

		scheduler.runStartupJobs();

		verify(classification).reclassifyOutdated();
		verify(collector, never()).collectByStockCodes(any(), any(), any());
	}

	@Test
	void 재분류를_꺼도_설정한_종목코드의_공시는_받는다() {
		DisclosureScheduler scheduler = scheduler("2026-11-20T09:00:00+09:00", false,
			new DisclosureCollectProperties(List.of("005930"), 3));

		scheduler.runStartupJobs();

		verifyNoInteractions(classification);
		verify(collector).collectByStockCodes(List.of("005930"), LocalDate.of(2026, 8, 21), LocalDate.of(2026, 11, 20));
	}

	@Test
	void 오늘은_한국_시간으로_정한다() {
		// UTC로는 아직 11월 19일이지만 한국은 11월 20일 새벽이다
		DisclosureScheduler scheduler = scheduler("2026-11-19T15:30:00Z", false,
			new DisclosureCollectProperties(List.of("005930"), 12));

		scheduler.runStartupJobs();

		verify(collector).collectByStockCodes(List.of("005930"), LocalDate.of(2025, 11, 21), LocalDate.of(2026, 11, 20));
	}

	@Test
	void 월말과_윤년에도_기간이_설정한_개월을_넘지_않는다() {
		// 3월 31일에 1개월이면 3월 1일부터다 (2월 28일 다음 날)
		scheduler("2026-03-31T09:00:00+09:00", false, new DisclosureCollectProperties(List.of("005930"), 1))
			.runStartupJobs();
		verify(collector).collectByStockCodes(List.of("005930"), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

		// 윤년 2월 29일에 12개월이면 전년 3월 1일부터다 (전년 2월 28일 다음 날)
		scheduler("2028-02-29T09:00:00+09:00", false, new DisclosureCollectProperties(List.of("005930"), 12))
			.runStartupJobs();
		verify(collector).collectByStockCodes(List.of("005930"), LocalDate.of(2027, 3, 1), LocalDate.of(2028, 2, 29));
	}

	@Test
	void 설정의_빈_값과_공백은_걸러지고_기간이_없거나_틀리면_12개월이다() {
		DisclosureCollectProperties properties = new DisclosureCollectProperties(
			Arrays.asList(" 005930 ", "", null, "000660"), 0);

		assertThat(properties.collectOnStartup()).containsExactly("005930", "000660");
		assertThat(properties.collectMonths()).isEqualTo(12);
		assertThat(new DisclosureCollectProperties(null, null).collectOnStartup()).isEmpty();
		assertThat(new DisclosureCollectProperties(null, null).collectMonths()).isEqualTo(12);
	}

	private DisclosureScheduler scheduler(String now, boolean reclassifyOnStartup, DisclosureCollectProperties properties) {
		Clock clock = Clock.fixed(java.time.OffsetDateTime.parse(now).toInstant(), ClockConfig.KST);
		return new DisclosureScheduler(collector, classification, new ExclusiveJobRunner(), clock, 3, reclassifyOnStartup,
			properties, analysisQueue, true);
	}
}
