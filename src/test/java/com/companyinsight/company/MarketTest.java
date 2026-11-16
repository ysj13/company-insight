package com.companyinsight.company;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarketTest {

	@Test
	void DART_법인_구분_코드로_시장을_찾는다() {
		assertThat(Market.fromDartCode("Y")).contains(Market.KOSPI);
		assertThat(Market.fromDartCode("K")).contains(Market.KOSDAQ);
		assertThat(Market.fromDartCode("N")).contains(Market.KONEX);
	}

	@Test
	void 기타_법인이나_모르는_코드는_상장_시장이_아니다() {
		assertThat(Market.fromDartCode("E")).isEmpty();
		assertThat(Market.fromDartCode("")).isEmpty();
		assertThat(Market.fromDartCode(null)).isEmpty();
	}
}
