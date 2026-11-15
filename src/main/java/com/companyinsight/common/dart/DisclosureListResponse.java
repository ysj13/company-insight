package com.companyinsight.common.dart;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** DART 공시검색(list.json) 응답. */
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

	public List<Item> items() {
		return list == null ? List.of() : list;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Item(
			@JsonProperty("corp_code") String corpCode,
			@JsonProperty("corp_name") String corpName,
			@JsonProperty("stock_code") String stockCode,
			/** Y 유가증권, K 코스닥, N 코넥스, E 기타 */
			@JsonProperty("corp_cls") String corpCls,
			@JsonProperty("report_nm") String reportName,
			@JsonProperty("rcept_no") String receiptNo,
			@JsonProperty("flr_nm") String filerName,
			@JsonProperty("rcept_dt") String receiptDate,
			String rm) {
	}
}
