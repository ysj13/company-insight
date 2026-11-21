package com.companyinsight.analysis.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 정정 표의 바뀐 항목이 날짜·일정뿐인지. 값의 모양은 점검 표본의 실제 정정 표에서 가져왔다
 * (경영권 양수도 계약의 잔금일 2026년 11월 16일 → 2026년 11월 27일, 유상증자의 납입일·상장 예정일, 기간 표기).
 */
class AiInputsTest {

	@Test
	void 날짜만_바뀌면_참() {
		assertThat(AiInputs.datesOnly(List.of(
			List.of("잔금지급일", "2026년 11월 16일", "2026년 11월 27일"),
			List.of("변경예정일자", "2026-11-16", "2026-11-27")))).isTrue();
		assertThat(AiInputs.datesOnly(List.of(
			List.of("납입일", "2026.11.04", "2026.12.04"),
			List.of("신주의 상장 예정일", "2026.11.20", "2026.12.22")))).isTrue();
	}

	@Test
	void 기간과_요일_표기도_날짜로_본다() {
		assertThat(AiInputs.datesOnly(List.of(
			List.of("처분예정기간", "2026.11.01 ~ 2026.11.30", "2026.11.01 ~ 2026.12.31"),
			List.of("주주총회 예정일", "2026년 11월 16일(월)", "2026년 11월 27일(금)")))).isTrue();
		assertThat(AiInputs.datesOnly(List.of(List.of("투자기간", "2026년 08월 07일부터", "2026년 09월 01일부터")))).isTrue();
	}

	@Test
	void 금액이나_글이_바뀌면_거짓() {
		assertThat(AiInputs.datesOnly(List.of(
			List.of("납입일", "2026.11.04", "2026.12.04"),
			List.of("발행가액", "1,566", "1,500")))).isFalse();
		assertThat(AiInputs.datesOnly(List.of(List.of("양수가액(원)", "4,400,000,000", "4,336,000,000")))).isFalse();
		assertThat(AiInputs.datesOnly(List.of(List.of("임상시험실시기관", "-", "서울대학교병원")))).isFalse();
	}

	@Test
	void 괄호_안에_금액이나_조건이_있으면_거짓() {
		assertThat(AiInputs.datesOnly(List.of(List.of("처분일", "2026.11.16", "2026.11.16(주식 1,000,000주 처분)")))).isFalse();
		assertThat(AiInputs.datesOnly(List.of(List.of("납입일", "2026.11.16 (발행가액 5,000원)", "2026.11.20")))).isFalse();
		assertThat(AiInputs.datesOnly(List.of(List.of("기일", "2026.11.16(단, 이사회 결의로 변경 가능)", "2026.11.20")))).isFalse();
	}

	@Test
	void 빈_칸에서_날짜로_바뀌면_새_조건일_수_있어_거짓() {
		assertThat(AiInputs.datesOnly(List.of(List.of("조기상환청구기간", "-", "2027.01.01 ~ 2027.12.31")))).isFalse();
	}

	@Test
	void 정정_표를_다_읽지_못했을_수_있으면_거짓() {
		List<List<String>> many = new java.util.ArrayList<>();
		for (int i = 0; i < AiInputs.CORRECTION_CHANGES_MAX; i++) {
			many.add(List.of("기일" + i, "2026.11.16", "2026.11.20"));
		}
		assertThat(AiInputs.datesOnly(many)).isFalse();
	}

	@Test
	void 바뀐_항목이_없거나_둘_다_비면_거짓() {
		assertThat(AiInputs.datesOnly(List.of())).isFalse();
		assertThat(AiInputs.datesOnly(List.of(List.of("비고", "-", "-")))).isFalse();
	}
}
