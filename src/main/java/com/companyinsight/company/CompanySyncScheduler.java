package com.companyinsight.company;

import com.companyinsight.common.schedule.ExclusiveJobRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 매일 새벽 기업 목록을 갱신한다. 테이블이 비어 있으면 앱이 뜬 직후에도 한 번 실행한다. */
@Component
public class CompanySyncScheduler {

	private static final String GROUP = "company-sync";

	private final CompanySyncService syncService;
	private final ExclusiveJobRunner jobs;
	private final boolean runOnStartup;

	public CompanySyncScheduler(CompanySyncService syncService, ExclusiveJobRunner jobs,
			@Value("${app.company-sync.run-on-startup:true}") boolean runOnStartup) {
		this.syncService = syncService;
		this.jobs = jobs;
		this.runOnStartup = runOnStartup;
	}

	/**
	 * 별도 스레드에서 실행한다. 이 리스너가 끝나야 readiness가 "트래픽 수신 가능"으로 바뀌는데,
	 * 외부 API가 느리면 그만큼 앱 준비가 늦어지기 때문이다.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		if (runOnStartup) {
			Thread.ofVirtual().name("company-sync-startup")
				.start(() -> jobs.run(GROUP, "기업 목록 동기화(시작)", syncService::syncIfEmpty));
		}
	}

	@Scheduled(cron = "${app.company-sync.cron:0 10 6 * * *}", zone = "Asia/Seoul")
	public void daily() {
		jobs.run(GROUP, "기업 목록 동기화", syncService::sync);
	}
}
