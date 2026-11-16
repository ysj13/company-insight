package com.companyinsight.common.dart;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * DART 공시검색(list.json) 응답.
 *
 * @param status     상태 코드. "000"이 정상.
 * @param message    상태 메시지
 * @param pageNo     현재 페이지 번호
 * @param totalCount 조건에 맞는 전체 공시 수
 * @param totalPage  전체 페이지 수
 * @param list       이 페이지의 공시. 없으면 null.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DisclosureListResponse(
		String status,
		String message,
		@JsonProperty("page_no") Integer pageNo,
		@JsonProperty("total_count") Integer totalCount,
		@JsonProperty("total_page") Integer totalPage,
		List<Item> list) {

	/** 오류·빈 결과(013) 응답에는 페이지 정보가 없다. Jackson 3는 없는 값을 int에 넣지 않으므로 Integer로 받는다. */
	public DisclosureListResponse {
		pageNo = pageNo == null ? 0 : pageNo;
		totalCount = totalCount == null ? 0 : totalCount;
		totalPage = totalPage == null ? 0 : totalPage;
	}

	/** 이 페이지의 공시 목록. 없으면 빈 목록. */
	public List<Item> items() {
		return list == null ? List.of() : list;
	}

	/**
	 * 공시 한 건.
	 *
	 * @param corpCode    DART 고유번호
	 * @param corpName    회사명
	 * @param stockCode   종목코드. 비상장사는 공백.
	 * @param corpCls     법인 구분. Y 유가증권, K 코스닥, N 코넥스, E 기타.
	 * @param reportName  보고서명. 뒤에 공백이 붙어 온다.
	 * @param receiptNo   접수번호 (14자리)
	 * @param filerName   공시 제출인
	 * @param receiptDate 접수일 (yyyyMMdd)
	 * @param rm          비고
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Item(
			@JsonProperty("corp_code") String corpCode,
			@JsonProperty("corp_name") String corpName,
			@JsonProperty("stock_code") String stockCode,
			@JsonProperty("corp_cls") String corpCls,
			@JsonProperty("report_nm") String reportName,
			@JsonProperty("rcept_no") String receiptNo,
			@JsonProperty("flr_nm") String filerName,
			@JsonProperty("rcept_dt") String receiptDate,
			String rm) {
	}
}
