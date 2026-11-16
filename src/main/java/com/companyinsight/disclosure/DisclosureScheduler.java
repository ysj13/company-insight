package com.companyinsight.disclosure;

import com.companyinsight.common.schedule.ExclusiveJobRunner;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 공시 수집과 재분류 일정. 모두 같은 테이블을 쓰므로 겹쳐 실행하지 않는다.
 * 실패 내용은 collection_run에 남고, 다음 실행이나 새벽 보정이 다시 채운다.
 */
@Component
public class DisclosureScheduler {

	private static final String GROUP = "disclosure";

	private final DisclosureCollector collector;
	private final DisclosureClassificationService classification;
	private final ExclusiveJobRunner jobs;
	private final Clock clock;
	private final int reconcileDays;
	private final boolean reclassifyOnStartup;

	public DisclosureScheduler(DisclosureCollector collector, DisclosureClassificationService classification,
			ExclusiveJobRunner jobs, Clock clock, @Value("${app.disclosure.reconcile-days:3}") int reconcileDays,
			@Value("${app.disclosure.reclassify-on-startup:true}") boolean reclassifyOnStartup) {
		this.collector = collector;
		this.classification = classification;
		this.jobs = jobs;
		this.clock = clock;
		this.reconcileDays = reconcileDays;
		this.reclassifyOnStartup = reclassifyOnStartup;
	}

	/**
	 * 앱이 뜬 직후, 옛 규칙으로 분류된 공시를 다시 분류한다. 규칙을 바꿔 배포하면 과거 공시에도 바로 반영된다.
	 * 앱 준비를 늦추지 않게 별도 스레드에서 실행한다. 마침 수집이 돌고 있어 건너뛰면 새벽 보정 때 실행된다.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		if (reclassifyOnStartup) {
			Thread.ofVirtual().name("disclosure-reclassify-startup")
				.start(() -> jobs.run(GROUP, "공시 재분류(시작)", classification::reclassifyOutdated));
		}
	}

	/** 평일 07:00~19:55, 5분마다. */
	@Scheduled(cron = "${app.disclosure.poll-cron:0 */5 7-19 * * MON-FRI}", zone = "Asia/Seoul")
	public void poll() {
		jobs.run(GROUP, "공시 폴링", collector::poll);
	}

	/**
	 * 매일 06:30, 최근 며칠을 처음부터 끝까지 다시 읽어 빠진 공시를 채우고 바뀐 비고(정정·철회)를 맞춘다.
	 * 이어서 옛 규칙으로 남아 있는 공시를 다시 분류한다.
	 */
	@Scheduled(cron = "${app.disclosure.reconcile-cron:0 30 6 * * *}", zone = "Asia/Seoul")
	public void reconcile() {
		LocalDate today = LocalDate.now(clock);
		jobs.run(GROUP, "공시 보정 수집", () -> collector.reconcile(today.minusDays(reconcileDays), today.minusDays(1)));
		jobs.run(GROUP, "공시 재분류", classification::reclassifyOutdated);
	}
}
