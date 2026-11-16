package com.companyinsight.common.dart;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DartTextTest {

	@Test
	void clean은_NUL을_지우고_길이에_맞춰_자른다() {
		assertThat(DartText.clean("가\u0000나다라", 3)).isEqualTo("가나다");
		assertThat(DartText.clean("짧다", 10)).isEqualTo("짧다");
		assertThat(DartText.clean(null, 10)).isNull();
	}

	@Test
	void collapseSpaces는_앞뒤_공백을_없애고_연속_공백을_하나로() {
		assertThat(DartText.collapseSpaces("  주요사항보고서   (자기주식취득결정)      ")).isEqualTo("주요사항보고서 (자기주식취득결정)");
		assertThat(DartText.collapseSpaces("사업보고서\t(2025.12)\r\n 정정")).isEqualTo("사업보고서 (2025.12) 정정");
		assertThat(DartText.collapseSpaces(null)).isEmpty();
	}

	@Test
	void blankToNull() {
		assertThat(DartText.blankToNull("  ")).isNull();
		assertThat(DartText.blankToNull(null)).isNull();
		assertThat(DartText.blankToNull(" 유 ")).isEqualTo("유");
	}

	@Test
	void 날짜는_yyyyMMdd만_받는다() {
		assertThat(DartText.parseDate("20261113")).contains(LocalDate.of(2026, 11, 13));
		assertThat(DartText.parseDate("2026-11-13")).isEmpty();
		assertThat(DartText.parseDate(" 20261113")).isEmpty();
		assertThat(DartText.parseDate("20261332")).isEmpty();
		assertThat(DartText.parseDate("")).isEmpty();
		assertThat(DartText.parseDate(null)).isEmpty();
	}

	@Test
	void 날짜를_yyyyMMdd로_바꾼다() {
		assertThat(DartText.formatDate(LocalDate.of(2026, 1, 5))).isEqualTo("20260105");
	}

	@Test
	void 금액은_쉼표를_지우고_숫자로_바꾼다() {
		// 실제 응답 값: 삼성전자 2025년 3분기 연결 매출액, SK하이닉스 2023년 연결 영업이익(적자)
		assertThat(DartText.parseAmount("86,061,747,000,000")).contains(new java.math.BigDecimal("86061747000000"));
		assertThat(DartText.parseAmount("-7,730,313,000,000")).contains(new java.math.BigDecimal("-7730313000000"));
		assertThat(DartText.parseAmount(" 0 ")).contains(java.math.BigDecimal.ZERO);
		assertThat(DartText.parseAmount("1,234.5")).contains(new java.math.BigDecimal("1234.5"));
	}

	@Test
	void 숫자가_아닌_금액은_비어_있다() {
		assertThat(DartText.parseAmount(null)).isEmpty();
		assertThat(DartText.parseAmount("")).isEmpty();
		assertThat(DartText.parseAmount("   ")).isEmpty();
		assertThat(DartText.parseAmount("-")).isEmpty();
		assertThat(DartText.parseAmount("해당없음")).isEmpty();
		assertThat(DartText.parseAmount("1e5")).isEmpty();
		assertThat(DartText.parseAmount("(1,000)")).isEmpty();
	}
}
