package com.companyinsight.financial;

import com.companyinsight.common.dart.DartText;
import com.companyinsight.common.dart.MajorAccountResponse;
import com.companyinsight.common.dart.MajorAccountResponse.Item;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 저장된 재무 응답(DART 주요계정)에서 화면과 계산에 쓰는 계정의 금액을 읽는다.
 *
 * <p>응답에는 재무상태표를 포함해 계정이 여러 줄 들어 있다. 그중 손익계산서의 매출액·영업이익·당기순이익만,
 * 연결과 별도를 따로 읽는다. 읽는 규칙을 여기서 고치면 다음 조회부터 바로 반영된다. 저장된 응답은 그대로다.
 */
public final class FinancialExtractor {

	/** 손익계산서 구분 코드 (sj_div). */
	private static final String INCOME_STATEMENT = "IS";

	private static final Comparator<Item> BY_ORDER = Comparator.comparingInt(FinancialExtractor::order);

	private FinancialExtractor() {
	}

	/**
	 * 저장된 응답 한 건에서 계정의 금액을 읽는다.
	 *
	 * @param report 저장된 응답
	 * @return 읽은 결과
	 * @throws tools.jackson.core.JacksonException 저장된 내용이 주요계정 응답 형식이 아닌 경우
	 */
	public static Extraction extract(FinancialReport report) {
		MajorAccountResponse response = MajorAccountResponse.fromJson(report.payload());
		// 저장된 내용이 JSON null이면 해석 결과가 null이다. 계정이 없는 응답으로 본다
		return extract(report, response == null ? List.of() : response.items());
	}

	/**
	 * 응답의 계정 줄에서 금액을 읽는다. 응답 본문을 이미 해석해 둔 경우에 쓴다.
	 *
	 * <p>같은 계정이 두 줄 오면(당기순이익이 같은 금액으로 두 줄 온다) 표시 순서가 앞선 줄을 쓴다.
	 * 당기 금액, 통화, 접수번호를 읽을 수 없는 줄은 건너뛴다.
	 *
	 * @param report 응답이 속한 보고서. 사업연도, 보고서 종류, 출처를 여기서 가져온다
	 * @param items  응답의 계정 줄
	 */
	static Extraction extract(FinancialReport report, List<Item> items) {
		Map<String, FinancialRecord> byKey = new LinkedHashMap<>();
		int unreadable = 0;
		int conflicting = 0;
		for (Item item : items.stream().filter(Objects::nonNull).sorted(BY_ORDER).toList()) {
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
				unreadable++;
				continue;
			}
			FinancialRecord record = new FinancialRecord(report.fiscalYear(), report.reportType(), statementType.get(),
				account.get(), amount.get(), DartText.parseAmount(item.currentAddAmount()).orElse(null),
				DartText.parseAmount(item.priorAmount()).orElse(null),
				DartText.parseAmount(item.priorAddAmount()).orElse(null), currency, report.source(), receiptNo);
			FinancialRecord first = byKey.putIfAbsent(statementType.get() + "/" + account.get(), record);
			if (first != null && !sameAmounts(first, record)) {
				conflicting++;
			}
		}
		return new Extraction(new ArrayList<>(byKey.values()), unreadable, conflicting);
	}

	private static boolean sameAmounts(FinancialRecord a, FinancialRecord b) {
		return same(a.amount(), b.amount()) && same(a.cumulativeAmount(), b.cumulativeAmount())
				&& same(a.priorAmount(), b.priorAmount())
				&& same(a.priorCumulativeAmount(), b.priorCumulativeAmount());
	}

	private static boolean same(BigDecimal a, BigDecimal b) {
		return a == null || b == null ? Objects.equals(a, b) : a.compareTo(b) == 0;
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
	 * 응답 한 건에서 읽은 결과.
	 *
	 * @param records     읽은 계정의 금액. 연결과 별도가 따로 들어 있다
	 * @param unreadable  읽는 계정인데 금액·통화·접수번호를 읽지 못해 건너뛴 줄 수
	 * @param conflicting 같은 계정이 두 줄인데 금액이 달라, 뒤의 줄을 버린 수
	 */
	public record Extraction(List<FinancialRecord> records, int unreadable, int conflicting) {
	}
}
