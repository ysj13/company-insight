package com.companyinsight.analysis.table;

import com.companyinsight.disclosure.Tone;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 주식 소각(E1)의 크기를 읽는다. 방향은 제목이 정한다(호재).
 *
 * <p>본문에 비율이 적혀 있지 않아 계산한다: 소각할 주식 수 ÷ 발행주식 총수 × 100. 두 항목 모두 보통주식과 종류주식 두 줄이라 줄마다 더한다.
 * 크기는 주식 수 경계(줄어듦)로 정하고, 비율을 낼 수 없으면(어느 한쪽이 비었거나 총수가 0) 크기 1이다.
 */
@Component
class ShareCancellationReader implements TableReader {

	private static final String CANCELLED = "소각할 주식의 종류와 수";
	private static final String ISSUED = "발행주식 총수";

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private final ImpactProperties.ShareDecrease sizes;

	ShareCancellationReader(ImpactProperties properties) {
		this.sizes = properties.shareDecrease();
	}

	@Override
	public Set<String> typeCodes() {
		return Set.of("E1");
	}

	/**
	 * @throws IllegalArgumentException 제목이 정한 방향(호재·악재)이 없을 때
	 */
	@Override
	public TableReading read(ReadTarget target, BodyTables tables) {
		Tone tone = target.tone();
		if (tone != Tone.POSITIVE && tone != Tone.NEGATIVE) {
			throw new IllegalArgumentException("제목이 방향을 정한 공시만 읽습니다: " + target);
		}
		Map<String, String> items = new LinkedHashMap<>();
		List<String> unread = new ArrayList<>();
		Optional<BigDecimal> cancelled = tables.sumOfRows(label -> label.startsWith("소각할주식의종류와수"), CANCELLED, items);
		Optional<BigDecimal> issued = tables.sumOfRows(label -> label.equals("발행주식총수"), ISSUED, items);
		tables.valueOf("소각예정금액(원)").filter(value -> !Cells.isBlank(value)).ifPresent(value -> items.put("소각예정금액(원)", value));
		tables.valueOf("소각할주식의취득방법").filter(value -> !Cells.isBlank(value)).ifPresent(value -> items.put("소각할 주식의 취득방법", value));
		tables.valueOf("소각예정일").filter(value -> !Cells.isBlank(value)).ifPresent(value -> items.put("소각 예정일", value));

		BigDecimal ratio = null;
		if (cancelled.isPresent() && issued.isPresent() && issued.get().signum() > 0) {
			ratio = cancelled.get().multiply(HUNDRED).divide(issued.get(), 4, RoundingMode.HALF_UP);
		}
		else {
			if (cancelled.isEmpty()) {
				unread.add(CANCELLED);
			}
			if (issued.isEmpty() || issued.get().signum() <= 0) {
				unread.add(ISSUED);
			}
		}
		return TableReading.judged(tone, sizes.sizeOf(ratio), ratio == null ? "NO_RATIO" : "RATIO", ratio, items, unread);
	}
}
