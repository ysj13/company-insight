package com.companyinsight.analysis.ai;

import com.companyinsight.analysis.AnalysisJob;
import com.companyinsight.analysis.AnalysisStage;
import com.companyinsight.analysis.StageHandler;
import com.companyinsight.analysis.StageResult;
import com.companyinsight.analysis.ai.AiInputs.TableContext;
import com.companyinsight.analysis.ai.AiSettingVersions.Bundle;
import com.companyinsight.analysis.ai.AiTargetRepository.AiTarget;
import com.companyinsight.analysis.body.BodyDocument;
import com.companyinsight.analysis.body.DisclosureBodyReader;
import com.companyinsight.analysis.table.AiSizeRules;
import com.companyinsight.disclosure.Tone;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * AI 요약 단계. 방향이 이미 정해진 호재·악재 공시(제목·표·AI 판정)의 요약을 AI가 쓴다. 판정은 바꾸지 않는다.
 *
 * <p>요약의 숫자·날짜가 원문과 맞지 않으면 실패로 센다(받은 답은 실행 기록에 남는다). 세 번 실패하면 멈추고 요약은 빈 채로 둔다.
 * 요약이 없어도 판정은 회사 판단에 그대로 들어간다.
 *
 * <p>그 사이 공시가 중립이 됐거나 방향이 지워졌으면(표 읽기를 다시 돌려 AI 판정으로 넘어간 경우 등) 부르지 않고 끝낸다.
 * 지분 보고(A2)는 요약을 코드가 쓰는 유형이라 부르지 않는다.
 */
@Component
public class AiSummaryHandler implements StageHandler {

	private final AiTargetRepository repository;
	private final DisclosureBodyReader bodies;
	private final AiCaller caller;
	private final AiPrompts prompts;
	private final AiSettingVersions versions;
	private final AiSizeRules sizeRules;

	public AiSummaryHandler(AiTargetRepository repository, DisclosureBodyReader bodies, AiCaller caller, AiPrompts prompts,
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
		return AnalysisStage.AI_SUMMARY;
	}

	/**
	 * 공시 한 건의 요약을 쓴다.
	 *
	 * @throws IllegalStateException 공시가 없거나 저장한 본문이 없을 때
	 * @throws com.companyinsight.analysis.StageFailedException 요약이 원문과 맞지 않을 때 (실패로 센다)
	 */
	@Override
	public StageResult handle(AnalysisJob job) {
		long id = job.disclosureId();
		AiTarget target = repository.findTarget(id).orElseThrow(() -> new IllegalStateException("공시가 없습니다: " + id));
		if (target.withdrawn()) {
			return StageResult.ended().withResult(skipped("WITHDRAWN")).withWrites(() -> repository.deleteAiSummary(id));
		}
		if (target.supersededBy() != null) {
			return StageResult.ended().withResult(skipped("SUPERSEDED"));
		}
		if (target.tone() != Tone.POSITIVE && target.tone() != Tone.NEGATIVE) {
			return StageResult.ended().withResult(skipped("NOT_NEEDED")).withWrites(() -> repository.deleteAiSummary(id));
		}
		String effectiveType = target.pickedTypeCode() != null ? target.pickedTypeCode() : target.typeCode();
		String effectiveName = target.pickedTypeCode() != null ? target.pickedTypeName() : target.typeName();
		if (AiJudgeHandler.CODE_SUMMARY_TYPES.contains(effectiveType)) {
			return StageResult.ended().withResult(skipped("CODE_SUMMARY"));
		}
		List<BodyDocument> documents = bodies.read(id)
			.orElseThrow(() -> new IllegalStateException("저장한 본문이 없습니다: " + id));
		String body = AiInputs.body(documents);
		TableContext table = AiInputs.tableContext(repository.lastTableReadResult(id).orElse(null));
		Map<String, String> ratioItems = sizeRules.ratioItems(effectiveType, documents);
		String user = AiInputs.summaryUser(target, effectiveType, effectiveName, target.tone(), table, ratioItems, body);
		long versionId = versions.idOf(Bundle.AI_SUMMARY, settings());
		AiCaller.Answer answer;
		try {
			answer = caller.call(prompts.summarySystem(), user, AiInputs.summarySchema(), versionId);
		}
		catch (AiCaller.Postpone postpone) {
			return postpone.deferred();
		}
		JsonNode json = answer.json();
		String headline = AiJudgeHandler.text(json, "headline");
		List<String> lines = AiJudgeHandler.lines(json.path("summary"));
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("headline", headline);
		result.put("summary", lines);
		List<String> problems = AiVerifier.summaryProblems(AiJudgeHandler.verifyText(target, body), headline, lines);
		result.put("summary_ok", problems.isEmpty());
		if (!problems.isEmpty()) {
			result.put("summary_problems", problems);
			throw AiJudgeHandler.failure("VERIFY", "요약이 원문과 맞지 않음: " + String.join("; ", problems), result,
					answer.usage());
		}
		return StageResult.ended()
			.withResult(AiJudgeHandler.json(result))
			.withAiUsage(answer.usage())
			.withWrites(() -> repository.saveSummary(id, AnalysisStage.AI_SUMMARY.name(), headline, lines));
	}

	/** 설정 버전에 넣는 값 전체 (모델, 지시문). */
	private Map<String, Object> settings() {
		Map<String, Object> settings = new LinkedHashMap<>();
		settings.put("model", caller.model());
		settings.put("system", prompts.summarySystem());
		settings.put("input", "AiInputs.summaryUser v1");
		settings.put("schema", AiInputs.summarySchema());
		return settings;
	}

	private static String skipped(String reason) {
		return "{\"skipped\":\"" + reason + "\"}";
	}
}
