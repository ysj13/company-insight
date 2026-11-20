package com.companyinsight.analysis.table;

import com.companyinsight.analysis.table.BodyTables.Found;
import com.companyinsight.disclosure.Tone;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * 유상증자 결정(D1)의 표를 읽는다.
 *
 * <p>방향은 "증자방식"으로 나눈다. 주주배정·일반공모·주주우선공모는 악재(주식 수가 늘어 지분이 묽어진다).
 * 제3자배정은 누구에게 왜 배정하는지에 따라 달라 코드가 정하지 않고 AI가 본문을 읽는다.
 * 그 밖의 글자가 적혀 있으면 읽지 못한 것으로 답한다.
 *
 * <p>자회사(종속회사)의 유상증자를 모회사가 알리는 공시는 증자방식과 관계없이 AI가 읽는다. 모회사의 주식 수는 늘지 않고,
 * 모회사가 자회사에 돈을 넣는 일인지 자회사가 남에게 주식을 내주는 일인지에 따라 뜻이 달라진다.
 *
 * <p>크기는 신주 수가 증자 전 발행주식총수의 몇 %인지로 정한다(보통주식과 그 밖의 주식을 더한다).
 * 비율을 낼 수 없으면(총수가 비었거나 0) 크기 1이다. 제3자배정도 비율은 읽어 둔다.
 */
@Component
class PaidInCapitalReader implements TableReader {

	static final String TYPE = "D1";

	private static final String METHOD = "증자방식";
	private static final String NEW_SHARES = "신주의 종류와 수";
	private static final String SHARES_BEFORE = "증자전 발행주식총수";

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private final ImpactProperties.ShareIncrease sizes;

	PaidInCapitalReader(ImpactProperties properties) {
		this.sizes = properties.shareIncrease();
	}

	@Override
	public Set<String> typeCodes() {
		return Set.of(TYPE);
	}

	@Override
	public TableReading read(ReadTarget target, BodyTables tables) {
		Map<String, String> items = new LinkedHashMap<>();
		List<String> unread = new ArrayList<>();

		String method = tables.valueOf("증자방식").orElse("");
		put(items, METHOD, method);
		Optional<BigDecimal> newShares = sum(tables, label -> label.startsWith("신주의종류와수"), NEW_SHARES, items);
		Optional<BigDecimal> before = sum(tables, label -> label.startsWith("증자전발행주식총수"), SHARES_BEFORE, items);
		sum(tables, label -> label.startsWith("자금조달의목적"), "자금조달의 목적", items);
		tables.valueOf("납입일").ifPresent(value -> put(items, "납입일", value));
		tables.valueOf("신주의상장예정일").ifPresent(value -> put(items, "신주의 상장 예정일", value));

		BigDecimal ratio = null;
		if (newShares.isPresent() && before.isPresent() && before.get().signum() > 0) {
			ratio = newShares.get().multiply(HUNDRED).divide(before.get(), 4, RoundingMode.HALF_UP);
		}
		else {
			if (newShares.isEmpty()) {
				unread.add(NEW_SHARES);
			}
			if (before.isEmpty() || before.get().signum() <= 0) {
				unread.add(SHARES_BEFORE);
			}
		}

		if (target.subsidiary()) {
			// 자회사 주식 수의 비율은 모회사에게 크기의 기준이 되지 못한다. 비율을 남기지 않는다
			return TableReading.needsAi("SUBSIDIARY", null, items, unread);
		}
		String compact = Cells.compact(method);
		if (compact.contains("제3자배정")) {
			return TableReading.needsAi("THIRD_PARTY", ratio, items, unread);
		}
		if (compact.contains("주주배정") || compact.contains("일반공모") || compact.contains("주주우선공모")) {
			return TableReading.judged(Tone.NEGATIVE, sizes.sizeOf(ratio), "TO_SHAREHOLDERS_OR_PUBLIC", ratio, items, unread);
		}
		unread.add(METHOD);
		return TableReading.unreadable(Cells.isBlank(method) ? "NO_METHOD" : "UNKNOWN_METHOD", items, unread);
	}

	/**
	 * 한 항목이 주식 종류(또는 자금 목적)별로 여러 줄일 때, 줄마다 마지막 칸의 숫자를 더한다. 값이 든 줄은 항목에 그대로 적는다.
	 * 정정 공시에 같은 항목의 묶음이 여러 번 나오면 마지막 묶음을 읽는다.
	 *
	 * @return 숫자가 든 줄이 하나도 없으면 비어 있음
	 */
	private static Optional<BigDecimal> sum(BodyTables tables, Predicate<String> labelMatches, String name,
			Map<String, String> items) {
		Predicate<List<String>> matches = row -> row.size() >= 2 && labelMatches.test(Cells.label(row.getFirst()));
		Optional<Found> last = tables.lastRow(matches);
		if (last.isEmpty()) {
			return Optional.empty();
		}
		List<List<String>> rows = last.get().table().rows();
		// 마지막 줄에서 위로, 같은 항목이 이어지는 묶음의 첫 줄을 찾는다
		int first = last.get().index();
		while (first > 0 && matches.test(rows.get(first - 1))) {
			first--;
		}
		BigDecimal total = null;
		for (int i = first; i <= last.get().index(); i++) {
			List<String> row = rows.get(i);
			Optional<BigDecimal> value = Cells.number(row.getLast());
			if (value.isPresent()) {
				total = total == null ? value.get() : total.add(value.get());
				items.put(name + ": " + row.get(row.size() - 2), row.getLast());
			}
		}
		return Optional.ofNullable(total);
	}

	private static void put(Map<String, String> items, String name, String value) {
		if (!Cells.isBlank(value)) {
			items.put(name, value);
		}
	}
}
