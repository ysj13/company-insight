package com.companyinsight.disclosure;

import com.companyinsight.common.dart.DartClient;
import com.companyinsight.common.dart.DisclosureListResponse;
import com.companyinsight.ops.CollectionRunRepository;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * DART 공시 목록 수집.
 *
 * <ul>
 * <li>{@link #poll()}: 오늘 공시를 최신부터 읽다가, 새 공시가 하나도 없는 페이지를 만나면 멈춘다. 5분마다 실행.</li>
 * <li>{@link #reconcile(LocalDate, LocalDate)}: 기간 전체를 끝까지 읽는다. 서버 중단이나 폴링 실패로 빠진 공시를
 * 채우기 위해 매일 새벽 최근 며칠을 다시 훑는다. 저장은 접수번호 unique라 중복되지 않는다.</li>
 * </ul>
 */
@Service
public class DisclosureCollector {

	private static final Logger log = LoggerFactory.getLogger(DisclosureCollector.class);

	static final String SOURCE_POLL = "DART_DISCLOSURE_POLL";
	static final String SOURCE_RECONCILE = "DART_DISCLOSURE_RECONCILE";

	private final DartClient dart;
	private final DisclosureRepository repository;
	private final CollectionRunRepository runs;
	private final Clock clock;

	public DisclosureCollector(DartClient dart, DisclosureRepository repository, CollectionRunRepository runs,
			Clock clock) {
		this.dart = dart;
		this.repository = repository;
		this.runs = runs;
		this.clock = clock;
	}

	public CollectResult poll() {
		return pollDay(LocalDate.now(clock));
	}

	CollectResult pollDay(LocalDate day) {
		return collect(SOURCE_POLL, day, day, true);
	}

	public CollectResult reconcile(LocalDate from, LocalDate to) {
		return collect(SOURCE_RECONCILE, from, to, false);
	}

	private CollectResult collect(String source, LocalDate from, LocalDate to, boolean stopWhenNothingNew) {
		if (!dart.isConfigured()) {
			log.warn("DART_API_KEY가 없어 공시 수집을 건너뜁니다");
			return CollectResult.skip();
		}
		long runId = runs.start(source, clock.instant());
		int fetched = 0;
		int saved = 0;
		int pages = 0;
		try {
			int page = 1;
			int totalPage;
			do {
				DisclosureListResponse response = dart.listDisclosures(from, to, page);
				totalPage = response.totalPage();
				pages++;
				fetched += response.items().size();
				int savedOnPage = repository.saveListed(response.items());
				saved += savedOnPage;

				boolean hasListed = response.items().stream().anyMatch(DisclosureRepository::isListed);
				if (stopWhenNothingNew && hasListed && savedOnPage == 0) {
					break;
				}
				page++;
			}
			while (page <= totalPage);

			runs.finish(runId, clock.instant(), "SUCCESS", fetched, saved, pages, null);
			log.info("공시 수집({} {}~{}): {}페이지, 받은 {}건, 새로 저장 {}건", source, from, to, pages, fetched, saved);
			return new CollectResult(false, pages, fetched, saved);
		}
		catch (RuntimeException e) {
			runs.finish(runId, clock.instant(), "FAILED", fetched, saved, pages, e.getMessage());
			throw e;
		}
	}

	public record CollectResult(boolean skipped, int pages, int fetched, int saved) {
		static CollectResult skip() {
			return new CollectResult(true, 0, 0, 0);
		}
	}
}
