package com.companyinsight.financial;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.companyinsight.common.schedule.ExclusiveJobRunner;
import com.companyinsight.company.CompanyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinancialSchedulerTest {

	private final FinancialCollector collector = mock(FinancialCollector.class);
	private final CompanyRepository companies = mock(CompanyRepository.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneId.of("Asia/Seoul"));

	@Test
	void 설정한_종목코드의_회사를_올해부터_거슬러_받는다() {
		given(companies.findIdsByStockCode("005930")).willReturn(List.of(379L));
		given(companies.findIdsByStockCode("000660")).willReturn(List.of(915L));

		scheduler(List.of("005930", " 000660 ", ""), 3).collectConfigured();

		verify(collector).collect(379L, 2024, 2026);
		verify(collector).collect(915L, 2024, 2026);
	}

	@Test
	void 한_회사가_실패해도_다음_회사는_받는다() {
		given(companies.findIdsByStockCode("005930")).willReturn(List.of(379L));
		given(companies.findIdsByStockCode("005380")).willReturn(List.of(914L));
		given(collector.collect(379L, 2026, 2026)).willThrow(new IllegalStateException("실패"));

		scheduler(List.of("005930", "005380"), 1).collectConfigured();

		verify(collector).collect(914L, 2026, 2026);
	}

	@Test
	void 종목코드에_해당하는_회사가_없거나_여럿이면_건너뛴다() {
		given(companies.findIdsByStockCode("999999")).willReturn(List.of());
		given(companies.findIdsByStockCode("111111")).willReturn(List.of(1L, 2L));

		scheduler(List.of("999999", "111111"), 3).collectConfigured();

		verify(collector, never()).collect(anyLong(), anyInt(), anyInt());
	}

	@Test
	void 제공되지_않는_2015년_이전은_받지_않는다() {
		given(companies.findIdsByStockCode("005930")).willReturn(List.of(379L));

		scheduler(List.of("005930"), 50).collectConfigured();

		verify(collector).collect(379L, 2015, 2026);
	}

	private FinancialScheduler scheduler(List<String> stockCodes, int years) {
		return new FinancialScheduler(collector, companies, mock(ExclusiveJobRunner.class),
			new FinancialProperties(stockCodes, years), clock);
	}
}
