package com.companyinsight.analysis.ai;

import com.companyinsight.analysis.AiUsage;
import com.companyinsight.analysis.AnalysisJob;
import com.companyinsight.analysis.AnalysisStage;
import com.companyinsight.analysis.StageFailedException;
import com.companyinsight.analysis.StageHandler;
import com.companyinsight.analysis.StageResult;
import com.companyinsight.analysis.ai.AiInputs.TableContext;
import com.companyinsight.analysis.ai.AiPrompts.CaseOption;
import com.companyinsight.analysis.ai.AiSettingVersions.Bundle;
import com.companyinsight.analysis.ai.AiTargetRepository.AiTarget;
import com.companyinsight.analysis.ai.AiTargetRepository.TypeChoice;
import com.companyinsight.analysis.body.BodyDocument;
import com.companyinsight.analysis.body.DisclosureBodyReader;
import com.companyinsight.analysis.table.AiSizeRules;
import com.companyinsight.disclosure.ClassifiedBy;
import com.companyinsight.disclosure.Tone;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 판정 단계. 코드가 방향을 정하지 못한 공시의 본문을 AI가 읽어 방향(호재·악재·중립)을 정하고, 같은 호출에서 요약도 쓴다.
 * 크기(영향도)는 AI가 아니라 코드가 정한다({@link AiSizeRules}).
 *
 * <p>여기로 오는 공시: AI가 방향을 정하는 유형(제3자배정 유상증자, 자기주식 처분, 감자, 합병, 타법인 주식 취득 등), 표를 읽지 못한 공시,
 * 정정 표에 철회 낱말이 있는 정정 공시, 포괄 제목·꼬리 미상·미분류(먼저 유형을 고른다). 제목 꼬리에 철회·취하가 든 공시와
 * 포괄 제목의 "…철회" 공시도 여기로 오지만 AI를 부르지 않고 중립으로 정한다(원래 결정은 원래 공시로 셌고 철회는 해소로 본다).
 *
 * <p>끝나는 모양:
 * <ul>
 * <li>호재·악재이고 요약이 대조를 통과함: 판정과 요약을 적고 끝
 * <li>호재·악재인데 요약의 숫자·날짜가 원문과 맞지 않음: 판정만 적고 AI 요약 단계로 넘긴다 (판정은 그대로 쓴다)
 * <li>중립: 판정을 적고 끝 (요약하지 않는다)
 * <li>판정불가, 또는 근거로 옮긴 구절이 본문에 없음: 판정 보류. 회사 판단에 세지 않는다
 * <li>철회된 공시, 뒤 정정으로 대체된 공시, 그 사이 제목·표가 판정한 공시: 부르지 않고 끝
 * <li>정정 표의 바뀐 항목이 날짜·일정뿐인 정정이고 앞 공시가 판정돼 있음: 앞 공시의 판정을 물려받고 부르지 않는다
 *   (호재·악재면 요약만 AI 요약으로). 앞 공시의 판정이 없으면 날짜만 바뀐 정정임을 알려 주고 부른다
 * <li>과부하·한도: 실패로 세지 않고 미룬다
 * </ul>
 * 지분 보고(A2)는 요약을 코드가 쓰는 유형이라 AI가 쓴 요약을 적지 않는다.
 */
@Component
public class AiJudgeHandler implements StageHandler {

	/** 넘긴 이유: 판정은 했지만 요약의 숫자·날짜가 원문과 맞지 않아 요약만 다시 쓴다 */
	static final String SUMMARY_UNVERIFIED = "SUMMARY_UNVERIFIED";

	/** 넘긴 이유: 날짜·일정만 바꾼 정정이라 앞 공시의 판정을 물려받았고 요약만 다시 쓴다 */
	static final String INHERITED = "INHERITED";

	/** 요약을 코드가 쓰는 유형 (지분 보고) */
	static final Set<String> CODE_SUMMARY_TYPES = Set.of("A2");

	private static final String WITHDRAWAL = "WITHDRAWAL";

	/** 포괄 제목의 공시 이름에서 철회 공시를 알아보는 낱말 */
	private static final String WITHDRAWAL_WORD = "철회";

	private static final String SUBSIDIARY = "SUBSIDIARY";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final AiTargetRepository repository;
	private final DisclosureBodyReader bodies;
	private final AiCaller caller;
	private final AiPrompts prompts;
	private final AiSettingVersions versions;
	private final AiSizeRules sizeRules;

	public AiJudgeHandler(AiTargetRepository repository, DisclosureBodyReader bodies, AiCaller caller, AiPrompts prompts,
			AiSettingVersions versions, AiSizeRules sizeRules) {
		this.repository = repository;
		this.bodies = bodies;
		this.caller = caller;
		this.prompts = prompts;
		this.versions = versions;
		this.sizeRules = sizeRules;
	}

	@Override
	public AnalysisStage stage() {
		return AnalysisStage.AI_JUDGE;
	}

	/**
	 * 공시 한 건을 AI로 판정한다.
	 *
	 * @throws IllegalStateException 공시가 없거나 저장한 본문이 없을 때
	 */
	@Override
	public StageResult handle(AnalysisJob job) {
		long id = job.disclosureId();
		AiTarget target = repository.findTarget(id).orElseThrow(() -> new IllegalStateException("공시가 없습니다: " + id));
		if (target.withdrawn()) {
			return StageResult.ended().withResult(skipped("WITHDRAWN")).withWrites(() -> {
				repository.hold(id, target.pickedTypeCode());
				repository.deleteAiSummary(id);
			});
		}
		if (target.supersededBy() != null) {
			return StageResult.ended().withResult(skipped("SUPERSEDED"));
		}
		if (target.tone() != null && target.classifiedBy() != ClassifiedBy.LLM) {
			// 그 사이 대응표나 표 읽기가 방향을 정했다
			return StageResult.ended().withResult(skipped("NOT_NEEDED"));
		}
		// 철회·취하 공시(제목 꼬리, 또는 포괄 제목의 "…철회")는 중립. 원래 결정은 원래 공시로 이미 셌고 철회는 해소로 본다
		boolean withdrawal = target.attributes().contains(WITHDRAWAL)
				|| AiSizeRules.PICKING_TYPES.contains(target.typeCode()) && target.reportName() != null
						&& target.reportName().contains(WITHDRAWAL_WORD);
		if (withdrawal) {
			return StageResult.ended().withResult("{\"decided\":\"WITHDRAWAL_NEUTRAL\",\"tone\":\"NEUTRAL\"}").withWrites(() -> {
				if (repository.saveJudgment(id, Tone.NEUTRAL, null, null, ClassifiedBy.RULE, target.pickedTypeCode())) {
					repository.deleteAiSummary(id);
				}
			});
		}
		TableContext table = AiInputs.tableContext(repository.lastTableReadResult(id).orElse(null));
		boolean datesOnlyCorrection = target.bodyCorrection() && AiInputs.datesOnly(table.changes());
		if (datesOnlyCorrection && target.previous().tone() != null && !table.mayWithdraw()) {
			return inherit(target, table);
		}
		List<BodyDocument> documents = bodies.read(id)
			.orElseThrow(() -> new IllegalStateException("저장한 본문이 없습니다: " + id));
		String body = AiInputs.body(documents);

		boolean picking = AiSizeRules.PICKING_TYPES.contains(target.typeCode()) && target.pickedTypeCode() == null;
		String effectiveType = target.pickedTypeCode() != null ? target.pickedTypeCode() : target.typeCode();
		String effectiveName = target.pickedTypeCode() != null ? target.pickedTypeName() : target.typeName();
		boolean askWithdrawn = table.mayWithdraw();
		List<TypeChoice> choices = picking ? repository.typeChoices() : List.of();
		Map<String, String> typeCriteria = new LinkedHashMap<>();
		choices.forEach(c -> {
			String criteria = prompts.criteria(c.code());
			if (criteria != null) {
				typeCriteria.put(c.code(), criteria);
			}
		});
		List<CaseOption> cases = picking ? List.of() : prompts.cases(effectiveType);
		Map<String, String> ratioItems = sizeRules.ratioItems(effectiveType, documents);

		String user = AiInputs.judgeUser(target, effectiveType, effectiveName, prompts.criteria(effectiveType), choices,
				typeCriteria, cases, askWithdrawn, datesOnlyCorrection, table, ratioItems, body);
		Map<String, Object> schema = AiInputs.judgeSchema(choices.stream().map(TypeChoice::code).toList(),
				cases.stream().map(CaseOption::code).toList(), askWithdrawn);
		long versionId = versions.idOf(Bundle.AI_JUDGE, settings());
		AiCaller.Answer answer;
		try {
			answer = caller.call(prompts.judgeSystem(), user, schema, versionId);
		}
		catch (AiCaller.Postpone postpone) {
			return postpone.deferred();
		}
		return decide(target, documents, body, table, picking, effectiveType, ratioItems, askWithdrawn,
				choices, cases, answer);
	}

	/**
	 * 날짜·일정만 바꾼 정정은 앞 공시의 판정(방향·크기·비율·주체)을 물려받는다. AI 판정을 부르지 않는다.
	 * 호재·악재면 요약만 바뀐 날짜로 다시 쓰러 AI 요약으로 넘긴다.
	 */
	private StageResult inherit(AiTarget target, TableContext table) {
		long id = target.id();
		AiTargetRepository.Previous previous = target.previous();
		Tone tone = previous.tone();
		Integer impact = tone == Tone.NEUTRAL ? null : previous.impact();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("inherited_from", previous.id());
		result.put("changes", table.changes());
		result.put("tone", tone.name());
		result.put("impact", impact);
		Runnable save = () -> repository.saveJudgment(id, tone, impact, previous.ratio(), previous.classifiedBy(),
				target.pickedTypeCode());
		if (tone == Tone.NEUTRAL || CODE_SUMMARY_TYPES.contains(target.typeCode())) {
			return StageResult.ended().withResult(json(result)).withWrites(() -> {
				save.run();
				if (tone == Tone.NEUTRAL) {
					repository.deleteAiSummary(id);
				}
			});
		}
		return StageResult.passed(INHERITED, StageResult.Next.of(AnalysisStage.AI_SUMMARY))
			.withResult(json(result))
			.withWrites(save);
	}

	private StageResult decide(AiTarget target, List<BodyDocument> documents, String body, TableContext table, boolean picking,
			String effectiveTypeBefore, Map<String, String> ratioItemsBefore, boolean askWithdrawn,
			List<TypeChoice> choices, List<CaseOption> cases, AiCaller.Answer answer) {
		long id = target.id();
		JsonNode json = answer.json();
		AiUsage usage = answer.usage();
		Map<String, Object> result = new LinkedHashMap<>();

		String direction = text(json, "direction");
		String evidence = text(json, "evidence");
		String headline = text(json, "headline");
		List<String> lines = lines(json.path("summary"));
		Boolean withdrawn = json.has("withdrawn") && json.path("withdrawn").isBoolean() ? json.path("withdrawn").asBoolean() : null;
		String caseCode = cases.stream().map(CaseOption::code).filter(c -> c.equals(text(json, "case"))).findFirst().orElse(null);
		Integer aiSize = json.has("size") && json.path("size").isNumber() ? json.path("size").asInt() : null;

		String pickedType = target.pickedTypeCode();
		String effectiveType = effectiveTypeBefore;
		Map<String, String> ratioItems = ratioItemsBefore;
		if (picking) {
			String picked = text(json, "picked_type");
			pickedType = choices.stream().map(TypeChoice::code).filter(c -> c.equals(picked)).findFirst().orElse(null);
			if (pickedType != null) {
				effectiveType = pickedType;
				ratioItems = sizeRules.ratioItems(pickedType, documents);
			}
			result.put("picked_type", picked);
		}
		result.put("direction", direction);
		result.put("evidence", evidence);
		result.put("reason", text(json, "reason"));
		if (askWithdrawn) {
			result.put("withdrawn", withdrawn);
		}
		if (caseCode != null || json.has("case")) {
			result.put("case", text(json, "case"));
		}
		if (aiSize != null) {
			result.put("size", aiSize);
		}
		result.put("headline", headline);
		result.put("summary", lines);

		Tone tone = toTone(direction);
		boolean evidenceOk = AiVerifier.evidenceFound(body, evidence);
		result.put("evidence_ok", evidenceOk);
		String heldPicked = pickedType;
		// 거둔 정정이 아니라는 답이면 표로 정했던 판정을 쓰므로, AI의 방향·근거가 없어도 보류하지 않는다
		boolean tableKept = askWithdrawn && Boolean.FALSE.equals(withdrawn) && table.judgedTone() != null;
		if (!tableKept && (tone == null || !evidenceOk)) {
			// 판정불가이거나, 근거로 옮긴 구절이 본문에 없다(지어낸 근거일 수 있다)
			result.put("held", tone == null ? "UNDECIDABLE" : "EVIDENCE_NOT_FOUND");
			return StageResult.held().withResult(json(result)).withAiUsage(usage).withWrites(() -> {
				repository.hold(id, heldPicked);
				repository.deleteAiSummary(id);
			});
		}

		ClassifiedBy classifiedBy = ClassifiedBy.LLM;
		Integer impact;
		BigDecimal ratio;
		if (askWithdrawn && Boolean.TRUE.equals(withdrawn)) {
			// 원래 결정을 거둔 정정이다. 묶음의 방향을 중립(해소)으로 둔다
			tone = Tone.NEUTRAL;
			impact = null;
			ratio = null;
			result.put("decided", "WITHDRAWN_CORRECTION");
		}
		else if (tableKept) {
			// 거둔 정정이 아니다. 표로 정했던 판정을 그대로 쓴다
			tone = table.judgedTone();
			impact = tone == Tone.NEUTRAL ? null : table.judgedImpact();
			ratio = null;
			classifiedBy = ClassifiedBy.TABLE;
			result.put("decided", "TABLE_KEPT");
		}
		else {
			Optional<AiSizeRules.Size> size = sizeRules.size(new AiSizeRules.SizeInput(effectiveType, tone, caseCode,
					target.attributes().contains(SUBSIDIARY), target.title(), target.impactRatio(), aiSize),
					ratioItems);
			impact = size.map(AiSizeRules.Size::impact).orElse(null);
			ratio = size.map(AiSizeRules.Size::ratio).orElse(null);
			size.ifPresent(s -> result.put("size_basis", s.basis()));
		}
		result.put("tone", tone.name());
		result.put("impact", impact);
		if (!ratioItems.isEmpty()) {
			result.put("ratio_items", ratioItems);
		}

		Tone finalTone = tone;
		Integer finalImpact = impact;
		BigDecimal finalRatio = ratio;
		ClassifiedBy finalBy = classifiedBy;
		String finalPicked = pickedType;
		Runnable saveJudgment = () -> repository.saveJudgment(id, finalTone, finalImpact, finalRatio, finalBy, finalPicked);
		if (tone == Tone.NEUTRAL) {
			return StageResult.ended().withResult(json(result)).withAiUsage(usage).withWrites(() -> {
				if (repository.saveJudgment(id, finalTone, finalImpact, finalRatio, finalBy, finalPicked)) {
					repository.deleteAiSummary(id);
				}
			});
		}
		if (CODE_SUMMARY_TYPES.contains(effectiveType)) {
			result.put("summary_skipped", "CODE_SUMMARY");
			return StageResult.ended().withResult(json(result)).withAiUsage(usage).withWrites(saveJudgment);
		}
		List<String> problems = AiVerifier.summaryProblems(verifyText(target, body), headline, lines);
		result.put("summary_ok", problems.isEmpty());
		if (!problems.isEmpty()) {
			result.put("summary_problems", problems);
			return StageResult.passed(SUMMARY_UNVERIFIED, StageResult.Next.of(AnalysisStage.AI_SUMMARY))
				.withResult(json(result))
				.withAiUsage(usage)
				.withWrites(() -> {
					if (repository.saveJudgment(id, finalTone, finalImpact, finalRatio, finalBy, finalPicked)) {
						repository.deleteAiSummary(id);
					}
				});
		}
		return StageResult.ended().withResult(json(result)).withAiUsage(usage).withWrites(() -> {
			// 그 사이 제목·표가 판정했으면 판정을 적지 않고, 요약도 적지 않는다
			if (repository.saveJudgment(id, finalTone, finalImpact, finalRatio, finalBy, finalPicked)) {
				repository.saveSummary(id, AnalysisStage.AI_JUDGE.name(), headline, lines);
			}
		});
	}

	/** 설정 버전에 넣는 값 전체 (모델, 지시문, 판정 기준, 경우). */
	private Map<String, Object> settings() {
		Map<String, Object> settings = new LinkedHashMap<>();
		settings.put("model", caller.model());
		settings.put("system", prompts.judgeSystem());
		settings.put("criteria", prompts.criteriaText());
		settings.put("cases", prompts.casesText());
		settings.put("input", "AiInputs.judgeUser v1");
		// 응답의 모양도 판정을 바꿀 수 있어 버전에 넣는다 (유형 고르기·경우·철회 질문이 모두 있는 모양)
		settings.put("schema", AiInputs.judgeSchema(List.of("A1"), List.of("CASE"), true));
		return settings;
	}

	static Tone toTone(String direction) {
		if (direction == null) {
			return null;
		}
		return switch (direction.strip()) {
			case AiInputs.POSITIVE -> Tone.POSITIVE;
			case AiInputs.NEGATIVE -> Tone.NEGATIVE;
			case AiInputs.NEUTRAL -> Tone.NEUTRAL;
			default -> null;
		};
	}

	static String text(JsonNode json, String field) {
		JsonNode node = json.path(field);
		return node.isString() ? node.asString() : null;
	}

	static List<String> lines(JsonNode array) {
		List<String> lines = new ArrayList<>();
		if (array.isArray()) {
			for (JsonNode item : array) {
				if (item.isString() && !item.asString().isBlank()) {
					lines.add(item.asString().strip());
				}
			}
		}
		return lines;
	}

	/**
	 * 요약을 대조할 글. 본문 앞에 입력 머리글의 접수일을 붙인다(AI가 요약에 접수일을 쓰면 본문에 없어 틀린 것으로 보지 않게).
	 */
	static String verifyText(AiTarget target, String body) {
		if (target.filedOn() == null) {
			return body;
		}
		return "접수일 " + target.filedOn() + " " + target.filedOn().getYear() + "년 " + target.filedOn().getMonthValue() + "월 "
				+ target.filedOn().getDayOfMonth() + "일\n" + body;
	}

	static String json(Map<String, Object> result) {
		return JSON.writeValueAsString(result);
	}

	private static String skipped(String reason) {
		return "{\"skipped\":\"" + reason + "\"}";
	}

	/** AI의 답을 실행 기록에 남기며 실패로 세는 예외를 만든다 (요약 대조 실패 등). */
	static StageFailedException failure(String kind, String message, Map<String, Object> result, AiUsage usage) {
		return new StageFailedException(kind, message, json(result), usage);
	}
}
