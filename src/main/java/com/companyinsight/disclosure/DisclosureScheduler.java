package com.companyinsight.disclosure;

import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 공시 수집 일정. 실패해도 예외를 밖으로 던지지 않는다. 실패 내용은 collection_run에 남고, 다음 실행이나 새벽 보정이 다시 채운다.
 */
@Component
public class DisclosureScheduler {

	private static final Logger log = LoggerFactory.getLogger(DisclosureScheduler.class);

	private final DisclosureCollector collector;
	private final Clock clock;
	private final int reconcileDays;

	public DisclosureScheduler(DisclosureCollector collector, Clock clock,
			@Value("${app.disclosure.reconcile-days:3}") int reconcileDays) {
		this.collector = collector;
		this.clock = clock;
		this.reconcileDays = reconcileDays;
	}

	/** 평일 07:00~19:55, 5분마다. */
	@Scheduled(cron = "${app.disclosure.poll-cron:0 */5 7-19 * * MON-FRI}", zone = "Asia/Seoul")
	public void poll() {
		try {
			collector.poll();
		}
		catch (RuntimeException e) {
			log.error("공시 폴링 실패: {}", e.getMessage());
		}
	}

	/** 매일 06:30, 최근 며칠을 처음부터 끝까지 다시 읽어 빠진 공시를 채운다. */
	@Scheduled(cron = "${app.disclosure.reconcile-cron:0 30 6 * * *}", zone = "Asia/Seoul")
	public void reconcile() {
		LocalDate today = LocalDate.now(clock);
		try {
			collector.reconcile(today.minusDays(reconcileDays), today.minusDays(1));
		}
		catch (RuntimeException e) {
			log.error("공시 보정 수집 실패: {}", e.getMessage());
		}
	}
}
