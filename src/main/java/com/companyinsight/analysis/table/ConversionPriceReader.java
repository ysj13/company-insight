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
 * 전환가액·신주인수권 행사가액·교환가액 조정(L1)의 표를 읽는다.
 *
 * <p>"조정에 관한 사항"의 회차별 조정 전·후 가액과 "조정사유"로 정한다.
 * <ul>
 * <li>가액이 내려갔고 사유가 시가 하락(리픽싱)이면 악재다. 같은 사채로 더 많은 주식이 나온다. 사유가 "시가 변동"이어도 가액이 내려갔으면 같다
 * <li>가액이 올랐거나 그대로면 중립이다
 * <li>가액이 내려갔어도 사유가 주식 병합·무상증자·저가 유상증자처럼 주식 수에 맞춘 비례 조정뿐이면 중립이다
 * <li>가액은 내려갔는데 사유가 시가 상승이거나, 회차마다 오르내림이 다르면 표끼리 어긋나 AI가 읽는다
 * <li>가액이나 사유를 읽지 못했거나 정해 두지 않은 사유면 읽지 못한 것으로 답한다
 * </ul>
 * 크기는 비율 없이 정해 둔 값이다.
 */
@Component
class ConversionPriceReader implements TableReader {

	private static final String SECTION = "조정에관한사항";
	private static final String REASON = "조정사유";

	/** 시가가 내려가 가액을 낮춘 것 (리픽싱) */
	private static final List<String> MARKET_DOWN = List.of("시가하락", "시가변동", "주가하락", "리픽싱", "refixing");

	/** 시가가 올라 가액을 올린 것 */
	private static final List<String> MARKET_UP = List.of("시가상승", "시가증가", "주가상승");

	/** 주식 수에 맞춘 비례 조정 */
	private static final List<String> PROPORTIONAL = List.of("병합", "분할", "무상증자", "주식배당", "유상증자", "하회하는발행가액", "합병",
			"감자");

	private final int size;

	ConversionPriceReader(ImpactProperties properties) {
		this.size = properties.fixed().conversionPriceDown();
	}

	@Override
	public Set<String> typeCodes() {
		return Set.of("L1");
	}

	@Override
	public TableReading read(ReadTarget target, BodyTables tables) {
		Map<String, String> items = new LinkedHashMap<>();
		List<String> unread = new ArrayList<>();
		String reason = tables.valueAfterLabel(REASON).orElse("");
		if (!Cells.isBlank(reason)) {
			items.put("조정사유", reason);
		}
		tables.valueAfterLabel("조정가액적용일").filter(value -> !Cells.isBlank(value)).ifPresent(value -> items.put("조정가액 적용일", value));

		List<BigDecimal[]> prices = prices(tables, items);
		if (prices.isEmpty()) {
			unread.add("조정 전·후 가액");
		}
		if (Cells.isBlank(reason)) {
			unread.add(REASON);
		}
		if (!unread.isEmpty()) {
			return TableReading.unreadable(prices.isEmpty() ? "NO_PRICES" : "NO_REASON", items, unread);
		}

		int down = 0;
		int up = 0;
		for (BigDecimal[] pair : prices) {
			int compared = pair[1].compareTo(pair[0]);
			down += compared < 0 ? 1 : 0;
			up += compared > 0 ? 1 : 0;
		}
		if (down > 0 && up > 0) {
			return TableReading.needsAi("MIXED_DIRECTION", null, items, unread);
		}
		if (down == 0) {
			return TableReading.neutral(up > 0 ? "PRICE_UP" : "NO_CHANGE", null, items, unread);
		}
		String words = Cells.compact(reason).toLowerCase(java.util.Locale.ROOT);
		boolean marketDown = MARKET_DOWN.stream().anyMatch(words::contains);
		boolean marketUp = MARKET_UP.stream().anyMatch(words::contains);
		if (marketDown && !marketUp) {
			return TableReading.judged(Tone.NEGATIVE, size, "MARKET_DOWN", null, items, unread);
		}
		if (marketUp) {
			// 가액은 내려갔는데 사유는 시가 상승이다
			return TableReading.needsAi("REASON_MISMATCH", null, items, unread);
		}
		if (PROPORTIONAL.stream().anyMatch(words::contains)) {
			return TableReading.neutral("PROPORTIONAL", null, items, unread);
		}
		unread.add(REASON);
		return TableReading.unreadable("UNKNOWN_REASON", items, unread);
	}

	/**
	 * "조정에 관한 사항"의 회차별 조정 전·후 가액. 머리 줄에서 "조정전 …가액"과 "조정후 …가액" 열을 찾아, 같은 묶음의 다른 줄에서 그 열의 숫자를 읽는다.
	 * 정정 공시처럼 묶음이 여러 번 나오면 마지막 묶음을 읽는다.
	 *
	 * @return 회차마다 {조정 전, 조정 후}. 숫자가 둘 다 읽힌 회차만
	 */
	private static List<BigDecimal[]> prices(BodyTables tables, Map<String, String> items) {
		Optional<BodyTables.Found> header = tables.lastRow(row -> !row.isEmpty() && Cells.label(row.getFirst()).equals(SECTION)
				&& column(row, "조정전") >= 0 && column(row, "조정후") >= 0);
		if (header.isEmpty()) {
			return List.of();
		}
		List<String> head = header.get().row();
		int before = column(head, "조정전");
		int after = column(head, "조정후");
		int round = column(head, "회차");
		List<BigDecimal[]> prices = new ArrayList<>();
		List<List<String>> rows = header.get().table().rows();
		for (int i = header.get().index() + 1; i < rows.size(); i++) {
			List<String> row = rows.get(i);
			if (row.isEmpty() || !Cells.label(row.getFirst()).equals(SECTION)) {
				break;
			}
			Optional<BigDecimal> from = Cells.number(TableGrid.at(row, before));
			Optional<BigDecimal> to = Cells.number(TableGrid.at(row, after));
			if (from.isPresent() && to.isPresent()) {
				prices.add(new BigDecimal[] { from.get(), to.get() });
				String name = round >= 0 ? "회차 " + TableGrid.at(row, round) : "가액";
				items.put(name + ": 조정 전 → 후", TableGrid.at(row, before) + " → " + TableGrid.at(row, after));
			}
		}
		return prices;
	}

	/** 머리 줄에서 이름이 이 말로 시작하고 가액을 적는 열(회차는 이름이 같은 열)의 자리. 없으면 -1. */
	private static int column(List<String> row, String prefix) {
		for (int i = 1; i < row.size(); i++) {
			String label = Cells.compact(row.get(i));
			if (prefix.equals("회차") ? label.equals("회차") : label.startsWith(prefix) && label.contains("가액")) {
				return i;
			}
		}
		return -1;
	}
}
