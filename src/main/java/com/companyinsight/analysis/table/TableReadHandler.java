package com.companyinsight.analysis.table;

import com.companyinsight.analysis.AnalysisJob;
import com.companyinsight.analysis.AnalysisStage;
import com.companyinsight.analysis.StageHandler;
import com.companyinsight.analysis.StageResult;
import com.companyinsight.analysis.body.BodyDocument;
import com.companyinsight.analysis.body.DisclosureBodyReader;
import com.companyinsight.analysis.table.TableReadRepository.TableTarget;
import com.companyinsight.disclosure.JudgeMethod;
import com.companyinsight.disclosure.Tone;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 본문 표 읽기 단계. 저장해 둔 본문의 표에서 정해진 항목을 코드가 읽어, 코드가 방향을 정하는 유형은 방향과 크기까지 정한다.
 * 외부를 부르지 않는다.
 *
 * <p>공시의 지금 분류에 따라 갈린다.
 * <ul>
 * <li>코드가 방향을 정하는 유형이고 읽는 규칙({@link TableReader})이 있음: 표를 읽는다.
 *   중립이면 여기서 끝나고, 호재·악재면 요약을 쓰러 AI 요약으로 넘긴다.
 *   방향을 정하는 항목을 읽지 못했거나, 읽은 값이 AI가 정할 경우(제3자배정 유상증자, 자회사의 유상증자)면 AI 판정으로 넘긴다
 * <li>코드가 방향을 정하는 유형인데 읽는 규칙이 아직 없음: 판정 없이 끝낸다. 규칙을 더할 때 그 유형의 작업을 다시 돌린다
 * <li>AI가 방향을 정하는 유형: AI 판정으로 넘긴다
 * <li>제목으로 방향이 정해진 유형: 호재·악재면 AI 요약으로 넘기고, 중립이면(본문을 고친 정정 공시가 여기까지 온다) 끝낸다
 * <li>제목 꼬리에 철회·취하가 든 공시: 무엇을 거뒀는지에 따라 방향이 달라 AI 판정으로 넘긴다
 * </ul>
 *
 * <p>읽은 항목과 읽지 못한 항목은 실행 기록의 결과에 남긴다. 뒤 단계(AI 판정, 요약)가 그 값을 받아 쓴다.
 * 본문을 고친 정정 공시는 새 공시처럼 읽는다(본문에 고친 내용 전체가 들어 있다).
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

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final TableReadRepository repository;
	private final DisclosureBodyReader bodies;
	private final ImpactProperties impactProperties;
	private final Map<String, TableReader> readers = new HashMap<>();

	public TableReadHandler(TableReadRepository repository, DisclosureBodyReader bodies, ImpactProperties impactProperties,
			List<TableReader> tableReaders) {
		this.repository = repository;
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
		if (target.withdrawn()) {
			return StageResult.ended().withResult(skipped("WITHDRAWN"));
		}
		if (target.typeCode() == null || target.judgeMethod() == null) {
			throw new IllegalStateException("유형이 붙지 않은 공시입니다: " + disclosureId);
		}
		if (target.attributes().contains(WITHDRAWAL)) {
			return StageResult.passed(WITHDRAWAL_TAIL, StageResult.Next.of(AnalysisStage.AI_JUDGE));
		}
		return switch (target.judgeMethod()) {
			case AI -> StageResult.passed(TONE_BY_AI, StageResult.Next.of(AnalysisStage.AI_JUDGE));
			case TITLE -> byTitle(target);
			case CODE -> byCode(disclosureId, target);
		};
	}

	/** 제목으로 판정하는 유형. 방향은 이미 정해져 있다. 크기와 요약에 쓸 항목을 읽는 규칙은 아직 없다. */
	private static StageResult byTitle(TableTarget target) {
		if (target.tone() == null) {
			return StageResult.passed(NO_TITLE_TONE, StageResult.Next.of(AnalysisStage.AI_JUDGE));
		}
		if (target.tone() == Tone.NEUTRAL) {
			return StageResult.ended();
		}
		return StageResult.passed(NEEDS_SUMMARY, StageResult.Next.of(AnalysisStage.AI_SUMMARY));
	}

	/** 코드가 방향을 정하는 유형. */
	private StageResult byCode(long disclosureId, TableTarget target) {
		TableReader reader = readers.get(target.typeCode());
		if (reader == null) {
			return StageResult.ended().withResult(skipped("NO_READER"));
		}
		List<BodyDocument> documents = bodies.read(disclosureId)
			.orElseThrow(() -> new IllegalStateException("저장한 본문이 없습니다: " + disclosureId));
		boolean subsidiary = target.attributes().contains(SUBSIDIARY);
		TableReading read = reader.read(new TableReader.ReadTarget(target.typeCode(), subsidiary), BodyTables.of(documents));
		if (read.impact() != null && subsidiary) {
			read = read.withImpact(impactProperties.stepDownForSubsidiary(read.impact()));
		}
		TableReading reading = read;
		String result = result(reading);
		return switch (reading.verdict()) {
			case JUDGED -> {
				Runnable write = () -> repository.saveJudgment(disclosureId, reading.tone(), reading.impact(), reading.ratio());
				yield reading.tone() == Tone.NEUTRAL ? StageResult.ended().withResult(result).withWrites(write)
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
