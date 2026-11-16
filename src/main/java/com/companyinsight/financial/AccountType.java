package com.companyinsight.financial;

import java.util.Optional;
import java.util.Set;

/**
 * 저장된 재무 응답에서 읽는 계정.
 *
 * <p>DART 주요계정의 계정명과 맞춘다. 계정명은 공백을 모두 없애고 비교한다.
 * 여기에 없는 계정명(자산총계, 법인세차감전 순이익 등)은 응답에 저장되어 있지만 읽지 않는다.
 */
public enum AccountType {

	/** 매출액 */
	REVENUE(Set.of("매출액")),

	/** 영업이익 */
	OPERATING_INCOME(Set.of("영업이익")),

	/** 당기순이익. 연결재무제표에서는 비지배지분을 포함한 전체 순이익 */
	NET_INCOME(Set.of("당기순이익(손실)", "당기순이익"));

	private final Set<String> dartNames;

	AccountType(Set<String> dartNames) {
		this.dartNames = dartNames;
	}

	/**
	 * DART 주요계정의 계정명으로 찾는다.
	 *
	 * @param accountName 계정명 (account_nm). 공백이 섞여 있어도 된다
	 * @return 읽지 않는 계정이나 null이면 비어 있음
	 */
	public static Optional<AccountType> fromDartName(String accountName) {
		if (accountName == null) {
			return Optional.empty();
		}
		String compact = accountName.replaceAll("\\s+", "");
		for (AccountType type : values()) {
			if (type.dartNames.contains(compact)) {
				return Optional.of(type);
			}
		}
		return Optional.empty();
	}
}
