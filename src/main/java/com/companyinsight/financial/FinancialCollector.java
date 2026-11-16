package com.companyinsight.financial;

import com.companyinsight.common.dart.DartClient;
import com.companyinsight.common.dart.MajorAccountResponse;
import com.companyinsight.common.dart.MajorAccountResponse.Item;
import com.companyinsight.common.external.ErrorKind;
import com.companyinsight.common.external.ExternalApiException;
import com.companyinsight.common.external.SourcePauseRegistry;
import com.companyinsight.common.source.Source;
import com.companyinsight.company.CompanyRepository;
import com.companyinsight.ops.CollectionRunRecorder;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * DART 주요계정으로 회사 한 곳의 재무를 수집한다.
 *
 * <p>사업연도마다 보고서 네 종류(1분기, 반기, 3분기, 사업보고서)를 한 번씩 조회하고, 응답을 보고서 단위로
 * 그대로 저장한다. 계정을 고르거나 금액을 계산하지 않는다. 그 일은 조회할 때 한다
 * ({@link FinancialExtractor}, {@link PerformanceCalculator}).
 *
 * <p>아직 제출되지 않은 보고서는 데이터가 없어 건너뛴다. 계정이 하나도 없는 응답과, 읽을 수 있는 계정이 없는데
 * 이미 저장된 응답에서는 읽을 수 있는 경우는 저장하지 않아, 쓸 수 있는 응답을 덮어쓰지 않는다.
 */
@Service
public class FinancialCollector {

	private static final Logger log = LoggerFactory.getLogger(FinancialCollector.class);

	/** collection_run에 남기는 작업 이름. */
	static final String JOB = "DART_FINANCIAL";

	/** DART 주요계정이 제공되는 첫 사업연도. */
	static final int FIRST_YEAR = 2015;

	/**
	 * 보고서 한 건에만 해당하는 오류. 그 보고서만 건너뛰고 나머지를 계속 받는다.
	 * 한도 초과, 인증 오류, 재시도 뒤에도 남은 일시 오류는 다음 호출도 실패하므로 수집을 멈춘다.
	 */
	private static final Set<ErrorKind> SKIPPABLE = Set.of(ErrorKind.PARSE, ErrorKind.BAD_REQUEST, ErrorKind.UNKNOWN);

	private final DartClient dart;
	private final CompanyRepository companies;
	private final FinancialRepository repository;
	private final CollectionRunRecorder recorder;
	private final SourcePauseRegistry pauses;
	private final Clock clock;

	public FinancialCollector(DartClient dart, CompanyRepository companies, FinancialRepository repository,
			CollectionRunRecorder recorder, SourcePauseRegistry pauses, Clock clock) {
		this.dart = dart;
		this.companies = companies;
		this.repository = repository;
		this.recorder = recorder;
		this.pauses = pauses;
		this.clock = clock;
	}

	/**
	 * 종목코드로 지정한 회사들의 재무를 올해부터 거슬러 받는다. 한 회사가 실패해도 나머지는 계속한다.
	 *
	 * <p>종목코드에 해당하는 회사가 없거나 여럿이면(상장폐지된 회사의 종목코드가 남아 있는 경우) 그 종목코드는 건너뛴다.
	 * 기업 목록이 아직 없는 새 DB에서는 받을 회사를 찾지 못한다.
	 *
	 * @param stockCodes 종목코드 목록
	 * @param years      받을 사업연도 수. 올해를 포함해 거슬러 올라간다. 2015년 이전은 받지 않는다
	 */
	public void collectByStockCodes(List<String> stockCodes, int years) {
		int toYear = LocalDate.now(clock).getYear();
		int fromYear = Math.max(FIRST_YEAR, toYear - years + 1);
		for (String stockCode : stockCodes) {
			List<Long> ids = companies.findIdsByStockCode(stockCode);
			if (ids.size() != 1) {
				log.warn("재무 수집: 종목코드 {}에 해당하는 회사가 {}곳이라 건너뜁니다", stockCode, ids.size());
				continue;
			}
			try {
				collect(ids.getFirst(), fromYear, toYear);
			}
			catch (RuntimeException e) {
				// DART 호출 실패는 collection_run에도 남는다
				log.warn("재무 수집 실패(종목코드 {}): {}", stockCode, e.getMessage());
			}
		}
	}

	/**
	 * 회사 한 곳의 재무를 사업연도 범위로 받아 저장한다. 여러 번 실행해도 결과가 같다.
	 *
	 * @param companyId 회사 ID
	 * @param fromYear  시작 사업연도 (포함). 2015 이상
	 * @param toYear    끝 사업연도 (포함)
	 * @return 수집 결과. 키가 없거나 DART가 정지 중이면 건너뛴 결과
	 * @throws IllegalArgumentException 연도 범위가 틀렸거나 DART에서 받은 회사가 아닌 경우
	 * @throws ExternalApiException 한도 초과·인증 오류처럼 다음 호출도 실패할 오류. 그때까지 받은 보고서는 저장되어 있다.
	 *                              보고서 한 건의 응답만 잘못된 경우는 그 보고서만 건너뛰고 계속한다
	 */
	public CollectResult collect(long companyId, int fromYear, int toYear) {
		if (fromYear < FIRST_YEAR || fromYear > toYear) {
			throw new IllegalArgumentException("사업연도 범위가 틀림: " + fromYear + "~" + toYear);
		}
		String corpCode = companies.findSourceKey(companyId, Source.DART)
			.orElseThrow(() -> new IllegalArgumentException("DART에서 받은 회사가 아님: " + companyId));
		if (!dart.isConfigured()) {
			log.warn("DART_API_KEY가 없어 재무 수집을 건너뜁니다");
			return CollectResult.skip();
		}
		if (pauses.pausedNow(DartClient.SOURCE).isPresent()) {
			log.warn("DART 정지 중이라 재무 수집을 건너뜁니다");
			return CollectResult.skip();
		}
		return recorder.record(JOB, progress -> {
			int reports = 0;
			int failedReports = 0;
			for (int year = fromYear; year <= toYear; year++) {
				for (ReportType reportType : ReportType.values()) {
					Optional<String> body;
					try {
						body = dart.fetchMajorAccounts(corpCode, year, reportType.dartCode());
					}
					catch (ExternalApiException e) {
						if (!SKIPPABLE.contains(e.kind())) {
							throw e;
						}
						failedReports++;
						log.warn("재무 수집: 회사 {} {}년 {} 보고서를 받지 못해 건너뜁니다: {}", companyId, year, reportType,
							e.getMessage());
						continue;
					}
					if (body.isEmpty()) {
						continue;
					}
					FinancialReport report = new FinancialReport(companyId, Source.DART, year, reportType, body.get());
					progress.addFetched(1);
					String problem = store(report, progress);
					if (problem == null) {
						reports++;
					}
					else {
						failedReports++;
						log.warn("재무 수집: 회사 {} {}년 {} 응답을 저장하지 않고 건너뜁니다: {}", companyId, year, reportType,
							problem);
					}
				}
			}
			log.info("재무 수집(회사 {} {}~{}): 받은 응답 {}건, 저장 {}건(새로 저장·변경 {}건), 건너뛴 보고서 {}건", companyId,
				fromYear, toYear, progress.fetched(), reports, progress.saved(), failedReports);
			return new CollectResult(false, reports, failedReports, progress.fetched(), progress.saved());
		});
	}

	/**
	 * 응답 한 건을 저장한다. 저장하면 안 되는 응답이면 저장하지 않고 이유를 돌려준다.
	 *
	 * <ul>
	 * <li>계정이 하나도 없는 응답: 저장하지 않는다.</li>
	 * <li>읽을 수 있는 계정이 없는데, 이미 저장된 응답에서는 읽을 수 있는 경우: 덮어쓰지 않는다.
	 * 처음 받는 응답이면 읽을 수 있는 계정이 없어도 저장한다. 읽는 규칙을 나중에 고치면 읽을 수 있기 때문이다.</li>
	 * <li>DB가 JSON으로 받지 못하는 내용(NUL 문자 등): 그 보고서만 건너뛴다.</li>
	 * </ul>
	 *
	 * @return 저장했으면 null, 저장하지 않았으면 이유
	 */
	private String store(FinancialReport report, CollectionRunRecorder.Progress progress) {
		FinancialExtractor.Extraction extraction;
		int lines;
		try {
			MajorAccountResponse response = MajorAccountResponse.fromJson(report.payload());
			List<Item> items = response == null ? List.of() : response.items();
			lines = items.size();
			extraction = FinancialExtractor.extract(report, items);
		}
		catch (RuntimeException e) {
			return "응답을 읽지 못함 (" + e.getClass().getSimpleName() + ")";
		}
		if (lines == 0) {
			return "응답에 계정이 없음";
		}
		if (extraction.records().isEmpty() && storedIsReadable(report)) {
			return "읽을 수 있는 계정이 없어, 이미 저장된 응답을 덮어쓰지 않음";
		}
		try {
			if (repository.upsert(report)) {
				progress.addSaved(1);
			}
		}
		catch (DataIntegrityViolationException e) {
			// 값의 형식 문제(SQLState 22로 시작)만 이 보고서의 문제로 본다. 참조 제약 위반 같은 다른 오류는 숨기지 않는다
			if (e.getMostSpecificCause() instanceof SQLException sql && sql.getSQLState() != null
					&& sql.getSQLState().startsWith("22")) {
				return "DB가 받지 못하는 내용 (SQLState " + sql.getSQLState() + ")";
			}
			throw e;
		}
		if (extraction.records().isEmpty() || extraction.unreadable() > 0 || extraction.conflicting() > 0) {
			log.warn("재무 수집: 회사 {} {}년 {} 응답 {}줄에서 읽은 계정 {}개, 읽지 못한 줄 {}개, 금액이 다른 중복 줄 {}개",
				report.companyId(), report.fiscalYear(), report.reportType(), lines, extraction.records().size(),
				extraction.unreadable(), extraction.conflicting());
		}
		return null;
	}

	/** 같은 보고서의 응답이 이미 저장되어 있고, 거기서 계정을 읽을 수 있는지. */
	private boolean storedIsReadable(FinancialReport report) {
		return repository.find(report.companyId(), report.source(), report.fiscalYear(), report.reportType())
			.map(stored -> {
				try {
					return !FinancialExtractor.extract(stored).records().isEmpty();
				}
				catch (RuntimeException e) {
					return false;
				}
			})
			.orElse(false);
	}

	/**
	 * 수집 결과.
	 *
	 * @param skipped       키가 없거나 소스가 정지 중이라 실행하지 않았으면 true
	 * @param reports       저장한 보고서 수 (내용이 그대로인 것 포함)
	 * @param failedReports 받지 못했거나 저장하지 않고 건너뛴 보고서 수
	 * @param fetched       DART에서 응답을 받은 보고서 수 (데이터가 없는 보고서 제외)
	 * @param saved         새로 저장했거나 내용이 바뀐 보고서 수
	 */
	public record CollectResult(boolean skipped, int reports, int failedReports, int fetched, int saved) {
		static CollectResult skip() {
			return new CollectResult(true, 0, 0, 0, 0);
		}
	}
}
