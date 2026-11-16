package com.companyinsight.disclosure;

/**
 * 공시 종류. 공시 피드를 종류별로 나눠 보여줄 때 쓴다.
 *
 * <p>DART 보고서명은 수백 가지라 그대로 쓰지 않고 화면에서 구분할 만한 묶음으로 줄였다.
 * DB의 {@code disclosure.kind}에 이름 그대로 저장한다.
 */
public enum DisclosureKind {

	/** 정기보고서 (사업·반기·분기보고서) */
	PERIODIC("정기보고서"),

	/** 실적 (잠정실적, 실적 전망, 손익구조 변동) */
	EARNINGS("실적"),

	/** 주요사항 (자기주식, 증자, 합병, 계약, 소송 등 회사의 주요 결정과 사건) */
	MAJOR("주요사항"),

	/** 지분 (임원·주요주주 소유 보고, 대량보유 보고) */
	OWNERSHIP("지분"),

	/** 기업설명회와 실적 발표 일정 */
	IR("기업설명회·일정"),

	/** 증권 발행 서류 (증권신고서, 투자설명서, 발행실적보고서) */
	ISSUANCE("증권 발행"),

	/** 주주총회와 절차성 공시 (소집, 기준일, 본점 이전 등) */
	GOVERNANCE("주주총회·절차"),

	/** 거래소 시장조치 (시장안내, 불성실공시, 매매거래정지, 조회공시) */
	MARKET("시장조치"),

	/** 위에 속하지 않는 공시 */
	ETC("기타");

	private final String label;

	DisclosureKind(String label) {
		this.label = label;
	}

	/** 화면에 보여주는 이름 */
	public String label() {
		return label;
	}
}
