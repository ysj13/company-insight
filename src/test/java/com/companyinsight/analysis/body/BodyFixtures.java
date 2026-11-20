package com.companyinsight.analysis.body;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * 테스트에 쓰는 실제 공시 본문. DART의 공시서류원본파일 응답(zip)을 그대로 받아 둔 것이다.
 */
final class BodyFixtures {

	/** 삼성전자 2025-11-28 기타 경영사항(자율공시). 거래소 공시(HTML), zip 1,469바이트 */
	static final String VOLUNTARY = "20251128800899";

	/** 현대자동차 2026-08-24 [기재정정]생산중단. 거래소 공시(HTML), 맨 위에 정정 표가 있다. zip 2,588바이트 */
	static final String CORRECTION = "20260824800707";

	/** 삼성전자 2026-01-26 주요사항보고서(자기주식처분결정). 금융위 문서(DART 전용 XML), zip 5,198바이트 */
	static final String TREASURY_DISPOSAL = "20260126000001";

	private BodyFixtures() {
	}

	/** 접수번호로 받아 둔 zip을 읽는다. */
	static byte[] zip(String receiptNo) {
		try (InputStream in = BodyFixtures.class.getResourceAsStream("/dart/body/" + receiptNo + ".zip")) {
			if (in == null) {
				throw new IllegalArgumentException("받아 둔 본문이 없습니다: " + receiptNo);
			}
			return in.readAllBytes();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** 받아 둔 zip의 첫 문서를 글자로 읽는다. */
	static BodyDocument document(String receiptNo) {
		return BodyTextExtractor.extract(BodyArchive.unzip(zip(receiptNo)).getFirst());
	}
}
