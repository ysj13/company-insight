package com.companyinsight.financial;

import com.companyinsight.common.dart.DartClient;
import com.companyinsight.common.dart.DartText;
import com.companyinsight.common.dart.MajorAccountResponse;
import com.companyinsight.common.dart.MajorAccountResponse.Item;
import com.companyinsight.common.external.SourcePauseRegistry;
import com.companyinsight.common.source.Source;
import com.companyinsight.company.CompanyRepository;
import com.companyinsight.ops.CollectionRunRecorder;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * DART 주요계정으로 회사 한 곳의 재무(매출액, 영업이익, 당기순이익)를 수집한다.
 *
 * <p>사업연도마다 보고서 네 종류(1분기, 반기, 3분기, 사업보고서)를 한 번씩 조회한다. 아직 제출되지 않은 보고서는
 * 데이터가 없어 건너뛴다. 받은 값은 그대로 저장하고, 4분기 금액이나 증감은 조회할 때 계산한다
 * ({@link PerformanceCalculator}).
 */
@Service
public class FinancialCollector {

	private static final Logger log = LoggerFactory.getLogger(FinancialCollector.class);

	/** collection_run에 남기는 작업 이름. */
	static final String JOB = "DART_FINANCIAL";

	/** DART 주요계정이 제공되는 첫 사업연도. */
	static final int FIRST_YEAR = 2015;

	/** 손익계산서 구분 코드 (sj_div). */
	private static final String INCOME_STATEMENT = "IS";

	private static final Comparator<Item> BY_ORDER = Comparator.comparingInt(FinancialCollector::order);

	private final DartClient dart;
	private final CompanyRepository companies;
	private final FinancialRepository repository;
	private final CollectionRunRecorder recorder;
	private final SourcePauseRegistry pauses;

	public FinancialCollector(DartClient dart, CompanyRepository companies, FinancialRepository repository,
			CollectionRunRecorder recorder, SourcePauseRegistry pauses) {
		this.dart = dart;
		this.companies = companies;
		this.repository = repository;
		this.recorder = recorder;
		this.pauses = pauses;
	}

	/**
	 * 회사 한 곳의 재무를 사업연도 범위로 받아 저장한다. 여러 번 실행해도 결과가 같다.
	 *
	 * @param companyId 회사 ID
	 * @param fromYear  시작 사업연도 (포함). 2015 이상
	 * @param toYear    끝 사업연도 (포함)
	 * @return 수집 결과. 키가 없거나 DART가 정지 중이면 건너뛴 결과
	 * @throws IllegalArgumentException 연도 범위가 틀렸거나 DART에서 받은 회사가 아닌 경우
	 * @throws com.companyinsight.common.external.ExternalApiException DART 호출이 실패한 경우. 그때까지 받은 보고서는 저장되어 있다
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
			for (int year = fromYear; year <= toYear; year++) {
				for (ReportType reportType : ReportType.values()) {
					MajorAccountResponse response = dart.listMajorAccounts(corpCode, year, reportType.dartCode());
					progress.addFetched(response.items().size());
					List<FinancialRecord> records = toRecords(year, reportType, response.items());
					if (!response.items().isEmpty()) {
						reports++;
						if (records.isEmpty()) {
							log.warn("재무 수집: 회사 {} {}년 {} 응답에 저장할 계정이 없습니다", companyId, year, reportType);
						}
					}
					progress.addSaved(repository.upsertAll(companyId, records));
				}
			}
			log.info("재무 수집(회사 {} {}~{}): 보고서 {}건, 받은 계정 {}줄, 추가·변경 {}행", companyId, fromYear, toYear,
				reports, progress.fetched(), progress.saved());
			return new CollectResult(false, reports, progress.fetched(), progress.saved());
		});
	}

	/**
	 * 주요계정 응답에서 저장할 행을 고른다. 손익계산서의 매출액·영업이익·당기순이익만, 연결과 별도를 따로 남긴다.
	 *
	 * <p>같은 계정이 두 줄 오면(당기순이익이 같은 금액으로 두 줄 온다) 표시 순서가 앞선 줄을 쓴다.
	 * 당기 금액이나 통화를 읽을 수 없는 줄은 건너뛴다.
	 */
	static List<FinancialRecord> toRecords(int fiscalYear, ReportType reportType, List<Item> items) {
		Map<String, FinancialRecord> byKey = new LinkedHashMap<>();
		for (Item item : items.stream().sorted(BY_ORDER).toList()) {
			if (!INCOME_STATEMENT.equals(item.sheetDiv())) {
				continue;
			}
			Optional<StatementType> statementType = StatementType.fromDartCode(item.statementDiv());
			Optional<AccountType> account = AccountType.fromDartName(item.accountName());
			if (statementType.isEmpty() || account.isEmpty()) {
				continue;
			}
			Optional<BigDecimal> amount = DartText.parseAmount(item.currentAmount());
			String currency = DartText.blankToNull(item.currency());
			String receiptNo = DartText.blankToNull(item.receiptNo());
			if (amount.isEmpty() || currency == null || currency.length() != 3 || receiptNo == null) {
				log.warn("재무 수집: {}년 {} {} {} 줄을 읽을 수 없어 건너뜁니다", fiscalYear, reportType, statementType.get(),
					account.get());
				continue;
			}
			FinancialRecord record = new FinancialRecord(fiscalYear, reportType, statementType.get(), account.get(),
				amount.get(), DartText.parseAmount(item.currentAddAmount()).orElse(null),
				DartText.parseAmount(item.priorAmount()).orElse(null),
				DartText.parseAmount(item.priorAddAmount()).orElse(null), currency, Source.DART,
				DartText.clean(receiptNo, 40));
			String key = statementType.get() + "/" + account.get();
			FinancialRecord first = byKey.putIfAbsent(key, record);
			if (first != null && first.amount().compareTo(record.amount()) != 0) {
				log.warn("재무 수집: {}년 {} {} {} 계정이 두 줄인데 금액이 다릅니다. 앞선 줄을 씁니다", fiscalYear, reportType,
					statementType.get(), account.get());
			}
		}
		return new ArrayList<>(byKey.values());
	}

	/** 표시 순서(ord). 숫자가 아니면 맨 뒤로 보낸다. */
	private static int order(Item item) {
		try {
			return Integer.parseInt(item.order().strip());
		}
		catch (RuntimeException e) {
			return Integer.MAX_VALUE;
		}
	}

	/**
	 * 수집 결과.
	 *
	 * @param skipped 키가 없거나 소스가 정지 중이라 실행하지 않았으면 true
	 * @param reports 데이터가 있던 보고서 수
	 * @param fetched DART에서 받은 계정 줄 수 (저장하지 않는 계정 포함)
	 * @param saved   새로 저장하거나 값이 바뀐 행 수
	 */
	public record CollectResult(boolean skipped, int reports, int fetched, int saved) {
		static CollectResult skip() {
			return new CollectResult(true, 0, 0, 0);
		}
	}
}
