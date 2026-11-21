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
 * 자산 재평가 결과(B6)의 표를 읽는다. 재평가 실시 결정은 제목으로 중립이라 여기까지 오지 않는다.
 *
 * <p>"재평가 내역"의 합계 줄(장부가액, 재평가금액, 재평가차액)로 정한다. 재평가로 자산이 늘었으면(차액이 0보다 크면) 호재다.
 * 자본(재평가잉여금)이 함께 늘어난다. 차액이 0이면 중립이다. 자산이 줄었으면(재평가 손실) 정해 둔 규칙이 없어 AI가 읽는다.
 * 세 숫자가 서로 맞지 않으면(재평가금액 − 장부가액 ≠ 차액) 읽지 못한 것으로 답한다. 크기는 비율 없이 정해 둔 값이다.
 */
@Component
class RevaluationReader implements TableReader {

	private static final String TOTAL = "재평가 내역의 합계";

	private final int size;

	RevaluationReader(ImpactProperties properties) {
		this.size = properties.fixed().revaluationGain();
	}

	@Override
	public Set<String> typeCodes() {
		return Set.of("B6");
	}

	@Override
	public TableReading read(ReadTarget target, BodyTables tables) {
		Map<String, String> items = new LinkedHashMap<>();
		List<String> unread = new ArrayList<>();
		tables.valueOf("재평가목적물").filter(value -> !Cells.isBlank(value)).ifPresent(value -> items.put("재평가 목적물", value));
		tables.valueOf("재평가기준일").filter(value -> !Cells.isBlank(value)).ifPresent(value -> items.put("재평가 기준일", value));
		tables.valueAfterLabel("재평가차액의자산총액대비비율(%)")
			.filter(value -> !Cells.isBlank(value))
			.ifPresent(value -> items.put("재평가차액의 자산총액대비 비율(%)", value));

		// 합계 줄은 머리 줄("재평가 목적물 | 장부가액 | 재평가금액 | 재평가차액")과 자리가 맞는다. 그 열의 숫자를 읽는다
		Optional<BodyTables.Found> total = tables.lastRow(row -> !row.isEmpty() && Cells.label(row.getFirst()).equals("합계"));
		Optional<List<String>> header = total.flatMap(found -> header(found));
		List<BigDecimal> numbers = new ArrayList<>();
		if (total.isPresent() && header.isPresent()) {
			for (String name : List.of("장부가액", "재평가금액", "재평가차액")) {
				int column = header.get().stream().map(Cells::compact).toList().indexOf(name);
				String cell = TableGrid.at(total.get().row(), column);
				if (!Cells.isBlank(cell)) {
					items.put(name, cell);
				}
				Cells.number(cell).ifPresent(numbers::add);
			}
		}
		if (numbers.size() < 3) {
			unread.add(TOTAL);
			return TableReading.unreadable("NO_TOTAL", items, unread);
		}
		BigDecimal book = numbers.get(0);
		BigDecimal revalued = numbers.get(1);
		BigDecimal difference = numbers.get(2);
		if (revalued.subtract(book).compareTo(difference) != 0) {
			unread.add(TOTAL);
			return TableReading.unreadable("TOTAL_MISMATCH", items, unread);
		}
		return switch (difference.signum()) {
			case 1 -> TableReading.judged(Tone.POSITIVE, size, "GAIN", null, items, unread);
			case 0 -> TableReading.neutral("NO_CHANGE", null, items, unread);
			default -> TableReading.needsAi("LOSS", null, items, unread);
		};
	}

	/** 합계 줄 위에서 가장 가까운, "재평가차액" 열이 있는 머리 줄. */
	private static Optional<List<String>> header(BodyTables.Found total) {
		List<List<String>> rows = total.table().rows();
		for (int i = total.index() - 1; i >= 0; i--) {
			List<String> row = rows.get(i);
			if (row.stream().map(Cells::compact).anyMatch("재평가차액"::equals)) {
				return Optional.of(row);
			}
		}
		return Optional.empty();
	}
}
