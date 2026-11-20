package com.companyinsight.analysis.table;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CellsTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
		// 실제 공시에 적힌 모양
		"83,333,818 | 83333818",
		"632.1 | 632.1",
		"-39.9 | -39.9",
		"1,810.26 | 1810.26",
		"-2,771,741,000 | -2771741000",
		"20.07 | 20.07",
		"1 | 1",
		// 재무제표에서 음수를 적는 다른 모양
		"△1,234 | -1234",
		"(1,234) | -1234",
		"12.5% | 12.5",
		"'  1,000  ' | 1000",
		"△ 1,234 | -1234",
		"( 1,234 ) | -1234",
	})
	void 숫자를_읽는다(String cell, String expected) {
		assertThat(Cells.number(cell)).contains(new BigDecimal(expected));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "-", "－", "미해당", "2026-01-28", "1,23", "12 34", "약 100", "--5" })
	void 숫자_하나가_아니면_읽지_않는다(String cell) {
		assertThat(Cells.number(cell)).isEmpty();
	}

	@Test
	void 빈_칸과_줄표만_있는_칸은_값이_없는_것이다() {
		assertThat(Cells.isBlank(null)).isTrue();
		assertThat(Cells.isBlank("")).isTrue();
		assertThat(Cells.isBlank(" - ")).isTrue();
		assertThat(Cells.isBlank("-5")).isFalse();
		assertThat(Cells.isBlank("적정")).isFalse();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
		"가. 감사의견 | 감사의견",
		"- 감사의견 | 감사의견",
		"ㆍ계속기업 존속불확실성 사유 해당여부 | 계속기업존속불확실성사유해당여부",
		"5. 증자방식 | 증자방식",
		"3. 증자전 발행주식총수 (주) | 증자전발행주식총수(주)",
		"7-1. 기준주가 산정방법 | 기준주가산정방법",
		"- 영업이익 | 영업이익",
		"영업이익 | 영업이익",
		"1. 감사의견 등 | 감사의견등",
	})
	void 항목_이름은_공백과_앞의_차례_표시를_뗀다(String cell, String expected) {
		assertThat(Cells.label(cell)).isEqualTo(expected);
	}
}
