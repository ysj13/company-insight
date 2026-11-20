package com.companyinsight.disclosure;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 한 출처의 "제목 → 유형" 대응표. DB의 유형·제목 규칙·속성 꼬리를 한 번 읽어 메모리에 둔 것이다.
 *
 * <p>대응표는 마이그레이션으로만 바뀌고 마이그레이션은 앱이 뜰 때 적용되므로, 앱이 도는 동안에는 바뀌지 않는다.
 * 그래서 만료되는 캐시를 쓰지 않고 뜰 때 한 번만 읽는다.
 *
 * <p>찾는 순서: 제목 전체가 같은 줄(EXACT)이 먼저다. 없으면 제목을 괄호가 열리는 자리마다 잘라 뒤에서부터
 * 서식 이름 줄(FORM)을 찾는다. 뒤에서부터 찾으므로 가장 긴 서식 이름이 이긴다.
 */
public final class TitleRuleBook {

	private final int version;
	private final Map<String, Type> types;
	private final Map<String, Rule> exact = new HashMap<>();
	private final Map<String, Rule> form = new HashMap<>();
	private final List<Attribute> attributes;

	/**
	 * @param version    대응표 버전
	 * @param types      유형 목록
	 * @param rules      제목 규칙
	 * @param attributes 속성 꼬리
	 * @throws IllegalStateException 대응표의 앞뒤가 맞지 않을 때. 틀린 대응표로 분류하지 않게 앱이 뜨지 않는다
	 */
	public TitleRuleBook(int version, List<Type> types, List<Rule> rules, List<Attribute> attributes) {
		this.version = version;
		Map<String, Type> byCode = new HashMap<>();
		for (Type type : types) {
			byCode.put(type.code(), type);
		}
		this.types = Map.copyOf(byCode);
		if (!this.types.containsKey(DisclosureType.UNCLASSIFIED)) {
			throw new IllegalStateException("유형 목록에 미분류(" + DisclosureType.UNCLASSIFIED + ")가 없습니다");
		}
		for (Rule rule : rules) {
			Type type = this.types.get(rule.typeCode());
			if (type == null) {
				throw new IllegalStateException("제목 규칙 " + rule.id() + "의 유형이 유형 목록에 없습니다: " + rule.typeCode());
			}
			if (!rule.pattern().equals(TitleNormalizer.squeeze(rule.pattern()))) {
				throw new IllegalStateException("제목 규칙 " + rule.id() + "의 글자가 다듬은 제목과 모양이 다릅니다: " + rule.pattern());
			}
			JudgeMethod method = rule.judgeMethod() != null ? rule.judgeMethod() : type.judgeMethod();
			if (rule.tone() != null && method != JudgeMethod.TITLE) {
				throw new IllegalStateException("제목 규칙 " + rule.id() + "에 방향이 있는데 판정 방법이 제목이 아닙니다");
			}
			Map<String, Rule> target = rule.matchKind() == MatchKind.EXACT ? exact : form;
			if (target.put(rule.pattern(), rule) != null) {
				throw new IllegalStateException("같은 제목 규칙이 두 줄입니다: " + rule.pattern());
			}
		}
		// 긴 꼬리부터 뗀다. "(자회사의주요경영사항)"을 "(자회사)"보다 먼저 봐야 한다
		this.attributes = attributes.stream()
			.sorted(Comparator.comparingInt((Attribute a) -> a.token().length()).reversed().thenComparing(Attribute::token))
			.toList();
	}

	/** 대응표 버전. 공시마다 이 번호를 남기고, 더 낮은 번호로 분류된 공시는 다시 분류한다. */
	public int version() {
		return version;
	}

	/** 속성 꼬리. 긴 것부터. */
	public List<Attribute> attributes() {
		return attributes;
	}

	/**
	 * 다듬은 제목이 걸리는 규칙을 찾는다.
	 *
	 * @param title 머리말, 공백, 기간 표기, 속성 꼬리를 뗀 제목
	 * @return 걸린 규칙. 대응표에 없으면 빈 값(미분류)
	 */
	public Optional<Rule> find(String title) {
		Rule rule = exact.get(title);
		if (rule == null) {
			rule = form.get(title);
		}
		for (int i = title.lastIndexOf('('); rule == null && i >= 0; i = title.lastIndexOf('(', i - 1)) {
			rule = form.get(title.substring(0, i));
		}
		return Optional.ofNullable(rule);
	}

	/**
	 * 규칙에 걸린 공시의 판정 방법. 규칙 줄이 정했으면 그 값, 아니면 유형의 값.
	 *
	 * @param rule 걸린 규칙. null이면 미분류 유형의 값
	 */
	public JudgeMethod judgeMethodOf(Rule rule) {
		if (rule == null) {
			return types.get(DisclosureType.UNCLASSIFIED).judgeMethod();
		}
		return rule.judgeMethod() != null ? rule.judgeMethod() : types.get(rule.typeCode()).judgeMethod();
	}

	/**
	 * 규칙에 걸린 공시의 제목이 정하는 방향. 규칙 줄이 정했으면 그 값, 아니면 유형의 기본 방향.
	 *
	 * @param rule 걸린 규칙. null이면 미분류
	 * @return 방향. 제목으로 정하지 않는 공시이거나 방향을 아직 적지 않은 줄이면 null
	 */
	public Tone titleToneOf(Rule rule) {
		if (rule == null || judgeMethodOf(rule) != JudgeMethod.TITLE) {
			return null;
		}
		return rule.tone() != null ? rule.tone() : types.get(rule.typeCode()).defaultTone();
	}

	/** 제목 규칙의 대응 방식 */
	public enum MatchKind {
		/** 제목 전체가 같다 */
		EXACT,
		/** 제목이 서식 이름과 같거나 서식 이름 바로 뒤에 괄호 꼬리가 온다 */
		FORM
	}

	/**
	 * 유형 한 줄.
	 *
	 * @param code        유형 번호
	 * @param judgeMethod 판정 방법
	 * @param defaultTone 제목으로 정해지는 기본 방향. 없으면 null
	 */
	public record Type(String code, JudgeMethod judgeMethod, Tone defaultTone) {
	}

	/**
	 * 제목 규칙 한 줄.
	 *
	 * @param id          규칙 ID
	 * @param matchKind   대응 방식
	 * @param pattern     맞춰 볼 글자
	 * @param typeCode    유형 번호
	 * @param tone        이 줄이 정하는 방향. 유형의 값을 따르면 null
	 * @param judgeMethod 이 줄의 판정 방법. 유형의 값을 따르면 null
	 * @param caseCode    제목이 정하는 경우. 없으면 null
	 */
	public record Rule(long id, MatchKind matchKind, String pattern, String typeCode, Tone tone, JudgeMethod judgeMethod,
			String caseCode) {
	}

	/**
	 * 속성 꼬리 한 줄.
	 *
	 * @param token     제목에서 떼는 글자
	 * @param attribute 속성 이름
	 */
	public record Attribute(String token, String attribute) {
	}
}
