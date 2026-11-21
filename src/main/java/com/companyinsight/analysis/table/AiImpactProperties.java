package com.companyinsight.analysis.table;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI가 방향을 정한 공시의 크기(영향도 1~3)를 정하는 값. 경계값으로 정하는 비율 유형(타법인 주식, 자산 양수도, 판결 금액,
 * 제3자배정 유상증자)은 {@link ImpactProperties}의 경계를 그대로 쓰고, 여기에는 견줄 비율이 없는 유형의 고정값을 둔다.
 *
 * @param fixed      유형마다 하나의 크기 (호재·악재 모두)
 * @param byTone     유형마다 호재일 때와 악재일 때의 크기가 다른 것
 * @param cases      AI가 함께 고른 "경우"마다의 크기 (유형 → 경우 이름 → 크기)
 * @param dissolution 해산사유 발생 (회생·파산 유형 안에서 이 서식만 작게)
 * @param withdrawal 앞서 알린 결정·계약을 거두는 공시 (제목 꼬리에 철회·취하)
 * @param fallback   위 어디에도 없을 때 (코드가 표를 읽지 못해 AI로 넘어온 유형 등). 영향도의 틀의 "망설여지면 1"
 */
@ConfigurationProperties("app.judgment.ai-impact")
public record AiImpactProperties(Map<String, Integer> fixed, Map<String, ToneSizes> byTone,
		Map<String, Map<String, Integer>> cases, Integer dissolution, Integer withdrawal, Integer fallback) {

	/** 크기의 가장 큰 값. 영향도의 틀이 정한 범위다 */
	static final int MAX_SIZE = 3;

	public AiImpactProperties {
		// 설정 파일의 키는 묶을 때 소문자가 될 수 있다. 유형 번호와 경우 이름은 대문자로 맞춘다
		fixed = upperKeys(fixed);
		byTone = upperKeys(byTone);
		Map<String, Map<String, Integer>> upperCases = new HashMap<>();
		upperKeys(cases).forEach((type, byCase) -> upperCases.put(type, upperKeys(byCase)));
		cases = Map.copyOf(upperCases);
		fixed.forEach((type, size) -> requireSize(size, "fixed." + type));
		cases.forEach((type, byCase) -> byCase.forEach((name, size) -> requireSize(size, "cases." + type + "." + name)));
		requireSize(dissolution, "dissolution");
		requireSize(withdrawal, "withdrawal");
		requireSize(fallback, "fallback");
	}

	/**
	 * 호재일 때와 악재일 때의 크기.
	 *
	 * @param positive 호재일 때
	 * @param negative 악재일 때
	 */
	public record ToneSizes(Integer positive, Integer negative) {

		public ToneSizes {
			requireSize(positive, "by-tone.positive");
			requireSize(negative, "by-tone.negative");
		}
	}

	private static <V> Map<String, V> upperKeys(Map<String, V> map) {
		Map<String, V> upper = new HashMap<>();
		if (map != null) {
			map.forEach((key, value) -> upper.put(key.toUpperCase(Locale.ROOT), value));
		}
		return Map.copyOf(upper);
	}

	private static void requireSize(Integer size, String name) {
		if (size == null || size < 1 || size > MAX_SIZE) {
			throw new IllegalArgumentException("app.judgment.ai-impact." + name + "는 1~" + MAX_SIZE + "이어야 합니다: " + size);
		}
	}
}
