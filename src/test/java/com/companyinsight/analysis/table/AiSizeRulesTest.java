package com.companyinsight.analysis.table;

import static org.assertj.core.api.Assertions.assertThat;

import com.companyinsight.analysis.table.AiSizeRules.SizeInput;
import com.companyinsight.disclosure.Tone;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * AI가 방향을 정한 공시의 크기. 기대값은 영향도의 틀(유형별 크기 표)의 값이고, 설정은 application.yml과 같은 값을 손으로 옮겼다.
 */
class AiSizeRulesTest {

	private final AiSizeRules rules = new AiSizeRules(TableFixtures.impact(), aiImpact());

	@Test
	void 중립이면_크기가_없다() {
		assertThat(rules.size(input("E4", Tone.NEUTRAL), Map.of())).isEmpty();
	}

	@Test
	void 유형의_고정값() {
		assertThat(size(input("G1", Tone.NEGATIVE))).isEqualTo(2);
		assertThat(size(input("B3", Tone.POSITIVE))).isEqualTo(1);
		assertThat(size(input("C3", Tone.POSITIVE))).isEqualTo(2);
	}

	@Test
	void 감자는_악재_3_호재_2() {
		assertThat(size(input("E8", Tone.NEGATIVE))).isEqualTo(3);
		assertThat(size(input("E8", Tone.POSITIVE))).isEqualTo(2);
	}

	@Test
	void 합병은_소규모면_1_아니면_2() {
		assertThat(size(new SizeInput("F1", Tone.NEGATIVE, "SMALL", false, "합병결정", false, null, null))).isEqualTo(1);
		assertThat(size(new SizeInput("F1", Tone.NEGATIVE, "NORMAL", false, "합병결정", false, null, null))).isEqualTo(2);
	}

	@Test
	void 타법인_주식은_자기자본_대비_비율의_금액_경계() {
		assertThat(rules.size(input("F4", Tone.POSITIVE), Map.of("자기자본대비(%)", "9.99")).orElseThrow().impact()).isEqualTo(1);
		assertThat(rules.size(input("F4", Tone.POSITIVE), Map.of("자기자본대비(%)", "10")).orElseThrow().impact()).isEqualTo(2);
		assertThat(rules.size(input("F4", Tone.POSITIVE), Map.of("자기자본대비(%)", "31.2")).orElseThrow()).satisfies(s -> {
			assertThat(s.impact()).isEqualTo(3);
			assertThat(s.ratio()).isEqualByComparingTo("31.2");
		});
		// 비율 칸이 없으면 1
		assertThat(rules.size(input("F4", Tone.POSITIVE), Map.of()).orElseThrow().impact()).isEqualTo(1);
	}

	@Test
	void 제3자배정은_표_읽기가_적어_둔_신주_비율의_주식_수_경계() {
		assertThat(size(new SizeInput("D1", Tone.NEGATIVE, null, false, "유상증자결정", false, new BigDecimal("4.99"), null))).isEqualTo(1);
		assertThat(size(new SizeInput("D1", Tone.NEGATIVE, null, false, "유상증자결정", false, new BigDecimal("20"), null))).isEqualTo(3);
	}

	@Test
	void 해산사유_발생은_회생_유형_안에서도_작게() {
		assertThat(size(new SizeInput("I3", Tone.NEGATIVE, null, false, "해산사유발생", false, null, null))).isEqualTo(1);
		assertThat(size(new SizeInput("I3", Tone.NEGATIVE, null, false, "회생절차폐지결정", false, null, null))).isEqualTo(3);
	}

	@Test
	void 맞는_유형이_없는_포괄_제목은_AI가_고른_값() {
		assertThat(size(new SizeInput("K1", Tone.POSITIVE, null, false, "기타경영사항", false, null, 3))).isEqualTo(3);
		assertThat(size(new SizeInput("K1", Tone.POSITIVE, null, false, "기타경영사항", false, null, null))).isEqualTo(1);
		assertThat(size(new SizeInput("K1", Tone.POSITIVE, null, false, "기타경영사항", false, null, 9))).isEqualTo(3);
	}

	@Test
	void 철회_꼬리는_정해_둔_값_어디에도_없으면_1() {
		assertThat(size(new SizeInput("E4", Tone.POSITIVE, null, false, "자기주식처분결정(철회)", true, null, null))).isEqualTo(1);
		assertThat(size(input("B1", Tone.NEGATIVE))).isEqualTo(1);
	}

	@Test
	void 자회사의_공시는_한_단계_낮춘다() {
		assertThat(size(new SizeInput("E8", Tone.NEGATIVE, null, true, "감자결정", false, null, null))).isEqualTo(2);
		assertThat(size(new SizeInput("B3", Tone.POSITIVE, null, true, "계획", false, null, null))).isEqualTo(1);
	}

	private int size(SizeInput input) {
		return rules.size(input, Map.of()).orElseThrow().impact();
	}

	private static SizeInput input(String type, Tone tone) {
		return new SizeInput(type, tone, null, false, "제목", false, null, null);
	}

	/** application.yml의 app.judgment.ai-impact와 같은 값 (키는 묶을 때처럼 소문자로 준다). */
	static AiImpactProperties aiImpact() {
		return new AiImpactProperties(
			Map.ofEntries(Map.entry("b3", 1), Map.entry("c3", 2), Map.entry("d6", 1), Map.entry("e4", 1), Map.entry("f2", 2),
				Map.entry("f3", 2), Map.entry("g1", 2), Map.entry("g2", 2), Map.entry("g5", 2), Map.entry("g7", 1),
				Map.entry("i2", 2), Map.entry("i4", 1), Map.entry("j7", 1)),
			Map.of("e8", new AiImpactProperties.ToneSizes(2, 3), "i3", new AiImpactProperties.ToneSizes(2, 3)),
			Map.of("f1", Map.of("small", 1, "normal", 2),
				"j1", Map.of("delisting", 3, "designation", 2, "recurring", 1, "relief", 2)),
			1, 1, 1);
	}
}
