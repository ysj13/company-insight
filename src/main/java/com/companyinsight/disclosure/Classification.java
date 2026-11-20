package com.companyinsight.disclosure;

import java.util.List;

/**
 * 공시 한 건을 제목 대응표로 분류한 결과.
 *
 * @param typeCode        유형 번호. 대응표에 없는 제목이면 미분류({@link DisclosureType#UNCLASSIFIED})
 * @param titleRuleId     걸린 제목 규칙의 ID. 대응표에 없는 제목이면 null
 * @param caseCode        제목이 정한 경우. 없으면 null
 * @param normalizedTitle 대응표를 찾을 때 쓴 제목. 머리말, 공백, 기간 표기, 속성 꼬리를 뗀 것
 * @param attributes      제목에서 읽은 속성 (자회사의 공시, 자율공시 등). 없으면 빈 목록
 * @param tone            판정. 제목만으로 정할 수 없으면 null (본문을 읽어 판정할 대상)
 * @param classifiedBy    판정 주체. tone이 null이면 null
 * @param correction      이미 낸 공시의 후속 공시(정정, 첨부 추가 등)인지
 * @param withdrawn       철회된 공시인지
 */
public record Classification(String typeCode, Long titleRuleId, String caseCode, String normalizedTitle,
		List<String> attributes, Tone tone, ClassifiedBy classifiedBy, boolean correction, boolean withdrawn) {

	/** 규칙으로 판정이 끝났는지. false면 본문을 읽어 판정해야 한다. */
	public boolean decided() {
		return tone != null;
	}
}
