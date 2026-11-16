package com.companyinsight.company;

import com.companyinsight.common.dart.DartClient;
import com.companyinsight.common.external.ExternalApiException;
import com.companyinsight.common.external.SourcePauseRegistry;
import com.companyinsight.ops.CollectionRunRepository;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** DART 고유번호 파일로 상장사 목록을 맞춘다. */
@Service
public class CompanySyncService {

	private static final Logger log = LoggerFactory.getLogger(CompanySyncService.class);

	static final String JOB = "DART_COMPANY_SYNC";

	private final DartClient dartClient;
	private final CorpCodeParser parser;
	private final CompanyRepository repository;
	private final CollectionRunRepository runs;
	private final SourcePauseRegistry pauses;
	private final Clock clock;

	public CompanySyncService(DartClient dartClient, CorpCodeParser parser, CompanyRepository repository,
			CollectionRunRepository runs, SourcePauseRegistry pauses, Clock clock) {
		this.dartClient = dartClient;
		this.parser = parser;
		this.repository = repository;
		this.runs = runs;
		this.pauses = pauses;
		this.clock = clock;
	}

	/**
	 * 한 번도 성공한 적이 없을 때만 동기화한다. 앱을 띄운 직후에 쓴다.
	 * 행 수로 판단하지 않는 이유: 동기화가 실패한 사이 공시 수집이 회사를 몇 건 넣으면 목록이 일부만 있는데도 채워진 것으로 보인다.
	 */
	public SyncResult syncIfNeverSucceeded() {
		return runs.hasSucceeded(JOB) ? SyncResult.skip() : sync();
	}

	public SyncResult sync() {
		if (!dartClient.isConfigured()) {
			log.warn("DART_API_KEY가 없어 기업 목록 동기화를 건너뜁니다");
			return SyncResult.skip();
		}
		if (pauses.pausedNow(DartClient.SOURCE).isPresent()) {
			log.warn("DART 정지 중이라 기업 목록 동기화를 건너뜁니다");
			return SyncResult.skip();
		}
		long runId = runs.start(JOB, clock.instant());
		long started = System.currentTimeMillis();
		int fetched = 0;
		try {
			byte[] zip = dartClient.downloadCorpCodeZip();
			List<CorpCode> listed = parser.parseListed(zip);
			fetched = listed.size();
			int changed = repository.upsertAll(listed);
			long elapsed = System.currentTimeMillis() - started;
			runs.finishSuccess(runId, clock.instant(), fetched, changed, 1);
			log.info("기업 목록 동기화: 상장사 {}곳, 추가·변경 {}건, {}ms (zip {}KB)", fetched, changed, elapsed, zip.length / 1024);
			return new SyncResult(false, fetched, changed, elapsed);
		}
		catch (ExternalApiException e) {
			pauses.pauseFor(e);
			runs.finishFailed(runId, clock.instant(), fetched, 0, 0, e.kind().name(), e.code(), e.getMessage());
			throw e;
		}
		catch (RuntimeException e) {
			runs.finishFailed(runId, clock.instant(), fetched, 0, 0, "INTERNAL", null, e.toString());
			throw e;
		}
	}

	public record SyncResult(boolean skipped, int listed, int changed, long elapsedMillis) {
		static SyncResult skip() {
			return new SyncResult(true, 0, 0, 0);
		}
	}
}
