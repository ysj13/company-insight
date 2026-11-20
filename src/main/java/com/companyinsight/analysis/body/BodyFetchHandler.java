package com.companyinsight.analysis.body;

import com.companyinsight.analysis.AnalysisJob;
import com.companyinsight.analysis.AnalysisStage;
import com.companyinsight.analysis.StageHandler;
import com.companyinsight.analysis.StageResult;
import com.companyinsight.analysis.body.DisclosureBodyRepository.BodyTarget;
import com.companyinsight.analysis.body.DisclosureBodyRepository.StoredBody;
import com.companyinsight.common.config.ClockConfig;
import com.companyinsight.common.dart.DartClient;
import com.companyinsight.common.external.ErrorKind;
import com.companyinsight.common.external.ExternalApiException;
import com.companyinsight.common.external.SourcePauseRegistry;
import com.companyinsight.common.external.SourcePauseRegistry.Pause;
import com.companyinsight.common.source.Source;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 본문 받기 단계. 공시 한 건의 본문 원문을 출처에서 받아 그대로 저장하고 다음 단계를 정한다.
 *
 * <p>받은 파일은 저장하기 전에 풀어서 읽어 본다. 풀리지 않는 파일을 저장해 두면 뒤 단계가
 * 모두 같은 자리에서 실패한다. 뽑은 글은 저장하지 않고 글자 수만 실행 기록에 남긴다(쓸 때마다 {@link DisclosureBodyReader}가 뽑는다).
 * 글자가 하나도 없는 문서도 저장한다(그림만 든 문서일 수 있다. 실행 기록의 글자 수 0으로 찾는다).
 *
 * <p>본문이 없다는 답은 실패가 아니다. 다만 접수한 날에는 출처가 파일을 아직 올리지 않았을 수 있어,
 * 접수일이 지날 때까지 한 시간마다 다시 확인한 뒤에 "본문 없음"으로 끝낸다.
 *
 * <p>출처를 부르지 않고 끝나는 경우: 대기하는 동안 공시가 철회됐거나 본문을 받지 않는 유형으로 바뀐 경우,
 * 본문을 이미 받아 둔 경우(같은 접수번호의 본문은 바뀌지 않는다. 고치면 새 접수번호의 정정 공시가 나온다).
 *
 * <p>하루 한도나 인증 오류처럼 다음 작업을 불러도 같은 답이 올 때는 실패로 세지 않고 본문 받기 전체를 미룬다.
 * 그 밖의 오류(연결 실패, 시간 초과, 점검)는 클라이언트가 재시도한 뒤에도 남으면 실패 한 번으로 센다.
 */
@Component
public class BodyFetchHandler implements StageHandler {

	/** 인증키가 없을 때 다시 확인하기까지의 간격 */
	static final Duration NO_KEY_DELAY = Duration.ofHours(1);

	/** 접수한 날의 공시에 본문이 없다는 답이 오면 다시 확인하기까지의 간격. 접수일이 지나면 본문 없음으로 끝낸다 */
	static final Duration SAME_DAY_RECHECK = Duration.ofHours(1);

	private final DartClient dart;
	private final DisclosureBodyRepository bodies;
	private final SourcePauseRegistry pauses;
	private final Clock clock;

	public BodyFetchHandler(DartClient dart, DisclosureBodyRepository bodies, SourcePauseRegistry pauses, Clock clock) {
		this.dart = dart;
		this.bodies = bodies;
		this.pauses = pauses;
		this.clock = clock;
	}

	@Override
	public AnalysisStage stage() {
		return AnalysisStage.FETCH_BODY;
	}

	/**
	 * 공시 한 건의 본문을 받아 저장한다.
	 *
	 * @throws IllegalStateException 공시가 없거나, 아직 유형이 붙지 않았거나, 본문을 받을 줄 모르는 출처일 때
	 * @throws ExternalApiException  출처 호출이 실패했거나 받은 파일을 읽지 못했을 때
	 */
	@Override
	public StageResult handle(AnalysisJob job) {
		BodyTarget target = bodies.findTarget(job.disclosureId())
			.orElseThrow(() -> new IllegalStateException("공시가 없습니다: " + job.disclosureId()));
		if (target.withdrawn()) {
			return StageResult.ended().withResult("{\"skipped\":\"WITHDRAWN\"}");
		}
		if (target.typeCode() == null) {
			throw new IllegalStateException("유형이 붙지 않은 공시입니다: " + job.disclosureId());
		}
		if (!target.fetchBody()) {
			return StageResult.ended().withResult("{\"skipped\":\"TYPE_NOT_FETCHED\"}");
		}
		if (target.source() != Source.DART) {
			throw new IllegalStateException("본문을 받을 줄 모르는 출처입니다: " + target.source());
		}

		Optional<StoredBody> stored = bodies.findStored(job.disclosureId());
		if (stored.isPresent()) {
			return BodyNextStage.decide(target)
				.withResult("{\"bytes\":%d,\"files\":%d,\"reused\":true}".formatted(stored.get().byteSize(),
					stored.get().fileCount()));
		}

		if (!dart.isConfigured()) {
			return StageResult.deferredAll(clock.instant().plus(NO_KEY_DELAY), ErrorKind.AUTH, "no-key",
				"DART 인증키가 설정되지 않았습니다");
		}
		Optional<Pause> paused = pauses.pausedNow(DartClient.SOURCE);
		if (paused.isPresent()) {
			return deferAll(paused.get());
		}

		Optional<byte[]> downloaded;
		try {
			downloaded = dart.downloadDocument(target.sourceKey());
		}
		catch (ExternalApiException e) {
			if (e.kind().pausesSource()) {
				pauses.pauseFor(e);
				Optional<Pause> pause = pauses.pausedNow(DartClient.SOURCE);
				if (pause.isPresent()) {
					return deferAll(pause.get());
				}
			}
			throw e;
		}
		if (downloaded.isEmpty()) {
			Instant now = clock.instant();
			if (target.receiptDate() != null && !target.receiptDate().isBefore(LocalDate.ofInstant(now, ClockConfig.KST))) {
				// 접수한 날에는 출처가 본문 파일을 아직 올리지 않았을 수 있다. 본문 없음으로 굳히지 않고 다시 확인한다
				return StageResult.deferred(now.plus(SAME_DAY_RECHECK), null, "014", "접수한 날이라 본문이 아직 없을 수 있습니다");
			}
			return StageResult.noBody();
		}

		byte[] zip = downloaded.get();
		List<BodyDocument> documents;
		try {
			documents = BodyArchive.unzip(zip).stream().map(BodyTextExtractor::extract).toList();
		}
		catch (BodyFormatException e) {
			throw ExternalApiException.of(DartClient.SOURCE, ErrorKind.PARSE, "body", e.getMessage());
		}
		int textChars = documents.stream().mapToInt(document -> document.text().length()).sum();
		Instant fetchedAt = clock.instant();
		long disclosureId = job.disclosureId();
		int fileCount = documents.size();
		return BodyNextStage.decide(target)
			.withResult("{\"bytes\":%d,\"files\":%d,\"format\":\"%s\",\"text_chars\":%d}".formatted(zip.length, fileCount,
				documents.getFirst().format(), textChars))
			.withWrites(() -> bodies.save(disclosureId, zip, fileCount, fetchedAt));
	}

	private static StageResult deferAll(Pause pause) {
		return StageResult.deferredAll(pause.until(), pause.kind(), pause.code(), "DART 호출을 쉬는 중입니다");
	}
}
