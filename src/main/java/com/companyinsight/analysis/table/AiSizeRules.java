package com.companyinsight.analysis.table;

import com.companyinsight.analysis.body.BodyDocument;
import com.companyinsight.disclosure.DisclosureType;
import com.companyinsight.disclosure.Tone;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * AI가 방향을 정한 공시의 크기(영향도 1~3)를 코드가 정한다. AI는 크기를 정하지 않는다
 * (포괄 제목·꼬리 미상·미분류에서 맞는 유형이 없을 때만 AI가 고른 1~3을 쓴다).
 *
 * <p>정하는 순서:
 * <ol>
 * <li>맞는 유형이 없는 포괄 제목·꼬리 미상·미분류: AI가 고른 값 (없으면 1)
 * <li>비율 유형: 타법인 주식·사채 취득·처분(F4)은 자기자본 대비, 자산·영업 양수도(F5)는 자산총액 대비,
 *   소송 판결(I1)은 자기자본 대비 비율을 본문 표에서 읽어 금액 경계로. 제3자배정 유상증자(D1)는 표 읽기가 계산해 둔
 *   신주 비율을 주식 수 경계(늘어남)로. 비율 칸이 비었으면 1
 * <li>해산사유 발생(I3 안의 서식): 정해 둔 값
 * <li>AI가 함께 고른 경우의 값 (합병의 소규모 여부, 관리종목·상장폐지 안내의 종류)
 * <li>호재·악재마다 다른 값, 유형 하나의 값
 * <li>어디에도 없으면 1
 * </ol>
 * 자회사의 공시는 마지막에 한 단계 낮춘다(최소 1). 영향도의 틀의 공통 규칙이다.
 */
@Component
public class AiSizeRules {

	/** 맞는 유형이 없을 때 AI가 크기를 고르는 유형: 포괄 제목, 꼬리 미상, 미분류 */
	public static final Set<String> PICKING_TYPES = Set.of("K1", "K2", DisclosureType.UNCLASSIFIED);

	/** 비율 유형마다 본문 표의 비율 칸 이름 (먼저 찾는 것부터) */
	private static final Map<String, List<String>> RATIO_LABELS = Map.of(
		"F4", List.of("자기자본대비(%)", "자기자본대비"),
		"F5", List.of("자산총액대비(%)", "자산총액대비"),
		"I1", List.of("자기자본대비(%)", "자기자본대비"));

	private static final String SHARE_ISSUE = "D1";

	private static final String INSOLVENCY = "I3";

	private static final String DISSOLUTION_WORD = "해산사유";

	private final ImpactProperties impact;
	private final AiImpactProperties ai;

	public AiSizeRules(ImpactProperties impact, AiImpactProperties ai) {
		this.impact = impact;
		this.ai = ai;
	}

	/**
	 * 판정에 앞서 본문 표에서 비율을 읽는다. AI에 함께 주고, 크기를 정할 때 쓴다.
	 *
	 * @param typeCode  판정에 쓰는 유형 (AI가 고른 유형이 있으면 그것)
	 * @param documents 본문 문서들
	 * @return 읽은 비율 칸 (칸 이름 → 본문에 적힌 값). 비율 유형이 아니거나 칸이 없으면 비어 있음
	 */
	public Map<String, String> ratioItems(String typeCode, List<BodyDocument> documents) {
		List<String> labels = RATIO_LABELS.get(typeCode);
		Map<String, String> items = new LinkedHashMap<>();
		if (labels == null || documents.isEmpty()) {
			return items;
		}
		BodyTables tables = BodyTables.of(documents);
		for (String label : labels) {
			Optional<String> value = tables.valueAfterLabel(label).filter(v -> !Cells.isBlank(v));
			if (value.isPresent()) {
				items.put(label, value.get().strip());
				break;
			}
		}
		return items;
	}

	/**
	 * 크기를 정한다.
	 *
	 * @return 크기와 그때 본 비율. 중립이면 비어 있음
	 */
	public Optional<Size> size(SizeInput input, Map<String, String> ratioItems) {
		if (input.tone() != Tone.POSITIVE && input.tone() != Tone.NEGATIVE) {
			return Optional.empty();
		}
		Size size = rawSize(input, ratioItems);
		int stepped = input.subsidiary() ? impact.stepDownForSubsidiary(size.impact()) : size.impact();
		return Optional.of(new Size(stepped, size.ratio(), size.basis()));
	}

	private Size rawSize(SizeInput input, Map<String, String> ratioItems) {
		String type = input.typeCode();
		if (PICKING_TYPES.contains(type)) {
			Integer chosen = input.aiSize();
			int value = chosen == null ? ai.fallback() : Math.max(1, Math.min(AiImpactProperties.MAX_SIZE, chosen));
			return new Size(value, null, "AI_SIZE");
		}
		if (RATIO_LABELS.containsKey(type)) {
			BigDecimal ratio = ratioItems.values().stream().findFirst().flatMap(Cells::number).map(BigDecimal::abs).orElse(null);
			return new Size(impact.amount().sizeOf(ratio), ratio, ratio == null ? "RATIO_MISSING" : "RATIO");
		}
		if (SHARE_ISSUE.equals(type)) {
			BigDecimal ratio = input.savedRatio();
			return new Size(impact.shareIncrease().sizeOf(ratio), ratio, ratio == null ? "RATIO_MISSING" : "RATIO");
		}
		if (INSOLVENCY.equals(type) && input.title() != null && input.title().contains(DISSOLUTION_WORD)) {
			return new Size(ai.dissolution(), null, "DISSOLUTION");
		}
		Map<String, Integer> byCase = ai.cases().get(type);
		if (byCase != null && input.caseCode() != null && byCase.containsKey(input.caseCode())) {
			return new Size(byCase.get(input.caseCode()), null, "CASE");
		}
		AiImpactProperties.ToneSizes byTone = ai.byTone().get(type);
		if (byTone != null) {
			return new Size(input.tone() == Tone.POSITIVE ? byTone.positive() : byTone.negative(), null, "TONE");
		}
		Integer fixed = ai.fixed().get(type);
		if (fixed != null) {
			return new Size(fixed, null, "FIXED");
		}
		return new Size(ai.fallback(), null, "FALLBACK");
	}

	/**
	 * 크기를 정할 때 보는 것.
	 *
	 * @param typeCode       판정에 쓰는 유형 (AI가 고른 유형이 있으면 그것)
	 * @param tone           AI가 정한 방향
	 * @param caseCode       AI가 함께 고른 경우. 없으면 null
	 * @param subsidiary     자회사의 공시인지
	 * @param title          머리말을 뗀 제목
	 * @param savedRatio     표 읽기가 공시에 적어 둔 원래 비율(%). 없으면 null
	 * @param aiSize         AI가 고른 크기(맞는 유형이 없는 포괄 제목 등). 없으면 null
	 */
	public record SizeInput(String typeCode, Tone tone, String caseCode, boolean subsidiary, String title,
			BigDecimal savedRatio, Integer aiSize) {
	}

	/**
	 * 정한 크기.
	 *
	 * @param impact 크기 (1 이상)
	 * @param ratio  크기를 정할 때 본 원래 비율(%). 비율로 정하지 않았거나 읽지 못했으면 null
	 * @param basis  어떻게 정했는지 (RATIO, CASE, FIXED 등). 실행 기록에 남긴다
	 */
	public record Size(int impact, BigDecimal ratio, String basis) {
	}
}
