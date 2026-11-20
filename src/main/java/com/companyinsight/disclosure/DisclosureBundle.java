package com.companyinsight.disclosure;

/**
 * 공시 유형의 묶음. 유형 번호의 첫 글자다({@code disclosure_type.bundle}).
 * 공시 목록을 묶음으로 거를 때 쓴다. 이름은 화면 문구라 코드에 둔다.
 */
public enum DisclosureBundle {

	A("정기·절차"),
	B("실적·재무"),
	C("공급계약·기술"),
	D("자금 조달"),
	L("전환·행사·사채 취득"),
	E("주주환원·주식 수"),
	F("합병·인수·투자"),
	G("경영권·임원"),
	H("보증·대여·내부거래"),
	I("소송·사건"),
	J("시장조치"),
	R("리츠·스팩 전용"),
	K("포괄 제목"),
	Z("미분류");

	private final String label;

	DisclosureBundle(String label) {
		this.label = label;
	}

	/** 화면에 보여주는 이름 */
	public String label() {
		return label;
	}
}
