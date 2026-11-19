package com.companyinsight.disclosure;

import com.companyinsight.common.schedule.ExclusiveJobRunner;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	private static final Logger log = LoggerFactory.getLogger(DisclosureScheduler.class);

	private static final String GROUP = "disclosure";

	private final DisclosureCollector collector;
	private final DisclosureClassificationService classification;
	private final ExclusiveJobRunner jobs;
	private final Clock clock;
	private final int reconcileDays;
	private final boolean reclassifyOnStartup;
	private final DisclosureCollectProperties collectProperties;

	public DisclosureScheduler(DisclosureCollector collector, DisclosureClassificationService classification,
			ExclusiveJobRunner jobs, Clock clock, @Value("${app.disclosure.reconcile-days:3}") int reconcileDays,
			@Value("${app.disclosure.reclassify-on-startup:true}") boolean reclassifyOnStartup,
			DisclosureCollectProperties collectProperties) {
		this.collector = collector;
		this.classification = classification;
		this.jobs = jobs;
		this.clock = clock;
		this.reconcileDays = reconcileDays;
		this.reclassifyOnStartup = reclassifyOnStartup;
		this.collectProperties = collectProperties;
	}

	/**
	 * 앱이 뜬 직후, 옛 규칙으로 분류된 공시를 다시 분류하고, 설정({@code app.disclosure.collect-on-startup})에
	 * 종목코드가 있으면 그 회사들의 공시 목록을 받는다. 규칙을 바꿔 배포하면 과거 공시에도 바로 반영된다.
	 * 앱 준비를 늦추지 않게 별도 스레드에서 실행한다. 마침 수집이 돌고 있어 건너뛰면 재분류는 새벽 보정 때 실행되고,
	 * 회사 지정 수집은 다음에 앱이 뜰 때 실행된다.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		if (reclassifyOnStartup || !collectProperties.collectOnStartup().isEmpty()) {
			Thread.ofVirtual().name("disclosure-startup").start(this::runStartupJobs);
		}
	}

	/** 시작할 때의 작업을 차례로 실행한다. 한 스레드에서 이어서 실행해 재분류와 수집이 서로를 건너뛰게 하지 않는다. */
	void runStartupJobs() {
		if (reclassifyOnStartup) {
			jobs.run(GROUP, "공시 재분류(시작)", classification::reclassifyOutdated);
		}
		List<String> stockCodes = collectProperties.collectOnStartup();
		if (!stockCodes.isEmpty()) {
			LocalDate today = LocalDate.now(clock);
			LocalDate from = today.minusMonths(collectProperties.collectMonths()).plusDays(1);
			boolean ran = jobs.run(GROUP, "공시 수집(회사 지정, 시작)",
				() -> collector.collectByStockCodes(stockCodes, from, today));
			if (!ran) {
				log.warn("다른 공시 작업이 실행 중이라 회사 지정 공시 수집({})을 하지 못했습니다. 앱을 다시 띄우면 실행됩니다", stockCodes);
			}
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
