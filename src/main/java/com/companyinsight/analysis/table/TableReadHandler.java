package com.companyinsight.analysis.table;

import com.companyinsight.analysis.AnalysisJob;
import com.companyinsight.analysis.AnalysisStage;
import com.companyinsight.analysis.StageHandler;
import com.companyinsight.analysis.StageResult;
import com.companyinsight.analysis.body.BodyDocument;
import com.companyinsight.analysis.body.DisclosureBodyReader;
import com.companyinsight.analysis.table.TableReadRepository.TableTarget;
import com.companyinsight.disclosure.ClassifiedBy;
import com.companyinsight.disclosure.JudgeMethod;
import com.companyinsight.disclosure.Tone;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 본문 표 읽기 단계. 저장해 둔 본문의 표에서 정해진 항목을 코드가 읽어, 코드가 방향을 정하는 유형은 방향과 크기까지 정한다.
 * 외부를 부르지 않는다.
 *
 * <p>공시의 지금 분류에 따라 갈린다.
 * <ul>
 * <li>코드가 방향을 정하는 유형이고 읽는 규칙({@link TableReader})이 있음: 표를 읽는다.
 *   중립이면 여기서 끝나고, 호재·악재면 요약을 쓰러 AI 요약으로 넘긴다
 *   (지분 보고처럼 요약도 코드가 읽은 항목으로 쓰는 유형은 호재·악재여도 여기서 끝난다).
 *   방향을 정하는 항목을 읽지 못했거나, 읽은 값이 AI가 정할 경우(제3자배정 유상증자, 자회사의 유상증자)면 AI 판정으로 넘긴다
 * <li>코드가 방향을 정하는 유형인데 읽는 규칙이 아직 없음: 판정 없이 끝낸다. 규칙을 더할 때 그 유형의 작업을 다시 돌린다
 * <li>AI가 방향을 정하는 유형: AI 판정으로 넘긴다
 * <li>제목으로 방향이 정해진 유형: 호재·악재면 AI 요약으로 넘기고, 중립이면(본문을 고친 정정 공시가 여기까지 온다) 끝낸다.
 *   호재·악재이고 크기를 읽는 규칙이 있으면 표에서 비율을 읽어 크기를 적는다(방향은 제목의 것 그대로).
 *   비율이 크기의 하한 아래면(전환 청구 행사가 아주 작을 때) 중립으로 고쳐 적고 여기서 끝낸다
 * <li>제목 꼬리에 철회·취하가 든 공시: AI 판정 단계로 넘긴다. 그 단계가 AI를 부르지 않고 중립으로 정한다(철회는 해소로 본다)
 * </ul>
 *
 * <p>읽은 항목과 읽지 못한 항목은 실행 기록의 결과에 남긴다. 뒤 단계(AI 판정, 요약)가 그 값을 받아 쓴다.
 * 본문을 고친 정정 공시는 새 공시처럼 읽는다(본문에 고친 내용 전체가 들어 있다). 다만 정정 표에 철회·취소·해제·취하·
 * 미발생·미체결이 적혀 있으면 원래 결정을 거둔 정정일 수 있어, 코드가 방향을 정하지 않고 AI 판정으로 넘긴다.
 *
 * <p>본문을 고친 정정 공시는 어느 갈래로 가든 정정 표(고친 공시의 제출일, 정정 사유, 정정 전·후)도 읽어 실행 기록에 남기고,
 * 앞 공시(원래 공시나 앞선 정정)와 잇는다({@link CorrectionBundles}). 이어진 묶음에서는 가장 늦게 낸 공시만 회사 판단에 세고,
 * 그 날짜는 묶음의 최초 제출일이다.
 */
@Component
public class TableReadHandler implements StageHandler {

	/** 넘긴 이유: 방향을 정하는 항목을 읽지 못했다 */
	static final String UNREADABLE = "UNREADABLE";

	/** 넘긴 이유: AI가 방향을 정한다 (유형이 그렇거나, 표에서 읽은 값이 그렇다) */
	static final String TONE_BY_AI = "TONE_BY_AI";

	/** 넘긴 이유: 방향이 정해졌고 요약이 필요하다 */
	static final String NEEDS_SUMMARY = "NEEDS_SUMMARY";

	/** 넘긴 이유: 제목 꼬리에 철회·취하가 든 공시 */
	static final String WITHDRAWAL_TAIL = "WITHDRAWAL_TAIL";

	/** 넘긴 이유: 제목으로 판정하는 유형인데 제목이 방향을 정하지 않았다 */
	static final String NO_TITLE_TONE = "NO_TITLE_TONE";

	/** 제목 꼬리에 철회·취하가 든 공시의 속성 */
	private static final String WITHDRAWAL = "WITHDRAWAL";

	/** 자회사의 공시의 속성 */
	private static final String SUBSIDIARY = "SUBSIDIARY";

	/** AI로 넘긴 경우: 정정 표에 철회·취소 같은 말이 있어 원래 결정을 거둔 정정인지 AI가 본다 */
	public static final String CORRECTION_MAY_WITHDRAW = "CORRECTION_MAY_WITHDRAW";

	/** 실행 기록의 키: 철회 여부를 AI에 묻기 전에 표로 정했던 판정 (방향, 크기) */
	public static final String JUDGED_BEFORE_WITHDRAW_CHECK = "judgedBeforeWithdrawCheck";

	/** 건너뛴 이유: 코드가 방향을 정하는 유형인데 읽는 규칙이 없다 */
	static final String NO_READER = "NO_READER";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final TableReadRepository repository;
	private final CorrectionBundles bundles;
	private final DisclosureBodyReader bodies;
	private final ImpactProperties impactProperties;
	private final Map<String, TableReader> readers = new HashMap<>();

	public TableReadHandler(TableReadRepository repository, CorrectionBundles bundles, DisclosureBodyReader bodies,
			ImpactProperties impactProperties, List<TableReader> tableReaders) {
		this.repository = repository;
		this.bundles = bundles;
		this.bodies = bodies;
		this.impactProperties = impactProperties;
		for (TableReader reader : tableReaders) {
			for (String typeCode : reader.typeCodes()) {
				TableReader before = readers.put(typeCode, reader);
				if (before != null) {
					throw new IllegalStateException("유형 " + typeCode + "의 표를 읽는 규칙이 둘입니다");
				}
			}
		}
	}

	/** 읽는 규칙이 있는 유형 번호. */
	Set<String> readableTypeCodes() {
		return Set.copyOf(readers.keySet());
	}

	@Override
	public AnalysisStage stage() {
		return AnalysisStage.READ_TABLE;
	}

	/**
	 * 공시 한 건의 본문 표를 읽는다.
	 *
	 * @throws IllegalStateException 공시가 없거나, 유형이 붙지 않았거나, 읽어야 하는데 저장한 본문이 없을 때
	 */
	@Override
	public StageResult handle(AnalysisJob job) {
		long disclosureId = job.disclosureId();
		TableTarget target = repository.findTarget(disclosureId)
			.orElseThrow(() -> new IllegalStateException("공시가 없습니다: " + disclosureId));
		// 표 읽기가 판정하지 않는 갈래로 가면, 앞선 실행이 표로 정해 둔 판정을 함께 지운다
		Runnable clear = () -> repository.clearTableJudgment(disclosureId);
		if (target.withdrawn()) {
			return StageResult.ended().withResult(skipped("WITHDRAWN")).withWrites(clear);
		}
		if (target.typeCode() == null || target.judgeMethod() == null) {
			throw new IllegalStateException("유형이 붙지 않은 공시입니다: " + disclosureId);
		}
		StageResult.Done done = judge(disclosureId, target, clear);
		return target.bodyCorrection() ? withCorrection(disclosureId, done) : done;
	}

	/**
	 * 정정 공시의 정정 표를 읽어 실행 기록에 더하고, 묶음 잇기를 끝을 기록하는 트랜잭션에 더한다.
	 * 정정 표도 고친 공시의 제출일도 없으면(저장한 본문이 없을 때 포함) {@code correction: null}로 남기고 묶음에서 뺀다({@code NO_DATE}).
	 */
	private StageResult.Done withCorrection(long disclosureId, StageResult.Done done) {
		Optional<CorrectionTable> correction = bodies.read(disclosureId)
			.flatMap(documents -> CorrectionTable.read(documents, BodyTables.of(documents)));
		Map<String, Object> result = new LinkedHashMap<>();
		if (done.resultJson() != null) {
			result.putAll(JSON.readValue(done.resultJson(), MAP_TYPE));
		}
		result.put("correction", correction.map(CorrectionTable::toResult).orElse(null));
		LocalDate correctedFiledOn = correction.map(CorrectionTable::correctedFiledOn).orElse(null);
		boolean firstFiling = correction.map(CorrectionTable::firstFiling).orElse(false);
		List<String> beforeValues = correction.map(c -> c.changes().stream().map(CorrectionTable.Change::before).toList())
			.orElse(List.of());
		Runnable writes = done.writes();
		return done.withResult(JSON.writeValueAsString(result)).withWrites(() -> {
			// 묶음의 잠금을 이 공시의 행을 고치기 전에 건다 (행 잠금을 쥔 채 기다리지 않게)
			bundles.lockCompanyOf(disclosureId);
			if (writes != null) {
				writes.run();
			}
			bundles.link(disclosureId, correctedFiledOn, firstFiling, beforeValues);
		});
	}

	/** 공시의 지금 분류에 따라 표를 읽어 판정한다. */
	private StageResult.Done judge(long disclosureId, TableTarget target, Runnable clear) {
		if (target.attributes().contains(WITHDRAWAL)) {
			return StageResult.passed(WITHDRAWAL_TAIL, StageResult.Next.of(AnalysisStage.AI_JUDGE)).withWrites(clear);
		}
		return switch (target.judgeMethod()) {
			case AI -> StageResult.passed(TONE_BY_AI, StageResult.Next.of(AnalysisStage.AI_JUDGE)).withWrites(clear);
			case TITLE -> byTitle(disclosureId, target);
			case CODE -> byCode(disclosureId, target);
		};
	}

	/**
	 * 제목으로 판정하는 유형. 방향은 이미 정해져 있다. 크기를 읽는 규칙이 있으면 표에서 크기를 읽는다.
	 *
	 * <p>정정 표에 철회 낱말이 있어도 방향은 제목이 정한 그대로 둔다(제목이 방향을 정하는 유형이다). 자회사의 공시면 크기를 낮춘다.
	 * 방향은 제목 규칙의 것으로 읽는다. 앞선 실행이 표로 중립을 적어 두었거나(크기의 하한 아래였다) AI가 판정해 둔 공시도
	 * 제목 규칙의 방향으로 크기를 읽고, AI의 판정은 덮어쓰지 않는다.
	 */
	private StageResult.Done byTitle(long disclosureId, TableTarget target) {
		Tone tone = target.classifiedBy() == ClassifiedBy.RULE ? target.tone() : target.titleTone();
		if (tone == null) {
			return StageResult.passed(NO_TITLE_TONE, StageResult.Next.of(AnalysisStage.AI_JUDGE));
		}
		if (tone == Tone.NEUTRAL) {
			return StageResult.ended();
		}
		TableReader reader = readers.get(target.typeCode());
		if (reader == null || !reader.sizeOnly()) {
			return StageResult.passed(NEEDS_SUMMARY, StageResult.Next.of(AnalysisStage.AI_SUMMARY));
		}
		List<BodyDocument> documents = bodies.read(disclosureId)
			.orElseThrow(() -> new IllegalStateException("저장한 본문이 없습니다: " + disclosureId));
		boolean subsidiary = target.attributes().contains(SUBSIDIARY);
		TableReading read = reader.read(new TableReader.ReadTarget(target.typeCode(), subsidiary, target.title(), tone),
				BodyTables.of(documents));
		if (read.verdict() != TableReading.Verdict.JUDGED || (read.tone() != tone && read.tone() != Tone.NEUTRAL)) {
			throw new IllegalStateException("제목이 방향을 정한 공시의 크기를 읽는 규칙이 다른 답을 냈습니다: " + disclosureId + ", "
					+ read.verdict() + ", " + read.tone());
		}
		if (read.impact() != null && subsidiary) {
			read = read.withImpact(impactProperties.stepDownForSubsidiary(read.impact()));
		}
		TableReading reading = read;
		String result = result(reading);
		if (reading.tone() == Tone.NEUTRAL) {
			// 크기의 하한 아래다. 표로 정한 중립으로 고쳐 적는다
			return StageResult.ended()
				.withResult(result)
				.withWrites(() -> repository.saveTitleNeutral(disclosureId, reading.ratio()));
		}
		return StageResult.passed(NEEDS_SUMMARY, StageResult.Next.of(AnalysisStage.AI_SUMMARY))
			.withResult(result)
			.withWrites(() -> repository.saveTitleImpact(disclosureId, reading.tone(), reading.impact(), reading.ratio()));
	}

	/** 코드가 방향을 정하는 유형. */
	private StageResult.Done byCode(long disclosureId, TableTarget target) {
		TableReader reader = readers.get(target.typeCode());
		if (reader == null || reader.sizeOnly()) {
			return StageResult.ended().withResult(skipped(NO_READER));
		}
		List<BodyDocument> documents = bodies.read(disclosureId)
			.orElseThrow(() -> new IllegalStateException("저장한 본문이 없습니다: " + disclosureId));
		boolean subsidiary = target.attributes().contains(SUBSIDIARY);
		BodyTables tables = BodyTables.of(documents);
		TableReading read = reader.read(new TableReader.ReadTarget(target.typeCode(), subsidiary, target.title()), tables);
		if (read.impact() != null && subsidiary) {
			read = read.withImpact(impactProperties.stepDownForSubsidiary(read.impact()));
		}
		TableReading judgedBeforeCheck = null;
		if (read.verdict() == TableReading.Verdict.JUDGED && tables.correctionMayWithdraw()) {
			// 정정 표에 철회·취소가 적혀 있다. 원래 결정을 거둔 정정이면 본문 표의 방향이 뜻을 잃는다.
			// 거둔 정정이 아니라고 AI가 답하면 이 판정을 그대로 쓰도록 실행 기록에 남긴다
			judgedBeforeCheck = read;
			read = TableReading.needsAi(CORRECTION_MAY_WITHDRAW, read.ratio(), read.items(), read.unread());
		}
		TableReading reading = read;
		String result = judgedBeforeCheck == null ? result(reading) : result(reading, judgedBeforeCheck);
		return switch (reading.verdict()) {
			case JUDGED -> {
				Runnable write = () -> repository.saveJudgment(disclosureId, reading.tone(), reading.impact(), reading.ratio());
				yield reading.tone() == Tone.NEUTRAL || reader.summaryByCode() ? StageResult.ended().withResult(result).withWrites(write)
						: StageResult.passed(NEEDS_SUMMARY, StageResult.Next.of(AnalysisStage.AI_SUMMARY))
							.withResult(result)
							.withWrites(write);
			}
			case NEEDS_AI -> StageResult.passed(TONE_BY_AI, StageResult.Next.of(AnalysisStage.AI_JUDGE))
				.withResult(result)
				.withWrites(() -> repository.saveRatioOnly(disclosureId, reading.ratio()));
			case UNREADABLE -> StageResult.passed(UNREADABLE, StageResult.Next.of(AnalysisStage.AI_JUDGE))
				.withResult(result)
				.withWrites(() -> repository.saveRatioOnly(disclosureId, null));
		};
	}

	/**
	 * 실행 기록에 남길 결과에, 정정 표의 철회 낱말 때문에 AI로 넘기기 전의 판정을 더한다 ({@code judgedBeforeWithdrawCheck}).
	 */
	private static String result(TableReading reading, TableReading judged) {
		Map<String, Object> result = JSON.readValue(result(reading), MAP_TYPE);
		Map<String, Object> before = new LinkedHashMap<>();
		before.put("tone", judged.tone() == null ? null : judged.tone().name());
		before.put("impact", judged.impact());
		result.put(JUDGED_BEFORE_WITHDRAW_CHECK, before);
		return JSON.writeValueAsString(result);
	}

	/** 실행 기록에 남길 결과. 읽은 항목, 읽지 못한 항목, 비율, 경우, 정한 방향과 크기. */
	private static String result(TableReading reading) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("items", reading.items());
		result.put("unread", reading.unread());
		result.put("ratio", reading.ratio() == null ? null : new BigDecimal(reading.ratio().stripTrailingZeros().toPlainString()));
		result.put("case", reading.caseCode());
		result.put("verdict", reading.verdict().name());
		result.put("tone", reading.tone() == null ? null : reading.tone().name());
		result.put("impact", reading.impact());
		return JSON.writeValueAsString(result);
	}

	private static String skipped(String reason) {
		return "{\"skipped\":\"" + reason + "\"}";
	}
}
