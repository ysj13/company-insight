package com.companyinsight.disclosure;

import com.companyinsight.common.dart.DartClient;
import com.companyinsight.common.dart.DisclosureListResponse;
import com.companyinsight.common.external.SourcePauseRegistry;
import com.companyinsight.ops.CollectionRunRecorder;
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
 * <li>{@link #poll()}: 오늘 공시를 최신부터 읽다가, 새 공시가 하나도 없는 페이지를 만나면 멈춘다. 5분마다 실행.
 * 직전 폴링이 성공하지 못했으면 뒤쪽 페이지에 빠진 공시가 있을 수 있으므로 멈추지 않고 끝까지 읽는다.</li>
 * <li>{@link #reconcile(LocalDate, LocalDate)}: 기간 전체를 끝까지 읽는다. 서버 중단이나 폴링 실패로 빠진 공시를
 * 채우기 위해 매일 새벽 최근 며칠을 다시 훑는다. 저장은 접수번호 unique라 중복되지 않는다.</li>
 * </ul>
 */
@Service
public class DisclosureCollector {

	private static final Logger log = LoggerFactory.getLogger(DisclosureCollector.class);

	/** collection_run에 남기는 작업 이름. */
	static final String SOURCE_POLL = "DART_DISCLOSURE_POLL";
	static final String SOURCE_RECONCILE = "DART_DISCLOSURE_RECONCILE";

	private final DartClient dart;
	private final DisclosureRepository repository;
	private final CollectionRunRepository runs;
	private final CollectionRunRecorder recorder;
	private final SourcePauseRegistry pauses;
	private final Clock clock;

	public DisclosureCollector(DartClient dart, DisclosureRepository repository, CollectionRunRepository runs,
			CollectionRunRecorder recorder, SourcePauseRegistry pauses, Clock clock) {
		this.dart = dart;
		this.repository = repository;
		this.runs = runs;
		this.recorder = recorder;
		this.pauses = pauses;
		this.clock = clock;
	}

	/** 오늘(한국 시간) 접수된 공시 중 아직 저장하지 않은 것을 가져온다. */
	public CollectResult poll() {
		return pollDay(LocalDate.now(clock));
	}

	/**
	 * 지정한 날의 공시를 폴링 방식으로 가져온다.
	 * 직전 폴링이 성공했으면 새 공시가 없는 페이지에서 멈추고, 아니면 끝까지 읽는다.
	 */
	CollectResult pollDay(LocalDate day) {
		boolean previousSucceeded = runs.lastRunSucceeded(SOURCE_POLL);
		return collect(SOURCE_POLL, day, day, previousSucceeded);
	}

	/**
	 * 기간 안의 공시를 처음부터 끝까지 다시 읽어 빠진 것을 채운다.
	 *
	 * @param from 시작일 (포함)
	 * @param to   종료일 (포함)
	 */
	public CollectResult reconcile(LocalDate from, LocalDate to) {
		return collect(SOURCE_RECONCILE, from, to, false);
	}

	/**
	 * @param stopWhenNothingNew true면 저장 가능한 상장사 공시가 있는데 새로 저장된 것이 없는 페이지에서 멈춘다.
	 *                           DART가 최신 공시부터 주므로 그 뒤는 이미 받은 것으로 본다.
	 */
	private CollectResult collect(String job, LocalDate from, LocalDate to, boolean stopWhenNothingNew) {
		if (!dart.isConfigured()) {
			log.warn("DART_API_KEY가 없어 공시 수집을 건너뜁니다");
			return CollectResult.skip();
		}
		var pause = pauses.pausedNow(DartClient.SOURCE);
		if (pause.isPresent()) {
			log.debug("DART 정지 중({})이라 공시 수집을 건너뜁니다", pause.get());
			return CollectResult.skip();
		}
		return recorder.record(job, progress -> {
			int page = 1;
			int totalPage;
			do {
				DisclosureListResponse response = dart.listDisclosures(from, to, page);
				totalPage = response.totalPage();
				progress.addFetched(response.items().size());
				int savedOnPage = repository.saveListed(response.items());
				progress.addSaved(savedOnPage);

				boolean hasStorable = response.items().stream()
					.anyMatch(item -> DisclosureRepository.isListed(item) && DisclosureRepository.isStorable(item));
				if (stopWhenNothingNew && hasStorable && savedOnPage == 0) {
					break;
				}
				page++;
			}
			while (page <= totalPage);

			log.info("공시 수집({} {}~{}): {}페이지, 받은 {}건, 새로 저장 {}건", job, from, to, progress.pages(),
				progress.fetched(), progress.saved());
			return new CollectResult(false, progress.pages(), progress.fetched(), progress.saved());
		});
	}

	/**
	 * 수집 결과.
	 *
	 * @param skipped 키가 없거나 소스가 정지 중이라 실행하지 않았으면 true
	 * @param pages   읽은 페이지 수
	 * @param fetched DART에서 받은 공시 수 (비상장 포함)
	 * @param saved   새로 저장한 상장사 공시 수
	 */
	public record CollectResult(boolean skipped, int pages, int fetched, int saved) {
		static CollectResult skip() {
			return new CollectResult(true, 0, 0, 0);
		}
	}
}
