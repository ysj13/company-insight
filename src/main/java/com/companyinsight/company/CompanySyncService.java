package com.companyinsight.company;

import com.companyinsight.common.dart.DartClient;
import com.companyinsight.common.external.ExternalApiException;
import com.companyinsight.common.external.SourcePauseRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** DART 고유번호 파일로 상장사 목록을 맞춘다. */
@Service
public class CompanySyncService {

	private static final Logger log = LoggerFactory.getLogger(CompanySyncService.class);

	private final DartClient dartClient;
	private final CorpCodeParser parser;
	private final CompanyRepository repository;
	private final SourcePauseRegistry pauses;

	public CompanySyncService(DartClient dartClient, CorpCodeParser parser, CompanyRepository repository,
			SourcePauseRegistry pauses) {
		this.pauses = pauses;
		this.dartClient = dartClient;
		this.parser = parser;
		this.repository = repository;
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
		long started = System.currentTimeMillis();
		byte[] zip;
		try {
			zip = dartClient.downloadCorpCodeZip();
		}
		catch (ExternalApiException e) {
			pauses.pauseFor(e);
			throw e;
		}
		List<CorpCode> listed = parser.parseListed(zip);
		int changed = repository.upsertAll(listed);
		long elapsed = System.currentTimeMillis() - started;
		log.info("기업 목록 동기화: 상장사 {}곳, 추가·변경 {}건, {}ms (zip {}KB)", listed.size(), changed, elapsed, zip.length / 1024);
		return new SyncResult(false, listed.size(), changed, elapsed);
	}

	public record SyncResult(boolean skipped, int listed, int changed, long elapsedMillis) {
		static SyncResult skip() {
			return new SyncResult(true, 0, 0, 0);
		}
	}
}
