package com.companyinsight.analysis.ai;

import com.companyinsight.analysis.ai.AiPrompts.CaseOption;
import com.companyinsight.analysis.ai.AiTargetRepository.AiTarget;
import com.companyinsight.analysis.ai.AiTargetRepository.TypeChoice;
import com.companyinsight.analysis.body.BodyDocument;
import com.companyinsight.analysis.table.TableReadHandler;
import com.companyinsight.disclosure.Tone;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI에 보내는 입력 글과 답의 모양(JSON 스키마)을 만든다.
 *
 * <p>입력 글에는 회사·제목·접수일·유형과 판정 기준, 코드가 표에서 읽은 항목, 정정 표의 바뀐 항목, 본문을 넣는다.
 * 본문은 저장해 둔 원문에서 그때 뽑은 글이고 화면에 내지 않는다.
 */
final class AiInputs {

	/** AI에 넣는 본문 글자 수의 상한. 넘으면 뒷부분을 줄인다 (2026-11-19 표본의 가장 큰 본문이 약 1만 6천 토큰) */
	static final int MAX_BODY_CHARS = 80_000;

	/** 방향을 나타내는 답의 말 */
	static final String POSITIVE = "호재";
	static final String NEGATIVE = "악재";
	static final String NEUTRAL = "중립";
	static final String UNDECIDABLE = "판정불가";

	/** 맞는 유형이 없다는 답 */
	static final String NO_TYPE = "없음";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private AiInputs() {
	}

	/** 날짜 하나 (2026.11.16, 2026-11-16, 2026/11/16, 2026년 11월 16일) */
	private static final String DATE = "\\d{4}\\s*(?:[.\\-/]\\s*\\d{1,2}\\s*[.\\-/]\\s*\\d{1,2}\\.?|년\\s*\\d{1,2}\\s*월\\s*\\d{1,2}\\s*일)";

	/** 날짜 뒤 괄호에 받는 표기: 요일, 예정, 잠정. 그 밖의 괄호(금액, 조건)가 붙으면 날짜만 적힌 값이 아니다 */
	private static final String DATE_NOTE = "\\((?:[월화수목금토일](?:요일)?|예정|잠정)\\)";

	/** 정정 표에서 읽는 바뀐 항목 줄 수의 상한 ({@code CorrectionTable}과 같다). 여기 닿으면 다 읽지 못했을 수 있다 */
	static final int CORRECTION_CHANGES_MAX = 40;

	/** 날짜만 적힌 값: 날짜 하나, 또는 "~"나 "부터·까지"로 이은 기간. 괄호는 요일과 예정·잠정 표기만 받는다(시각 표기는 받지 않는다) */
	private static final Pattern DATE_VALUE = Pattern.compile(
			"\\s*" + DATE + "(?:\\s*" + DATE_NOTE + ")?(?:\\s*(?:부터|까지|~|-)\\s*(?:" + DATE + ")?(?:\\s*" + DATE_NOTE + ")?(?:\\s*까지)?)?\\s*");

	/**
	 * 정정 표의 바뀐 항목이 모두 날짜·일정뿐인지. 정정 전·후 값이 모두 날짜(또는 기간)여야 한다.
	 * 빈 칸("-")에서 날짜로 바뀐 줄은 조건이 새로 생긴 것일 수 있어 날짜만 바뀐 것으로 보지 않는다.
	 * 바뀐 항목이 없거나 정정 표를 다 읽지 못했을 수 있으면(읽은 줄 수가 상한에 닿음) false.
	 *
	 * @param changes 정정 표의 바뀐 항목 (항목, 정정 전, 정정 후)
	 */
	static boolean datesOnly(List<List<String>> changes) {
		if (changes.isEmpty() || changes.size() >= CORRECTION_CHANGES_MAX) {
			return false;
		}
		for (List<String> change : changes) {
			String before = change.get(1);
			String after = change.get(2);
			if (blankValue(before) || blankValue(after) || !DATE_VALUE.matcher(before).matches()
					|| !DATE_VALUE.matcher(after).matches()) {
				return false;
			}
		}
		return true;
	}

	private static boolean blankValue(String value) {
		return value == null || value.isBlank() || value.strip().chars().allMatch(c -> c == '-' || c == '－');
	}

	/** 본문 문서들을 한 글로 잇는다. 상한을 넘으면 뒷부분을 줄이고 그렇다고 적는다. */
	static String body(List<BodyDocument> documents) {
		StringBuilder text = new StringBuilder();
		for (BodyDocument document : documents) {
			if (!text.isEmpty()) {
				text.append("\n\n");
			}
			text.append(document.text());
		}
		if (text.length() > MAX_BODY_CHARS) {
			text.setLength(MAX_BODY_CHARS);
			text.append("\n(본문이 길어 뒷부분을 줄였다)");
		}
		return text.toString();
	}

	/** 표 읽기 실행 기록의 결과를 읽는다. 없으면 빈 것. */
	static TableContext tableContext(String resultJson) {
		if (resultJson == null) {
			return TableContext.EMPTY;
		}
		Map<String, Object> result = JSON.readValue(resultJson, MAP_TYPE);
		Map<String, String> items = new LinkedHashMap<>();
		if (result.get("items") instanceof Map<?, ?> map) {
			map.forEach((k, v) -> {
				if (v != null) {
					items.put(String.valueOf(k), String.valueOf(v));
				}
			});
		}
		List<List<String>> changes = new ArrayList<>();
		List<String> reasons = new ArrayList<>();
		if (result.get("correction") instanceof Map<?, ?> correction) {
			if (correction.get("changes") instanceof List<?> list) {
				for (Object row : list) {
					if (row instanceof List<?> cells && cells.size() == 3) {
						changes.add(cells.stream().map(c -> c == null ? "" : String.valueOf(c)).toList());
					}
				}
			}
			if (correction.get("reasons") instanceof List<?> list) {
				list.forEach(r -> reasons.add(String.valueOf(r)));
			}
		}
		String caseCode = result.get("case") instanceof String s ? s : null;
		String verdict = result.get("verdict") instanceof String s ? s : null;
		Tone tone = result.get("tone") instanceof String s ? Tone.valueOf(s) : null;
		Integer impact = result.get("impact") instanceof Number n ? n.intValue() : null;
		Tone judgedTone = null;
		Integer judgedImpact = null;
		if (result.get(TableReadHandler.JUDGED_BEFORE_WITHDRAW_CHECK) instanceof Map<?, ?> judged) {
			judgedTone = judged.get("tone") instanceof String s ? Tone.valueOf(s) : null;
			judgedImpact = judged.get("impact") instanceof Number n ? n.intValue() : null;
		}
		return new TableContext(items, changes, reasons, caseCode, verdict, tone, impact, judgedTone, judgedImpact);
	}

	/**
	 * AI 판정의 입력 글.
	 *
	 * @param effectiveType  판정에 쓰는 유형 (AI가 앞서 고른 유형이 있으면 그것)
	 * @param effectiveName  그 이름
	 * @param criteria       그 유형의 판정 기준. 없으면 null
	 * @param choices        유형을 고르게 할 때의 목록. 고르지 않으면 빈 목록
	 * @param cases          함께 고를 경우. 없으면 빈 목록
	 * @param askWithdrawn   "원래 결정을 거둔 정정인가"를 물을지
	 * @param datesOnlyCorrection 정정 표의 바뀐 항목이 날짜·일정뿐인 정정인지 (앞 공시의 판정이 없어 AI가 판정할 때)
	 * @param previousTone   정정 공시가 고친 앞 공시의 방향 (물려받아도 되는 판정일 때만). 알려 주지 않으면 null
	 * @param ratioItems     코드가 본문 표에서 읽은 비율 칸
	 */
	static String judgeUser(AiTarget target, String effectiveType, String effectiveName, String criteria,
			List<TypeChoice> choices, Map<String, String> typeCriteria, List<CaseOption> cases, boolean askWithdrawn,
			boolean datesOnlyCorrection, Tone previousTone, TableContext table, Map<String, String> ratioItems,
			String body) {
		StringBuilder text = header(target, effectiveType, effectiveName);
		text.append("판정 기준: ")
			.append(criteria != null ? criteria
					: "이 유형의 판정 기준은 따로 없다. 일어난 사실이 이 회사의 주주에게 좋은 소식인지 나쁜 소식인지로 정한다.")
			.append('\n');
		if (target.attributes().contains("SUBSIDIARY")) {
			text.append("이 공시는 종속회사(자회사)의 일을 알린 것이다.\n");
		}
		if (previousTone != null) {
			text.append("이 정정 공시가 고친 앞 공시(원래 결정)는 ").append(toneWord(previousTone))
				.append("로 판정됐다. 정정으로 바뀐 내용이 그 판단을 바꿀 만한 사실이 아니면 같은 방향을 고른다.\n");
		}
		if (datesOnlyCorrection) {
			text.append("이 정정 공시는 날짜·일정만 바꿨다. 새로 일어난 일이 아니므로 원래 결정의 성격으로 판정하고, ")
				.append("일정이 바뀐 것 자체를 좋은 소식이나 나쁜 소식으로 보지 않는다.\n");
		}
		if (!choices.isEmpty()) {
			text.append("\n[고를 수 있는 유형] picked_type 에 맞는 유형 번호를 고른다. 맞는 것이 없으면 \"없음\".\n");
			for (TypeChoice choice : choices) {
				text.append(choice.code()).append(' ').append(choice.name());
				String c = typeCriteria.get(choice.code());
				if (c != null) {
					text.append(" — ").append(c);
				}
				else if (choice.defaultTone() != null) {
					text.append(" — 기본 방향: ").append(toneWord(choice.defaultTone()));
				}
				text.append('\n');
			}
			text.append("size 는 고른 유형이 없을 때 쓰는 크기다: 1 작음(일상적이거나 회사 규모에 견줘 작은 일), 2 보통, ")
				.append("3 큼(실적·주식 수·지배구조·상장 유지가 눈에 띄게 달라지는 일). 망설여지면 1.\n");
		}
		if (!cases.isEmpty()) {
			text.append("\n[경우] case 에 맞는 것 하나를 고른다.\n");
			for (CaseOption option : cases) {
				text.append(option.code()).append(": ").append(option.description()).append('\n');
			}
		}
		if (askWithdrawn) {
			text.append("\n[묻는 것] 정정 표에 철회·취소·해제·취하·미발생·미체결 같은 말이 있다. ")
				.append("withdrawn 에는 이 정정 공시가 원래 공시의 결정·계약을 거둔 것(없던 일이 됨)이면 true, ")
				.append("내용 일부를 고친 것이면 false 를 적는다.\n");
		}
		appendTable(text, table, ratioItems);
		text.append("\n[본문]\n").append(body);
		return text.toString();
	}

	/** AI 요약의 입력 글. */
	static String summaryUser(AiTarget target, String effectiveType, String effectiveName, Tone tone, TableContext table,
			Map<String, String> ratioItems, String body) {
		StringBuilder text = header(target, effectiveType, effectiveName);
		text.append("정해진 판단: ").append(toneWord(tone)).append(" (요약에 이 말을 쓰지 않는다)\n");
		if (target.attributes().contains("SUBSIDIARY")) {
			text.append("이 공시는 종속회사(자회사)의 일을 알린 것이다.\n");
		}
		appendTable(text, table, ratioItems);
		text.append("\n[본문]\n").append(body);
		return text.toString();
	}

	/**
	 * AI 판정의 답 모양.
	 *
	 * @param caseCodes 함께 고를 경우의 이름. 없으면 빈 목록
	 * @param typeCodes 고를 수 있는 유형 번호. 고르지 않으면 빈 목록
	 */
	static Map<String, Object> judgeSchema(List<String> typeCodes, List<String> caseCodes, boolean askWithdrawn) {
		Map<String, Object> properties = new LinkedHashMap<>();
		List<String> required = new ArrayList<>();
		if (!typeCodes.isEmpty()) {
			List<String> options = new ArrayList<>(typeCodes);
			options.add(NO_TYPE);
			properties.put("picked_type", Map.of("type", "STRING", "enum", options));
			required.add("picked_type");
		}
		if (askWithdrawn) {
			properties.put("withdrawn", Map.of("type", "BOOLEAN"));
			required.add("withdrawn");
		}
		if (!caseCodes.isEmpty()) {
			properties.put("case", Map.of("type", "STRING", "enum", caseCodes));
			required.add("case");
		}
		properties.put("direction", Map.of("type", "STRING", "enum", List.of(POSITIVE, NEGATIVE, NEUTRAL, UNDECIDABLE)));
		properties.put("evidence", Map.of("type", "STRING"));
		properties.put("reason", Map.of("type", "STRING"));
		required.addAll(List.of("direction", "evidence", "reason"));
		if (!typeCodes.isEmpty()) {
			properties.put("size", Map.of("type", "INTEGER", "minimum", 1, "maximum", 3));
			required.add("size");
		}
		properties.put("headline", Map.of("type", "STRING"));
		properties.put("summary", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"), "maxItems", AiVerifier.MAX_LINES));
		required.addAll(List.of("headline", "summary"));
		return object(properties, required);
	}

	/** AI 요약의 답 모양. */
	static Map<String, Object> summarySchema() {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("headline", Map.of("type", "STRING"));
		properties.put("summary", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"), "maxItems", AiVerifier.MAX_LINES));
		return object(properties, List.of("headline", "summary"));
	}

	static String toneWord(Tone tone) {
		return switch (tone) {
			case POSITIVE -> POSITIVE;
			case NEGATIVE -> NEGATIVE;
			case NEUTRAL -> NEUTRAL;
		};
	}

	private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "OBJECT");
		schema.put("properties", properties);
		schema.put("required", required);
		schema.put("propertyOrdering", List.copyOf(properties.keySet()));
		return schema;
	}

	private static StringBuilder header(AiTarget target, String effectiveType, String effectiveName) {
		StringBuilder text = new StringBuilder();
		text.append("회사: ").append(target.companyName()).append('\n');
		text.append("공시 제목: ").append(target.reportName()).append('\n');
		text.append("접수일: ").append(target.filedOn()).append('\n');
		text.append("유형: ").append(effectiveType).append(' ').append(effectiveName == null ? "" : effectiveName).append('\n');
		return text;
	}

	private static void appendTable(StringBuilder text, TableContext table, Map<String, String> ratioItems) {
		Map<String, String> items = new LinkedHashMap<>(table.items());
		items.putAll(ratioItems);
		if (!items.isEmpty()) {
			text.append("\n[코드가 표에서 읽은 항목]\n");
			items.forEach((k, v) -> text.append(k).append(": ").append(v).append('\n'));
		}
		if (!table.changes().isEmpty()) {
			text.append("\n[정정 표에서 바뀐 항목] 항목 / 정정 전 / 정정 후\n");
			for (List<String> change : table.changes()) {
				text.append(change.get(0)).append(" / ").append(change.get(1)).append(" / ").append(change.get(2)).append('\n');
			}
		}
		if (!table.reasons().isEmpty()) {
			text.append("정정 사유: ").append(String.join(", ", table.reasons())).append('\n');
		}
	}

	/**
	 * 표 읽기가 남긴 것 가운데 AI 단계가 쓰는 것.
	 *
	 * @param items   읽은 항목
	 * @param changes 정정 표의 바뀐 항목 (항목, 정정 전, 정정 후)
	 * @param reasons 정정 사유
	 * @param caseCode 표 읽기가 정한 경우 (예: CORRECTION_MAY_WITHDRAW)
	 * @param verdict 표 읽기의 판정 (JUDGED, NEEDS_AI, UNREADABLE)
	 * @param tone    표 읽기가 정한 방향. 없으면 null
	 * @param impact  표 읽기가 정한 크기. 없으면 null
	 * @param judgedTone   정정 표의 철회 낱말 때문에 AI로 넘기기 전에 표로 정했던 방향. 없으면 null
	 * @param judgedImpact 그때의 크기. 없으면 null
	 */
	record TableContext(Map<String, String> items, List<List<String>> changes, List<String> reasons, String caseCode,
			String verdict, Tone tone, Integer impact, Tone judgedTone, Integer judgedImpact) {

		static final TableContext EMPTY = new TableContext(Map.of(), List.of(), List.of(), null, null, null, null, null, null);

		/** 정정 표에 철회 낱말이 있어 원래 결정을 거둔 정정인지 AI에 물어야 하는지. */
		boolean mayWithdraw() {
			return TableReadHandler.CORRECTION_MAY_WITHDRAW.equals(caseCode);
		}
	}
}
