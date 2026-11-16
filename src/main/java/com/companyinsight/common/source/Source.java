package com.companyinsight.common.source;

/**
 * 회사 정보와 공시를 받아 오는 출처.
 *
 * <p>회사와 공시는 "출처 + 그 출처에서의 번호"로 구분한다. 나라마다 공시 시스템이 달라
 * 번호 체계가 서로 겹칠 수 있기 때문이다. DB의 {@code company.source}, {@code disclosure.source}에 상수 이름을 저장한다.
 * 수집 작업 이름을 담는 {@code collection_run.source}와는 다른 값이다.
 */
public enum Source {

	/** 금융감독원 전자공시시스템. 회사 번호는 고유번호(8자리), 공시 번호는 접수번호(14자리) */
	DART("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=");

	private final String documentUrlPrefix;

	Source(String documentUrlPrefix) {
		this.documentUrlPrefix = documentUrlPrefix;
	}

	/**
	 * 공시 원문을 볼 수 있는 출처의 주소.
	 *
	 * @param sourceKey 이 출처의 공시 번호
	 */
	public String documentUrl(String sourceKey) {
		return documentUrlPrefix + sourceKey;
	}
}
