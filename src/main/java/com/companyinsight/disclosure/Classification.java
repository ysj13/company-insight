package com.companyinsight.disclosure;

/**
 * 공시 한 건을 제목 규칙으로 분류한 결과.
 *
 * @param kind         공시 종류
 * @param tone         판정. 제목만으로 정할 수 없으면 null (본문을 읽어 판정할 대상)
 * @param impact       영향도 1~3. 호재·악재일 때만 값이 있고 아니면 null
 * @param classifiedBy 판정 주체. tone이 null이면 null
 * @param correction   이미 낸 공시의 후속 공시(정정, 첨부 추가 등)인지
 * @param withdrawn    철회된 공시인지
 */
public record Classification(DisclosureKind kind, Tone tone, Integer impact, ClassifiedBy classifiedBy,
		boolean correction, boolean withdrawn) {

	/** 규칙으로 판정이 끝났는지. false면 본문을 읽어 판정해야 한다. */
	public boolean decided() {
		return tone != null;
	}
}
