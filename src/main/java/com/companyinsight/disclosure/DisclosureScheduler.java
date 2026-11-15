package com.companyinsight.disclosure;

import com.companyinsight.common.schedule.ExclusiveJobRunner;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 공시 수집 일정. 폴링과 보정은 같은 테이블을 쓰므로 겹쳐 실행하지 않는다.
 * 실패 내용은 collection_run에 남고, 다음 실행이나 새벽 보정이 다시 채운다.
 */
@Component
public class DisclosureScheduler {

	private static final String GROUP = "disclosure";

	private final DisclosureCollector collector;
	private final ExclusiveJobRunner jobs;
	private final Clock clock;
	private final int reconcileDays;

	public DisclosureScheduler(DisclosureCollector collector, ExclusiveJobRunner jobs, Clock clock,
			@Value("${app.disclosure.reconcile-days:3}") int reconcileDays) {
		this.collector = collector;
		this.jobs = jobs;
		this.clock = clock;
		this.reconcileDays = reconcileDays;
	}

	/** 평일 07:00~19:55, 5분마다. */
	@Scheduled(cron = "${app.disclosure.poll-cron:0 */5 7-19 * * MON-FRI}", zone = "Asia/Seoul")
	public void poll() {
		jobs.run(GROUP, "공시 폴링", collector::poll);
	}

	/** 매일 06:30, 최근 며칠을 처음부터 끝까지 다시 읽어 빠진 공시를 채운다. */
	@Scheduled(cron = "${app.disclosure.reconcile-cron:0 30 6 * * *}", zone = "Asia/Seoul")
	public void reconcile() {
		LocalDate today = LocalDate.now(clock);
		jobs.run(GROUP, "공시 보정 수집", () -> collector.reconcile(today.minusDays(reconcileDays), today.minusDays(1)));
	}
}
