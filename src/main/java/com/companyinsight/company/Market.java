package com.companyinsight.company;

import java.util.Optional;

/**
 * 상장 시장 구분.
 *
 * <p>DB의 {@code company.market}에는 DART 법인 구분 코드({@link #dartCode()})를 그대로 저장한다.
 * DART 문서와 바로 대조할 수 있게 하기 위해서다.
 */
public enum Market {

	/** 유가증권시장 */
	KOSPI("Y"),

	/** 코스닥시장 */
	KOSDAQ("K"),

	/** 코넥스시장 */
	KONEX("N");

	private final String dartCode;

	Market(String dartCode) {
		this.dartCode = dartCode;
	}

	/** DART 법인 구분 코드 (corp_cls). DB에 저장하는 값. */
	public String dartCode() {
		return dartCode;
	}

	/**
	 * DART 법인 구분 코드로 시장을 찾는다.
	 *
	 * @return 상장 시장이면 해당 값, 기타 법인(E)이나 모르는 코드면 비어 있음
	 */
	public static Optional<Market> fromDartCode(String code) {
		for (Market market : values()) {
			if (market.dartCode.equals(code)) {
				return Optional.of(market);
			}
		}
		return Optional.empty();
	}
}
