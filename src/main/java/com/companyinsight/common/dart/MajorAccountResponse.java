package com.companyinsight.common.dart;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * DART 단일회사 주요계정(fnlttSinglAcnt.json) 응답. 보고서 한 건의 주요 재무 계정이 들어 있다.
 *
 * @param status  상태 코드. "000"이 정상.
 * @param message 상태 메시지
 * @param list    계정 목록. 연결재무제표와 별도재무제표의 계정이 함께 온다. 없으면 null.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MajorAccountResponse(String status, String message, List<Item> list) {

	/** 계정 목록. 없으면 빈 목록. */
	public List<Item> items() {
		return list == null ? List.of() : list;
	}

	/**
	 * 계정 한 줄. 금액은 쉼표가 들어간 문자열로 온다(예: "79,098,731,000,000").
	 *
	 * @param receiptNo             금액의 근거가 된 보고서의 접수번호
	 * @param accountName           계정명 (예: 매출액, 영업이익, 당기순이익(손실))
	 * @param statementDiv          CFS 연결재무제표, OFS 별도재무제표
	 * @param sheetDiv              BS 재무상태표, IS 손익계산서
	 * @param currentAmount         당기 금액. 분기·반기보고서의 손익 계정은 그 분기 3개월 금액
	 * @param currentAddAmount      당기 누적 금액. 사업보고서와 재무상태표 계정에는 없다
	 * @param priorAmount           전기 금액. 분기·반기보고서의 손익 계정은 전년 같은 분기 3개월 금액
	 * @param priorAddAmount        전기 누적 금액. 사업보고서와 재무상태표 계정에는 없다
	 * @param order                 계정 표시 순서. 숫자 문자열
	 * @param currency              통화 (예: KRW)
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Item(
			@JsonProperty("rcept_no") String receiptNo,
			@JsonProperty("account_nm") String accountName,
			@JsonProperty("fs_div") String statementDiv,
			@JsonProperty("sj_div") String sheetDiv,
			@JsonProperty("thstrm_amount") String currentAmount,
			@JsonProperty("thstrm_add_amount") String currentAddAmount,
			@JsonProperty("frmtrm_amount") String priorAmount,
			@JsonProperty("frmtrm_add_amount") String priorAddAmount,
			@JsonProperty("ord") String order,
			String currency) {
	}
}
