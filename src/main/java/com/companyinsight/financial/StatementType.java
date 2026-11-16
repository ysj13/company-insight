package com.companyinsight.financial;

import java.util.Optional;

/** 재무제표 구분. */
public enum StatementType {

	/** 연결재무제표. 종속회사를 합친 실적 */
	CONSOLIDATED("CFS"),

	/** 별도(개별)재무제표. 회사 한 곳만의 실적 */
	SEPARATE("OFS");

	private final String dartCode;

	StatementType(String dartCode) {
		this.dartCode = dartCode;
	}

	/** DART 개별/연결 구분 코드 (fs_div). */
	public String dartCode() {
		return dartCode;
	}

	/**
	 * DART 개별/연결 구분 코드로 찾는다.
	 *
	 * @return 모르는 코드나 null이면 비어 있음
	 */
	public static Optional<StatementType> fromDartCode(String dartCode) {
		for (StatementType type : values()) {
			if (type.dartCode.equals(dartCode)) {
				return Optional.of(type);
			}
		}
		return Optional.empty();
	}
}
