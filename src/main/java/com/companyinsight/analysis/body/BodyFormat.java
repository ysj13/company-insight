package com.companyinsight.analysis.body;

/**
 * 공시 본문 파일의 형식. 제출처에 따라 갈린다.
 */
public enum BodyFormat {

	/** HTML. 거래소에 낸 공시 */
	HTML,

	/** DART 전용 XML({@code <DOCUMENT>}). 금융위원회·공정거래위원회에 낸 문서 */
	XML
}
