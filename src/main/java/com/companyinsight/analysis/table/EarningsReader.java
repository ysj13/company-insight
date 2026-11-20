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
 * 실적 공시 두 유형의 표를 읽는다: 잠정실적(B1)과 매출액 또는 손익구조 변동(B4).
 *
 * <p>방향은 영업이익을 견주는 기간(잠정실적은 전년 동기, 손익구조 변동은 직전 사업연도)과 견줘 정한다.
 * <ul>
 * <li>두 기간 모두 흑자: 늘면 호재, 줄면 악재. 증감율의 절댓값이 하한보다 작으면 중립
 * <li>적자에서 흑자로: 호재. 흑자에서 적자로: 악재
 * <li>두 기간 모두 적자: 적자 폭이 늘면 악재, 줄거나 그대로면 중립
 * <li>잠정실적인데 영업이익 줄이 당해·누계 모두 비어 있음(월별 판매·매출만 알리는 공시): 중립.
 *   당해 칸만 비고 누계에 값이 있으면(누계로만 알린 실적) 코드가 정하지 않는다
 * </ul>
 *
 * <p>흑자·적자는 표의 "흑자적자전환여부" 칸이 아니라 두 기간의 영업이익 숫자로 가른다. 그 칸에 전환·지속이 적혀 있는데
 * 숫자와 맞지 않으면 표를 잘못 읽은 것으로 보고 읽지 못한 것으로 답한다. 증감율은 표에 적힌 값을 쓰고, 없으면 두 숫자로 계산한다.
 */
@Component
class EarningsReader implements TableReader {

	static final String PRELIMINARY = "B1";
	static final String STRUCTURE_CHANGE = "B4";

	static final String OPERATING_PROFIT = "영업이익";

	/** 요약에 쓸 줄. 표에 적힌 항목 이름 그대로 */
	private static final List<String> SUMMARY_LINES = List.of("매출액", OPERATING_PROFIT, "당기순이익");

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private final ImpactProperties.Earnings sizes;

	EarningsReader(ImpactProperties properties) {
		this.sizes = properties.earnings();
	}

	@Override
	public Set<String> typeCodes() {
		return Set.of(PRELIMINARY, STRUCTURE_CHANGE);
	}

	@Override
	public TableReading read(ReadTarget target, BodyTables tables) {
		boolean preliminary = PRELIMINARY.equals(target.typeCode());
		Map<String, String> items = new LinkedHashMap<>();
		List<String> unread = new ArrayList<>();

		Optional<Found> profitRow = tables.lastRow(lineOf(OPERATING_PROFIT, preliminary));
		Optional<Columns> columns = profitRow.flatMap(found -> preliminary ? preliminaryColumns(found) : changeColumns(found));
		if (columns.isEmpty()) {
			unread.add(OPERATING_PROFIT);
			return TableReading.unreadable("NO_OPERATING_PROFIT_ROW", items, unread);
		}
		Columns at = columns.get();
		TableGrid table = profitRow.get().table();

		for (String name : SUMMARY_LINES) {
			Optional<List<String>> line = table.rows().stream().filter(lineOf(name, preliminary)).findFirst();
			if (line.isEmpty()) {
				unread.add(name);
				continue;
			}
			put(items, name, TableGrid.at(line.get(), at.current()));
			put(items, name + " (견주는 기간)", TableGrid.at(line.get(), at.previous()));
			put(items, name + " 증감율(%)", TableGrid.at(line.get(), at.rate()));
		}
		if (!preliminary) {
			tables.valueOf("재무제표의종류").ifPresent(value -> put(items, "재무제표의 종류", value));
			tables.valueOf("매출액또는손익구조변동주요원인").ifPresent(value -> put(items, "변동 주요원인", value));
		}

		List<String> row = profitRow.get().row();
		String turnCell = at.turn() < 0 ? "" : TableGrid.at(row, at.turn());
		put(items, "영업이익 흑자적자전환여부", turnCell);
		boolean blankMeansNeutral = preliminary && cumulativeIsBlank(table, at);
		return judge(blankMeansNeutral, TableGrid.at(row, at.current()), TableGrid.at(row, at.previous()),
			TableGrid.at(row, at.rate()), turnCell, items, unread);
	}

	/** 잠정실적 표에서 영업이익의 누계실적 줄도 비어 있는지. 그 줄이 없으면 빈 것으로 본다. */
	private static boolean cumulativeIsBlank(TableGrid table, Columns at) {
		return table.rows().stream()
			.filter(row -> row.size() >= 2 && Cells.label(row.getFirst()).equals(OPERATING_PROFIT)
					&& Cells.compact(row.get(1)).equals("누계실적"))
			.allMatch(row -> Cells.isBlank(TableGrid.at(row, at.current())) && Cells.isBlank(TableGrid.at(row, at.previous())));
	}

	/**
	 * 영업이익 한 줄의 칸으로 방향과 크기를 정한다.
	 *
	 * @param blankMeansNeutral 영업이익 칸이 비었을 때 중립으로 볼지 (영업이익을 아예 알리지 않은 잠정실적만.
	 *                          그 밖에는 읽지 못한 것으로 본다)
	 */
	TableReading judge(boolean blankMeansNeutral, String currentCell, String previousCell, String rateCell, String turnCell,
			Map<String, String> items, List<String> unread) {
		Optional<BigDecimal> current = Cells.number(currentCell);
		Optional<BigDecimal> previous = Cells.number(previousCell);
		if (current.isEmpty()) {
			if (blankMeansNeutral && Cells.isBlank(currentCell)) {
				return TableReading.neutral("NO_OPERATING_PROFIT", null, items, unread);
			}
			unread.add("영업이익 (이번 기간)");
			return TableReading.unreadable("OPERATING_PROFIT_UNREADABLE", items, unread);
		}
		if (previous.isEmpty()) {
			unread.add("영업이익 (견주는 기간)");
			return TableReading.unreadable("NO_COMPARISON", items, unread);
		}
		int currentSign = current.get().signum();
		int previousSign = previous.get().signum();
		if (currentSign == 0 || previousSign == 0) {
			// 0은 흑자도 적자도 아니다. 증감율도 낼 수 없다
			return TableReading.unreadable("ZERO_OPERATING_PROFIT", items, unread);
		}

		String state = currentSign > 0 ? (previousSign > 0 ? "흑자지속" : "흑자전환") : (previousSign > 0 ? "적자전환" : "적자지속");
		String written = Cells.compact(turnCell);
		if (Set.of("흑자전환", "적자전환", "적자지속").contains(written) && !written.equals(state)) {
			unread.add("영업이익 흑자적자전환여부");
			return TableReading.unreadable("TURN_MISMATCH", items, unread);
		}

		Optional<BigDecimal> rate = Cells.number(rateCell);
		switch (state) {
			case "흑자전환":
				return TableReading.judged(Tone.POSITIVE, sizes.turnaround(), "TURN_TO_PROFIT", rate.orElse(null), items, unread);
			case "적자전환":
				return TableReading.judged(Tone.NEGATIVE, sizes.turnaround(), "TURN_TO_LOSS", rate.orElse(null), items, unread);
			case "적자지속":
				// 둘 다 음수다. 더 작은(더 큰 적자) 쪽이 이번 기간이면 적자 폭이 늘었다
				if (current.get().compareTo(previous.get()) < 0) {
					return TableReading.judged(Tone.NEGATIVE, sizes.deficitWidened(), "DEFICIT_WIDENED", rate.orElse(null), items,
						unread);
				}
				return TableReading.neutral("DEFICIT_NOT_WIDENED", rate.orElse(null), items, unread);
			default:
				break;
		}

		// 두 기간 모두 흑자
		int direction = current.get().compareTo(previous.get());
		BigDecimal computed = current.get().subtract(previous.get()).multiply(HUNDRED).divide(previous.get(), 4,
			RoundingMode.HALF_UP);
		BigDecimal used = rate.orElse(computed);
		if (rate.isPresent() && rate.get().signum() != 0 && direction != 0 && rate.get().signum() != Integer.signum(direction)) {
			unread.add("영업이익 증감율(%)");
			return TableReading.unreadable("RATE_MISMATCH", items, unread);
		}
		int size = sizes.sizeOf(used.abs());
		if (size == 0 || direction == 0) {
			return TableReading.neutral("SMALL_CHANGE", used, items, unread);
		}
		return TableReading.judged(direction > 0 ? Tone.POSITIVE : Tone.NEGATIVE, size, direction > 0 ? "PROFIT_UP" : "PROFIT_DOWN",
			used, items, unread);
	}

	/**
	 * 손익 항목 한 줄을 찾는 조건. 잠정실적은 한 항목이 "당해실적"과 "누계실적" 두 줄이라 당해실적 줄만 읽는다.
	 */
	private static Predicate<List<String>> lineOf(String name, boolean preliminary) {
		return row -> row.size() >= 2 && Cells.label(row.getFirst()).equals(name)
				&& (!preliminary || Cells.compact(row.get(1)).equals("당해실적"));
	}

	/**
	 * 잠정실적 표의 열 자리. 머리 줄은 두 줄이다: 윗줄에 "당기실적", "전년동기실적", "전년동기대비"가,
	 * 아랫줄의 "전년동기대비" 아래에 "증감율(%)"과 "흑자적자전환여부"가 있다.
	 */
	private static Optional<Columns> preliminaryColumns(Found profitRow) {
		List<List<String>> rows = profitRow.table().rows();
		for (int i = profitRow.index() - 1; i >= 1; i--) {
			List<String> lower = rows.get(i);
			List<String> upper = rows.get(i - 1);
			int current = indexOf(upper, cell -> cell.equals("당기실적"));
			int previous = indexOf(upper, cell -> cell.equals("전년동기실적"));
			if (current < 0 || previous < 0) {
				continue;
			}
			int rate = -1;
			int turn = -1;
			for (int column = 0; column < Math.min(upper.size(), lower.size()); column++) {
				if (!Cells.compact(upper.get(column)).equals("전년동기대비")) {
					continue;
				}
				String below = Cells.compact(lower.get(column));
				if (below.startsWith("증감율")) {
					rate = column;
				}
				else if (below.contains("전환여부")) {
					turn = column;
				}
			}
			return rate < 0 ? Optional.empty() : Optional.of(new Columns(current, previous, rate, turn));
		}
		return Optional.empty();
	}

	/** 손익구조 변동 표의 열 자리. 머리 줄 하나에 "당해사업연도", "직전사업연도", "증감비율(%)", "흑자적자전환여부"가 있다. */
	private static Optional<Columns> changeColumns(Found profitRow) {
		List<List<String>> rows = profitRow.table().rows();
		for (int i = profitRow.index() - 1; i >= 0; i--) {
			List<String> header = rows.get(i);
			int current = indexOf(header, cell -> cell.equals("당해사업연도"));
			int previous = indexOf(header, cell -> cell.equals("직전사업연도"));
			int rate = indexOf(header, cell -> cell.startsWith("증감비율"));
			if (current >= 0 && previous >= 0 && rate >= 0) {
				return Optional.of(new Columns(current, previous, rate, indexOf(header, cell -> cell.contains("전환여부"))));
			}
		}
		return Optional.empty();
	}

	private static int indexOf(List<String> row, Predicate<String> compactMatches) {
		for (int i = 0; i < row.size(); i++) {
			if (compactMatches.test(Cells.compact(row.get(i)))) {
				return i;
			}
		}
		return -1;
	}

	private static void put(Map<String, String> items, String name, String value) {
		if (!Cells.isBlank(value)) {
			items.put(name, value);
		}
	}

	/**
	 * 영업이익 줄에서 읽을 열 자리.
	 *
	 * @param current  이번 기간의 값
	 * @param previous 견주는 기간의 값
	 * @param rate     증감율(%)
	 * @param turn     흑자적자전환여부. 그 열이 없으면 -1
	 */
	private record Columns(int current, int previous, int rate, int turn) {
	}
}
