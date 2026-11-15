package com.companyinsight.company;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 새벽 기업 목록을 갱신한다. 테이블이 비어 있으면 앱이 뜬 직후에도 한 번 실행한다.
 * 실패해도 예외를 밖으로 던지지 않는다. 다음 실행에서 다시 시도하면 되고, 앱이 죽으면 안 되기 때문이다.
 */
@Component
public class CompanySyncScheduler {

	private static final Logger log = LoggerFactory.getLogger(CompanySyncScheduler.class);

	private final CompanySyncService syncService;
	private final CompanyRepository repository;
	private final boolean runOnStartup;

	public CompanySyncScheduler(CompanySyncService syncService, CompanyRepository repository,
			@Value("${app.company-sync.run-on-startup:true}") boolean runOnStartup) {
		this.syncService = syncService;
		this.repository = repository;
		this.runOnStartup = runOnStartup;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		if (runOnStartup && repository.count() == 0) {
			runSafely("startup");
		}
	}

	@Scheduled(cron = "${app.company-sync.cron:0 10 6 * * *}", zone = "Asia/Seoul")
	public void daily() {
		runSafely("daily");
	}

	private void runSafely(String trigger) {
		try {
			syncService.sync();
		}
		catch (RuntimeException e) {
			log.error("기업 목록 동기화 실패 ({}): {}", trigger, e.getMessage());
		}
	}
}
