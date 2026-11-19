package com.companyinsight.disclosure;

import com.companyinsight.common.dart.DartClient;
import com.companyinsight.common.dart.DisclosureListResponse;
import com.companyinsight.common.external.SourcePauseRegistry;
import com.companyinsight.common.source.Source;
import com.companyinsight.company.CompanyRepository;
import com.companyinsight.ops.CollectionRunRecorder;
import com.companyinsight.ops.CollectionRunRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;
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
 * 채우기 위해 매일 새벽 최근 며칠을 다시 훑는다. 저장은 "출처 + 접수번호" unique라 중복되지 않는다.</li>
 * <li>{@link #collectByStockCodes(List, LocalDate, LocalDate)}: 종목코드로 지정한 회사의 공시만 기간 전체를 끝까지 읽는다.
 * 회사를 지정하면 DART가 긴 기간(1년 이상)도 한 번에 조회해 준다. 목록만 받고 본문은 받지 않는다.</li>
 * </ul>
 */
@Service
public class DisclosureCollector {

	private static final Logger log = LoggerFactory.getLogger(DisclosureCollector.class);

	/** collection_run에 남기는 작업 이름. */
	static final String SOURCE_POLL = "DART_DISCLOSURE_POLL";
	static final String SOURCE_RECONCILE = "DART_DISCLOSURE_RECONCILE";
	static final String SOURCE_COMPANY = "DART_DISCLOSURE_COMPANY";

	private final DartClient dart;
	private final CompanyRepository companies;
	private final DisclosureRepository repository;
	private final CollectionRunRepository runs;
	private final CollectionRunRecorder recorder;
	private final SourcePauseRegistry pauses;
	private final Clock clock;

	public DisclosureCollector(DartClient dart, CompanyRepository companies, DisclosureRepository repository,
			CollectionRunRepository runs, CollectionRunRecorder recorder, SourcePauseRegistry pauses, Clock clock) {
		this.dart = dart;
		this.companies = companies;
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
		return collect(SOURCE_POLL, day, day, previousSucceeded, page -> dart.listDisclosures(day, day, page));
	}

	/**
	 * 기간 안의 공시를 처음부터 끝까지 다시 읽어 빠진 것을 채운다.
	 *
	 * @param from 시작일 (포함)
	 * @param to   종료일 (포함)
	 */
	public CollectResult reconcile(LocalDate from, LocalDate to) {
		return collect(SOURCE_RECONCILE, from, to, false, page -> dart.listDisclosures(from, to, page));
	}

	/**
	 * 종목코드로 지정한 회사들의 공시 목록을 기간 전체로 받는다. 한 회사가 실패해도 나머지는 계속한다.
	 * 여러 번 실행해도 같은 공시는 한 번만 저장되고, 이미 있는 공시는 비고(정정·철회 표시)만 최신 값으로 맞춘다.
	 *
	 * <p>종목코드에 해당하는 회사가 없거나 여럿이면(상장폐지된 회사의 종목코드가 남아 있는 경우) 그 종목코드는 경고만
	 * 남기고 건너뛴다. 기업 목록이 아직 없는 새 DB에서는 받을 회사를 찾지 못한다.
	 *
	 * @param stockCodes 종목코드 목록. 같은 종목코드가 여러 번 있으면 한 번만 받는다
	 * @param from       시작일 (포함)
	 * @param to         종료일 (포함)
	 * @return 회사별 결과를 더한 값
	 */
	public CompanyCollectResult collectByStockCodes(List<String> stockCodes, LocalDate from, LocalDate to) {
		int requested = 0;
		int collected = 0;
		int fetched = 0;
		int saved = 0;
		for (String stockCode : new LinkedHashSet<>(stockCodes)) {
			requested++;
			List<Long> ids = companies.findIdsByStockCode(stockCode);
			if (ids.size() != 1) {
				log.warn("공시 수집(회사 지정): 종목코드 {}에 해당하는 회사가 {}곳이라 건너뜁니다", stockCode, ids.size());
				continue;
			}
			Optional<String> corpCode = companies.findSourceKey(ids.getFirst(), Source.DART);
			if (corpCode.isEmpty()) {
				log.warn("공시 수집(회사 지정): 종목코드 {}는 DART에서 받은 회사가 아니라 건너뜁니다", stockCode);
				continue;
			}
			try {
				CollectResult result = collectCompany(corpCode.get(), from, to);
				if (!result.skipped()) {
					collected++;
					fetched += result.fetched();
					saved += result.saved();
				}
			}
			catch (RuntimeException e) {
				// DART 호출 실패는 collection_run에도 남는다. 한도 초과처럼 소스가 정지되는 오류면 다음 회사는 건너뛴 것으로 끝난다
				log.warn("공시 수집(회사 지정) 실패(종목코드 {}, 고유번호 {}): {}", stockCode, corpCode.get(), e.getMessage());
			}
		}
		log.info("공시 수집(회사 지정 {}~{}): 종목코드 {}개 중 {}곳을 받음, 받은 {}건, 새로 저장 {}건", from, to, requested,
			collected, fetched, saved);
		return new CompanyCollectResult(requested, collected, fetched, saved);
	}

	/**
	 * 회사 한 곳의 공시 목록을 기간 전체로 받아 저장한다.
	 *
	 * @param corpCode DART 고유번호 (8자리)
	 * @param from     시작일 (포함)
	 * @param to       종료일 (포함)
	 */
	CollectResult collectCompany(String corpCode, LocalDate from, LocalDate to) {
		return collect(SOURCE_COMPANY, from, to, false, page -> dart.listDisclosures(corpCode, from, to, page));
	}

	/**
	 * @param fetchPage          페이지 번호(1부터)를 받아 그 페이지를 가져오는 호출
	 * @param stopWhenNothingNew true면 저장 가능한 상장사 공시가 있는데 새로 저장된 것이 없는 페이지에서 멈춘다.
	 *                           DART가 최신 공시부터 주므로 그 뒤는 이미 받은 것으로 본다.
	 */
	private CollectResult collect(String job, LocalDate from, LocalDate to, boolean stopWhenNothingNew,
			IntFunction<DisclosureListResponse> fetchPage) {
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
				DisclosureListResponse response = fetchPage.apply(page);
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

	/**
	 * 회사를 지정한 수집의 결과.
	 *
	 * @param requested 요청한 종목코드 수 (중복 제외)
	 * @param collected 실제로 받은 회사 수. 회사를 찾지 못했거나 실패한 종목코드, 키가 없거나 소스가 정지 중이라 건너뛴 회사는 빠진다
	 * @param fetched   DART에서 받은 공시 수. 도중에 실패한 회사가 그 전까지 받은 것은 빠진다 (collection_run에는 남는다)
	 * @param saved     새로 저장한 공시 수. 도중에 실패한 회사가 그 전까지 저장한 것은 빠진다
	 */
	public record CompanyCollectResult(int requested, int collected, int fetched, int saved) {
	}
}
