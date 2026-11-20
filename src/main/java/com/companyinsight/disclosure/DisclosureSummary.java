package com.companyinsight.disclosure;

import com.companyinsight.common.source.Source;
import java.time.LocalDate;

/**
 * 공시 목록의 한 줄.
 *
 * <p>공시 원문은 담지 않는다. 제목과 분류 결과, 그리고 출처의 원문으로 가는 링크만 보여준다.
 *
 * @param source      공시를 받은 출처
 * @param sourceKey   출처의 공시 번호. DART는 접수번호
 * @param receiptDate 접수일
 * @param companyName 회사명
 * @param stockCode   종목코드
 * @param reportName  보고서명 (공시 제목)
 * @param typeName    공시 유형의 이름. 아직 분류하지 않은 공시는 null
 * @param tone        판정. 아직 판정하지 않았으면 null
 * @param impact      영향도. 호재·악재일 때만 값이 있다
 * @param correction  이미 낸 공시의 정정·첨부 추가인지
 * @param withdrawn   철회된 공시인지
 */
public record DisclosureSummary(Source source, String sourceKey, LocalDate receiptDate, String companyName,
		String stockCode, String reportName, String typeName, Tone tone, Integer impact, boolean correction, boolean withdrawn) {

	/** 영향도가 이 값 이상이면 "큼"으로 보여준다. */
	private static final int HIGH_IMPACT = 3;

	/** 판정을 목록 조건과 같은 이름으로. 판정하지 않은 공시는 {@link ToneFilter#UNDECIDED}. */
	public ToneFilter toneFilter() {
		return ToneFilter.of(tone);
	}

	/**
	 * 영향도를 말로 바꾼 값. 숫자만 보여주면 뜻을 알기 어려워 화면에는 이 말을 쓴다.
	 *
	 * @return 작음, 보통, 큼. 영향도가 없으면 null
	 */
	public String impactLabel() {
		if (impact == null) {
			return null;
		}
		if (impact >= HIGH_IMPACT) {
			return "큼";
		}
		return impact == 2 ? "보통" : "작음";
	}

	/** 출처에서 공시 원문을 볼 수 있는 주소. DART는 공시 뷰어. */
	public String originalUrl() {
		return source.documentUrl(sourceKey);
	}
}
