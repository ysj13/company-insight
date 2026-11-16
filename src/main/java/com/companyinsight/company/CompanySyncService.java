package com.companyinsight.company;

import com.companyinsight.common.dart.DartClient;
import com.companyinsight.common.external.SourcePauseRegistry;
import com.companyinsight.ops.CollectionRunRecorder;
import com.companyinsight.ops.CollectionRunRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** DART 고유번호 파일로 상장사 목록을 맞춘다. */
@Service
public class CompanySyncService {

	private static final Logger log = LoggerFactory.getLogger(CompanySyncService.class);

	/** collection_run에 남기는 작업 이름. */
	static final String JOB = "DART_COMPANY_SYNC";

	private final DartClient dartClient;
	private final CorpCodeParser parser;
	private final CompanyRepository repository;
	private final CollectionRunRepository runs;
	private final CollectionRunRecorder recorder;
	private final SourcePauseRegistry pauses;

	public CompanySyncService(DartClient dartClient, CorpCodeParser parser, CompanyRepository repository,
			CollectionRunRepository runs, CollectionRunRecorder recorder, SourcePauseRegistry pauses) {
		this.dartClient = dartClient;
		this.parser = parser;
		this.repository = repository;
		this.runs = runs;
		this.recorder = recorder;
		this.pauses = pauses;
	}

	/**
	 * 한 번도 성공한 적이 없을 때만 동기화한다. 앱을 띄운 직후에 쓴다.
	 * 행 수로 판단하지 않는 이유: 동기화가 실패한 사이 공시 수집이 회사를 몇 건 넣으면 목록이 일부만 있는데도 채워진 것으로 보인다.
	 */
	public SyncResult syncIfNeverSucceeded() {
		return runs.hasSucceeded(JOB) ? SyncResult.skip() : sync();
	}

	/**
	 * 고유번호 파일을 받아 상장사 목록을 추가·갱신한다. 여러 번 실행해도 결과가 같다.
	 *
	 * @return 실행 결과. 키가 없거나 DART가 정지 중이면 건너뛴 결과.
	 * @throws com.companyinsight.common.external.ExternalApiException DART 호출이 실패한 경우. 실패 내용은 collection_run에 남는다.
	 */
	public SyncResult sync() {
		if (!dartClient.isConfigured()) {
			log.warn("DART_API_KEY가 없어 기업 목록 동기화를 건너뜁니다");
			return SyncResult.skip();
		}
		if (pauses.pausedNow(DartClient.SOURCE).isPresent()) {
			log.warn("DART 정지 중이라 기업 목록 동기화를 건너뜁니다");
			return SyncResult.skip();
		}
		return recorder.record(JOB, progress -> {
			long started = System.currentTimeMillis();
			byte[] zip = dartClient.downloadCorpCodeZip();
			List<CorpCode> listed = parser.parseListed(zip);
			int changed = repository.upsertAll(listed);
			progress.addPage(listed.size(), changed);
			long elapsed = System.currentTimeMillis() - started;
			log.info("기업 목록 동기화: 상장사 {}곳, 추가·변경 {}건, {}ms (zip {}KB)", listed.size(), changed, elapsed,
				zip.length / 1024);
			return new SyncResult(false, listed.size(), changed, elapsed);
		});
	}

	/**
	 * 동기화 결과.
	 *
	 * @param skipped       실행하지 않았으면 true
	 * @param listed        고유번호 파일에 있던 상장사 수
	 * @param changed       추가되거나 값이 바뀐 회사 수
	 * @param elapsedMillis 걸린 시간
	 */
	public record SyncResult(boolean skipped, int listed, int changed, long elapsedMillis) {
		static SyncResult skip() {
			return new SyncResult(true, 0, 0, 0);
		}
	}
}
