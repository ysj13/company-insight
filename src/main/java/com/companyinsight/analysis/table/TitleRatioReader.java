package com.companyinsight.analysis.table;

import com.companyinsight.disclosure.Tone;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 제목으로 방향이 정해지는 유형 가운데, 본문 표에 적힌 비율 하나로 크기를 정하는 유형의 표를 읽는다.
 * 방향은 정하지 않는다(제목이 정한 방향을 그대로 돌려준다).
 *
 * <ul>
 * <li>공급계약 체결(C1)·해지(C2): 계약(해지)금액의 "매출액대비(%)". 금액 경계
 * <li>시설·사업 투자(F8), 소송 제기(I1): "자기자본대비(%)". 금액 경계
 * <li>주식 관련 사채 발행(D3), 자기사채 매도(L4): 전환(행사·교환)으로 발행할 주식의 "주식총수 대비비율(%)". 주식 수 경계(늘어남)
 * <li>전환·신주인수권·교환 청구 행사(L2): 행사 주식 수의 "발행주식총수 대비(%)". 주식 수 경계(늘어남)이고, 하한 아래면 중립
 * <li>횡령·배임·제재(I2): 서식으로 가른다. 횡령·배임은 "자기자본대비(%)"로 금액 경계를 쓰되 가장 작은 크기를 정해 두고,
 *   벌금 등의 부과는 금액 경계, 회계처리기준 위반 조치는 금액이 없어 정해 둔 크기다
 * </ul>
 *
 * <p>비율 칸이 비었거나("-", 공시 유보) 칸이 없으면 크기 1이다(영향도의 틀의 공통 규칙). 크기를 추측하지 않고, 읽지 못한 항목에 남긴다.
 */
@Component
class TitleRatioReader implements TableReader {

	private static final String SALES_RATIO = "매출액대비(%)";
	private static final String EQUITY_RATIO = "자기자본대비(%)";
	private static final String SHARE_RATIO = "주식총수대비비율(%)";

	/** 유형마다: 비율 칸의 항목 이름(먼저 찾는 것부터), 크기의 경계, 요약에 쓸 항목 이름 */
	private static final Map<String, Spec> SPECS = Map.of(
		"C1", new Spec(List.of(SALES_RATIO), Scale.AMOUNT,
			List.of("판매ㆍ공급계약구분", "체결계약명", "계약금액(원)", "최근매출액(원)", "계약상대", "계약상대방", "계약(수주)일자")),
		"C2", new Spec(List.of(SALES_RATIO), Scale.AMOUNT,
			List.of("판매ㆍ공급계약해지내용", "해지금액(원)", "최근매출액(원)", "계약상대방", "주요해지사유", "해지일자")),
		"F8", new Spec(List.of(EQUITY_RATIO), Scale.AMOUNT, List.of("투자구분", "투자금액(원)", "자기자본(원)", "투자목적")),
		"I1", new Spec(List.of(EQUITY_RATIO), Scale.AMOUNT, List.of("사건의명칭", "청구금액(원)", "자기자본(원)", "관할법원")),
		"I2", new Spec(List.of(EQUITY_RATIO), Scale.MISCONDUCT, List.of("발생금액(원)", "부과금액(원)", "자기자본(원)")),
		"D3", new Spec(List.of(SHARE_RATIO), Scale.SHARE_INCREASE,
			List.of("사채의권면(전자등록)총액(원)", "전환가액(원/주)", "행사가액(원/주)", "교환가액(원/주)")),
		"L4", new Spec(List.of(SHARE_RATIO), Scale.SHARE_INCREASE, List.of("매도대상사채의권면(전자등록)금액(원)", "보고일현재전환가액(원/주)")),
		"L2", new Spec(List.of("발행주식총수대비(%)", "발행주식총수대비"), Scale.EXERCISE, List.of("발행주식총수(주)")));

	private final ImpactProperties properties;

	TitleRatioReader(ImpactProperties properties) {
		this.properties = properties;
	}

	@Override
	public boolean sizeOnly() {
		return true;
	}

	@Override
	public Set<String> typeCodes() {
		return SPECS.keySet();
	}

	/**
	 * @throws IllegalArgumentException 제목이 정한 방향(호재·악재)이 없을 때. 이 규칙은 제목으로 방향이 정해진 공시만 읽는다
	 */
	@Override
	public TableReading read(ReadTarget target, BodyTables tables) {
		Tone tone = target.tone();
		if (tone != Tone.POSITIVE && tone != Tone.NEGATIVE) {
			throw new IllegalArgumentException("제목이 방향을 정한 공시만 읽습니다: " + target);
		}
		Spec spec = SPECS.get(target.typeCode());
		Map<String, String> items = new LinkedHashMap<>();
		List<String> unread = new ArrayList<>();
		for (String label : spec.items()) {
			tables.valueAfterLabel(label).filter(value -> !Cells.isBlank(value)).ifPresent(value -> items.put(label, value));
		}

		BigDecimal ratio = null;
		String ratioLabel = spec.ratioLabels().getFirst();
		for (String label : spec.ratioLabels()) {
			Optional<String> value = tables.valueAfterLabel(label);
			if (value.isPresent()) {
				ratioLabel = label;
				items.put(label, value.get());
				ratio = Cells.number(value.get()).filter(number -> number.signum() >= 0).orElse(null);
				break;
			}
		}
		if (ratio == null) {
			unread.add(ratioLabel);
		}
		String caseCode = ratio == null ? "NO_RATIO" : "RATIO";

		return switch (spec.scale()) {
			case AMOUNT -> TableReading.judged(tone, properties.amount().sizeOf(ratio), caseCode, ratio, items, unread);
			case SHARE_INCREASE -> TableReading.judged(tone, properties.shareIncrease().sizeOf(ratio), caseCode, ratio, items, unread);
			case EXERCISE -> ratio != null && ratio.compareTo(properties.shareIncrease().exerciseNeutralBelow()) < 0
					? TableReading.neutral("SMALL_EXERCISE", ratio, items, unread)
					: TableReading.judged(tone, properties.shareIncrease().sizeOf(ratio), caseCode, ratio, items, unread);
			case MISCONDUCT -> misconduct(target, tone, ratio, caseCode, items, unread);
		};
	}

	/** 횡령·배임·제재. 서식(제목)으로 가른다. */
	private TableReading misconduct(ReadTarget target, Tone tone, BigDecimal ratio, String caseCode, Map<String, String> items,
			List<String> unread) {
		String title = Cells.compact(target.title());
		ImpactProperties.Misconduct sizes = properties.misconduct();
		if (title.contains("회계처리기준위반")) {
			// 증권선물위원회의 조치. 금액이 없는 서식이다
			return TableReading.judged(tone, sizes.accountingViolation(), "ACCOUNTING_VIOLATION", null, items, List.of());
		}
		int size = properties.amount().sizeOf(ratio);
		if (title.contains("횡령") || title.contains("배임")) {
			return TableReading.judged(tone, Math.max(sizes.embezzlementMinimum(), size), "EMBEZZLEMENT", ratio, items, unread);
		}
		return TableReading.judged(tone, size, caseCode, ratio, items, unread);
	}

	/** 크기의 경계. */
	private enum Scale {

		/** 금액 경계 (매출액·자기자본 대비) */
		AMOUNT,

		/** 주식 수 경계 (늘어남) */
		SHARE_INCREASE,

		/** 주식 수 경계 (늘어남)에 하한이 있다. 전환 청구 행사 */
		EXERCISE,

		/** 횡령·배임·제재. 서식마다 다르다 */
		MISCONDUCT
	}

	/**
	 * 유형 하나를 읽는 법.
	 *
	 * @param ratioLabels 비율 칸의 항목 이름. 서식마다 다르게 적힌 것을 먼저 찾을 것부터
	 * @param scale       크기의 경계
	 * @param items       요약에 쓸 항목 이름
	 */
	private record Spec(List<String> ratioLabels, Scale scale, List<String> items) {
	}
}
