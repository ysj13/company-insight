package com.companyinsight.analysis.table;

import com.companyinsight.analysis.table.BodyTables.Found;
import com.companyinsight.analysis.table.OwnershipReasons.Kind;
import com.companyinsight.disclosure.Tone;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 지분 보고(A2)의 표를 읽는다. 서식이 셋이고 제목으로 가린다.
 *
 * <ul>
 * <li>임원ㆍ주요주주특정증권등소유상황보고서: "세부변동내역"의 줄마다 보고사유와 증감을 읽는다
 * <li>최대주주등소유주식변동신고서: 사람마다 있는 변동 내역 표의 변경원인과 증감주식수를 읽는다
 * <li>주식등의대량보유상황보고서(일반, 약식): 직전 보고서와 이번 보고서의 주식등의 비율을 읽는다
 * </ul>
 *
 * <p>앞의 두 서식은 매수·매도인 줄({@link OwnershipReasons})의 증감만 더한 순증감으로 방향을 정한다.
 * 순매수면 호재, 순매도면 악재이고, 크기는 순증감이 발행주식총수의 몇 %인지로 정한다. 아주 작은 매매와
 * 매매가 아닌 변동(상여금, 신규 선임, 증여 등)만 있는 보고서는 중립이다.
 * 정해 둔 낱말이 아닌 사유가 한 줄이라도 있으면 추측하지 않고 읽지 못한 것으로 답한다.
 *
 * <p>대량보유상황보고서는 보유비율이 직전 보고서보다 늘면 호재, 줄면 악재, 그대로면 중립이고 크기는 증감 폭(%p)으로 정한다.
 * 다만 세부변동내역에 매수·매도 줄이 하나도 없으면 비율이 움직였어도 중립이다
 * (발행주식총수가 바뀌었거나 특별관계자가 바뀌어 비율만 달라진 보고서). 직전 보고서가 없는 신규 보고도 중립이다.
 *
 * <p>지분 보고의 요약은 AI가 쓰지 않고 여기서 읽은 항목으로 쓴다. 그래서 방향이 정해져도 AI 요약으로 넘기지 않는다.
 *
 * <p>정정 공시에는 정정 전·후 표의 사본이 본문보다 앞에 실릴 수 있어, 한 번만 나와야 하는 표는 문서에서 마지막 것을 읽는다.
 * 보고자에 따라 크기를 달리하지 않는다.
 */
@Component
class OwnershipReportReader implements TableReader {

	static final String TYPE = "A2";

	private static final String TOTAL_SHARES = "발행주식총수";
	private static final String REASON = "변동 사유";
	private static final String NET_TRADE = "매매 순증감 (주, 계산)";
	private static final String BEFORE_RATIO = "직전 보고서 비율(%)";
	private static final String AFTER_RATIO = "이번 보고서 비율(%)";

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	/** 보유목적 칸에 적히는 낱말. 일반 서식에는 항목 이름 없이 이 낱말만 든 표가 있다 */
	private static final Set<String> PURPOSES = Set.of("경영권영향", "일반투자", "단순투자");

	/** 요약에 넣는 보고자 수의 상한. 최대주주 신고서는 한 번에 여러 사람의 변동을 싣는다 */
	private static final int MAX_REPORTERS = 5;

	private final ImpactProperties.Ownership sizes;

	OwnershipReportReader(ImpactProperties properties) {
		this.sizes = properties.ownership();
	}

	@Override
	public Set<String> typeCodes() {
		return Set.of(TYPE);
	}

	@Override
	public boolean summaryByCode() {
		return true;
	}

	@Override
	public TableReading read(ReadTarget target, BodyTables tables) {
		String title = Cells.compact(target.title());
		if (title.contains("대량보유상황보고서")) {
			return blockHolding(tables);
		}
		if (title.contains("최대주주등소유주식변동신고서")) {
			return largestShareholder(tables);
		}
		if (title.contains("임원ㆍ주요주주특정증권등소유상황보고서")) {
			return insider(tables);
		}
		return TableReading.unreadable("UNKNOWN_FORM", Map.of(), List.of("서식"));
	}

	// ── 임원ㆍ주요주주특정증권등소유상황보고서 ─────────────────────────────

	private TableReading insider(BodyTables tables) {
		Map<String, String> items = new LinkedHashMap<>();
		List<String> unread = new ArrayList<>();

		rowValue(tables, "보고자:").ifPresent(value -> put(items, "보고자", value));
		for (Found found : tables.allRows(row -> row.size() >= 3 && Cells.label(row.getFirst()).equals("발행회사와의관계"))) {
			String name = Cells.compact(found.row().get(1));
			if (name.equals("임원(등기여부)")) {
				put(items, "임원", found.row().get(2));
				int position = column(found.row(), "직위명");
				if (position >= 0) {
					put(items, "직위", TableGrid.at(found.row(), position + 1));
				}
			}
			else if (name.equals("주요주주")) {
				put(items, "주요주주", found.row().get(2));
			}
		}
		tables.valueOf("보고구분").ifPresent(value -> put(items, "보고구분", value));
		holdingRows(tables, items, "특정증권등의 수(주)");
		Optional<String> total = tables.valueOf("발행주식총수");
		total.ifPresent(value -> put(items, TOTAL_SHARES, value));

		Trades trades = new Trades();
		Optional<Found> header = tables.lastRow(row -> !row.isEmpty() && Cells.compact(row.getFirst()).equals("보고사유"));
		if (header.isPresent()) {
			List<List<String>> rows = header.get().table().rows();
			int delta = columnIn(rows, header.get().index(), "증감");
			for (int i = header.get().index() + 1; i < rows.size() && delta >= 0; i++) {
				List<String> row = rows.get(i);
				String first = Cells.compact(TableGrid.at(row, 0));
				if (first.equals("보고사유") || first.equals("합계")) {
					continue;
				}
				trades.add(TableGrid.at(row, 0), TableGrid.at(row, delta));
			}
		}
		return byTrades(trades, total.flatMap(Cells::number), items, unread);
	}

	// ── 최대주주등소유주식변동신고서 ────────────────────────────────────

	private TableReading largestShareholder(BodyTables tables) {
		Map<String, String> items = new LinkedHashMap<>();
		List<String> unread = new ArrayList<>();

		List<String> reporters = new ArrayList<>();
		Trades trades = new Trades();
		List<Found> headers = tables.allRows(row -> column(row, "변경일") == 0 && column(row, "변경원인") > 0);
		for (Found header : headers) {
			List<List<String>> rows = header.table().rows();
			int reason = column(header.row(), "변경원인");
			int delta = column(header.row(), "증감주식수");
			boolean changed = false;
			for (int i = header.index() + 1; i < rows.size() && delta >= 0; i++) {
				changed |= trades.add(TableGrid.at(rows.get(i), reason), TableGrid.at(rows.get(i), delta));
			}
			if (changed) {
				reporters.add(reporter(rows, header.index()));
			}
		}
		if (!reporters.isEmpty()) {
			String names = reporters.stream().limit(MAX_REPORTERS).collect(Collectors.joining(", "));
			items.put("보고자", reporters.size() > MAX_REPORTERS ? names + " 외 " + (reporters.size() - MAX_REPORTERS) + "명" : names);
		}

		// 소유주식 표: 직전·이번 보고서와 증감의 합계 줄
		for (Found found : tables.allRows(row -> row.size() >= 5 && Cells.compact(row.get(row.size() - 3)).equals("합계"))) {
			List<String> row = found.row();
			String group = Cells.compact(row.getFirst());
			String name = switch (group) {
				case "직전보고서제출일" -> "직전 보고서";
				case "이번보고서제출일" -> "이번 보고서";
				case "증감" -> "증감";
				default -> null;
			};
			if (name != null) {
				put(items, name + " 주식 수(주)", row.get(row.size() - 2));
				put(items, name + " 비율(%)", row.getLast());
			}
		}

		Optional<BigDecimal> total = Optional.empty();
		Optional<Found> totalHeader = tables.lastRow(row -> totalColumn(row) >= 0);
		if (totalHeader.isPresent()) {
			List<List<String>> rows = totalHeader.get().table().rows();
			int next = totalHeader.get().index() + 1;
			if (next < rows.size()) {
				String value = TableGrid.at(rows.get(next), totalColumn(totalHeader.get().row()));
				put(items, TOTAL_SHARES, value);
				total = Cells.number(value);
			}
		}
		return byTrades(trades, total, items, unread);
	}

	/** 사람마다 있는 표에서 성명과 회사와의 관계를 읽는다: "이름(관계)". 변동 내역의 머리 줄보다 위에 있다. */
	private static String reporter(List<List<String>> rows, int headerIndex) {
		String name = "";
		String relation = "";
		for (int i = 0; i < headerIndex; i++) {
			List<String> row = rows.get(i);
			String first = Cells.compact(TableGrid.at(row, 0));
			if (first.equals("성명")) {
				name = valueAfterLabel(row);
			}
			else if (first.equals("최대주주및발행회사와의관계")) {
				relation = valueAfterLabel(row);
			}
		}
		return Cells.isBlank(relation) ? name : name + "(" + relation + ")";
	}

	private static int totalColumn(List<String> row) {
		for (int i = row.size() - 1; i >= 0; i--) {
			if (Cells.compact(row.get(i)).startsWith("발행주식총수")) {
				return i;
			}
		}
		return -1;
	}

	// ── 주식등의대량보유상황보고서 (일반, 약식) ───────────────────────────

	private TableReading blockHolding(BodyTables tables) {
		Map<String, String> items = new LinkedHashMap<>();
		List<String> unread = new ArrayList<>();

		rowValue(tables, "보고자:").ifPresent(value -> put(items, "보고자", value));
		// 첫 장 요약표의 "발행회사명 | … | 발행회사와의 관계 | …" 줄. 뒤에 나오는 특별관계자 표의 머리 줄에는 값이 없다
		tables.allRows(row -> column(row, "발행회사와의관계") > 0)
			.stream()
			.map(found -> found.valueAfter(column(found.row(), "발행회사와의관계")))
			.filter(value -> !Cells.isBlank(value))
			.findFirst()
			.ifPresent(value -> put(items, "발행회사와의 관계", value));
		tables.valueOf("보고구분").ifPresent(value -> put(items, "보고구분", value));
		purpose(tables).ifPresent(value -> put(items, "보유목적", value));
		tables.valueOf("보고사유").ifPresent(value -> put(items, "보고사유", value));
		tables.valueOf("변동방법").ifPresent(value -> put(items, "변동방법", value));
		tables.valueOf("변동사유").ifPresent(value -> put(items, "변동사유", value));

		// 세부변동내역: 보고자와 특별관계자의 줄마다 취득/처분방법과 증감
		Trades trades = new Trades();
		for (Found header : tables.allRows(row -> column(row, "취득/처분방법") > 0)) {
			List<List<String>> rows = header.table().rows();
			int method = column(header.row(), "취득/처분방법");
			for (int i = header.index() + 1; i < rows.size(); i++) {
				String cell = TableGrid.at(rows.get(i), method);
				if (!Cells.compact(cell).equals("취득/처분방법")) {
					trades.addReason(cell);
				}
			}
		}
		if (trades.rows > 0) {
			items.put(REASON, trades.reasons());
		}

		Optional<String[]> holding = holdingRows(tables, items, "주식등의 수(주)");
		Optional<BigDecimal> after = holding.flatMap(values -> Cells.number(values[1]));
		if (after.isEmpty()) {
			unread.add(AFTER_RATIO);
			return TableReading.unreadable("NO_HOLDING_RATIO", items, unread);
		}
		Optional<BigDecimal> before = Cells.number(holding.get()[0]);
		if (before.isEmpty()) {
			if (!Cells.isBlank(holding.get()[0])) {
				unread.add(BEFORE_RATIO);
				return TableReading.unreadable("NO_HOLDING_RATIO", items, unread);
			}
			// 직전 보고서가 없다: 처음 5%를 넘겨 내는 신규 보고
			return TableReading.neutral("NEW_REPORT", null, items, unread);
		}
		BigDecimal change = after.get().subtract(before.get());
		int size = sizes.sizeOfHolding(change.abs());
		if (size == 0) {
			return TableReading.neutral("RATIO_UNCHANGED", change, items, unread);
		}
		if (trades.tradeRows == 0) {
			if (!trades.unknown.isEmpty()) {
				unread.add(REASON + ": " + String.join(", ", trades.unknown));
				return TableReading.unreadable("UNKNOWN_REASON", items, unread);
			}
			// 사고판 줄이 없다: 발행주식총수가 바뀌었거나 특별관계자가 바뀌어 비율만 움직였다
			return TableReading.neutral("RATIO_MOVED_WITHOUT_TRADE", change, items, unread);
		}
		return change.signum() > 0 ? TableReading.judged(Tone.POSITIVE, size, "RATIO_UP", change, items, unread)
				: TableReading.judged(Tone.NEGATIVE, size, "RATIO_DOWN", change, items, unread);
	}

	/** 보유목적. 약식 서식은 "보유목적" 줄에, 일반 서식은 항목 이름 없이 낱말만 든 한 칸짜리 표에 적힌다. */
	private static Optional<String> purpose(BodyTables tables) {
		Optional<String> labeled = tables.valueOf("보유목적").filter(value -> !Cells.isBlank(value));
		if (labeled.isPresent()) {
			return labeled;
		}
		return tables.lastRow(row -> row.size() == 1 && PURPOSES.contains(Cells.compact(row.getFirst())))
			.filter(found -> found.table().rows().size() == 1)
			.map(found -> found.row().getFirst());
	}

	// ── 함께 쓰는 것 ────────────────────────────────────────────────

	/**
	 * "직전보고서 / 이번보고서" 줄의 수와 비율을 읽어 항목에 적는다. 비율은 머리 줄에서 처음 나오는 "비율(%)" 열이고
	 * (특정증권등 또는 주식등의 비율. 그 뒤의 열은 주권만 센 비율이다) 수는 그 앞 열이다.
	 *
	 * @param countName 수 항목의 이름
	 * @return 직전 보고서와 이번 보고서의 비율 칸에 적힌 글. 두 줄과 비율 열을 찾지 못하면 비어 있음
	 */
	private static Optional<String[]> holdingRows(BodyTables tables, Map<String, String> items, String countName) {
		Optional<Found> after = tables.lastRow(row -> !row.isEmpty() && Cells.compact(row.getFirst()).equals("이번보고서"));
		if (after.isEmpty() || after.get().index() == 0) {
			return Optional.empty();
		}
		List<List<String>> rows = after.get().table().rows();
		List<String> before = rows.get(after.get().index() - 1);
		if (!Cells.compact(TableGrid.at(before, 0)).equals("직전보고서")) {
			return Optional.empty();
		}
		int ratio = -1;
		for (int i = 0; i < after.get().index() && ratio < 0; i++) {
			ratio = column(rows.get(i), "비율(%)");
		}
		if (ratio < 1) {
			return Optional.empty();
		}
		put(items, "직전 보고서 " + countName, TableGrid.at(before, ratio - 1));
		put(items, BEFORE_RATIO, TableGrid.at(before, ratio));
		put(items, "이번 보고서 " + countName, TableGrid.at(after.get().row(), ratio - 1));
		put(items, AFTER_RATIO, TableGrid.at(after.get().row(), ratio));
		return Optional.of(new String[] { TableGrid.at(before, ratio), TableGrid.at(after.get().row(), ratio) });
	}

	/** 매수·매도 줄의 순증감으로 방향과 크기를 정한다. */
	private TableReading byTrades(Trades trades, Optional<BigDecimal> totalShares, Map<String, String> items, List<String> unread) {
		if (trades.rows == 0) {
			unread.add(REASON);
			return TableReading.unreadable("NO_CHANGE_ROWS", items, unread);
		}
		items.put(REASON, trades.reasons());
		if (!trades.unknown.isEmpty()) {
			unread.add(REASON + ": " + String.join(", ", trades.unknown));
			return TableReading.unreadable("UNKNOWN_REASON", items, unread);
		}
		if (trades.badAmount) {
			unread.add("증감");
			return TableReading.unreadable("TRADE_AMOUNT", items, unread);
		}
		if (trades.tradeRows == 0) {
			return TableReading.neutral("NOT_TRADE", null, items, unread);
		}
		items.put(NET_TRADE, trades.net.toPlainString());
		if (trades.net.signum() == 0) {
			return TableReading.neutral("NET_ZERO", BigDecimal.ZERO, items, unread);
		}
		if (totalShares.isEmpty() || totalShares.get().signum() <= 0) {
			unread.add(TOTAL_SHARES);
			return TableReading.unreadable("NO_TOTAL_SHARES", items, unread);
		}
		BigDecimal ratio = trades.net.multiply(HUNDRED).divide(totalShares.get(), 6, RoundingMode.HALF_UP);
		int size = sizes.sizeOfTrade(ratio.abs());
		if (size == 0) {
			return TableReading.neutral("SMALL_TRADE", ratio, items, unread);
		}
		return trades.net.signum() > 0 ? TableReading.judged(Tone.POSITIVE, size, "NET_BUY", ratio, items, unread)
				: TableReading.judged(Tone.NEGATIVE, size, "NET_SELL", ratio, items, unread);
	}

	/** 이 글(공백을 없애고 견준다)이 든 칸이 있는 마지막 줄에서, 그 칸 뒤에 처음 나오는 다른 칸. */
	private static Optional<String> rowValue(BodyTables tables, String compactLabel) {
		return tables.lastRow(row -> column(row, compactLabel) >= 0)
			.map(found -> found.valueAfter(column(found.row(), compactLabel)));
	}

	/** 줄에서 이 글(공백을 없애고 견준다)이 든 첫 자리. 없으면 -1. */
	private static int column(List<String> row, String compactLabel) {
		for (int i = 0; i < row.size(); i++) {
			if (Cells.compact(row.get(i)).equals(compactLabel)) {
				return i;
			}
		}
		return -1;
	}

	/** 머리 줄과 그 아랫줄(두 줄짜리 머리)에서 이 글이 든 자리. 없으면 -1. */
	private static int columnIn(List<List<String>> rows, int headerIndex, String compactLabel) {
		int at = column(rows.get(headerIndex), compactLabel);
		if (at < 0 && headerIndex + 1 < rows.size()) {
			at = column(rows.get(headerIndex + 1), compactLabel);
		}
		return at;
	}

	/** 줄의 첫 칸(항목 이름) 뒤에 처음 나오는, 첫 칸과 다른 칸. */
	private static String valueAfterLabel(List<String> row) {
		for (int i = 1; i < row.size(); i++) {
			if (!row.get(i).equals(row.getFirst())) {
				return row.get(i);
			}
		}
		return "";
	}

	private static void put(Map<String, String> items, String name, String value) {
		if (!Cells.isBlank(value)) {
			items.put(name, value);
		}
	}

	/** 변동 내역의 줄들을 모은 것. */
	private static final class Trades {

		/** 사유가 적힌 줄 수 */
		private int rows;

		/** 그 가운데 매수·매도인 줄 수 */
		private int tradeRows;

		/** 매수·매도 줄의 증감을 더한 값 (주) */
		private BigDecimal net = BigDecimal.ZERO;

		/** 매수·매도 줄의 증감을 읽지 못했거나 부호가 사유와 다르다 */
		private boolean badAmount;

		/** 정해 둔 낱말이 아닌 사유 */
		private final Set<String> unknown = new LinkedHashSet<>();

		/** 사유별 줄 수. 나온 순서대로 */
		private final Map<String, Integer> counts = new LinkedHashMap<>();

		/**
		 * 한 줄을 더한다. 사유와 증감이 모두 빈 줄은 세지 않는다.
		 *
		 * @return 줄을 셌으면 true
		 */
		boolean add(String reasonCell, String deltaCell) {
			if (Cells.isBlank(reasonCell) && Cells.isBlank(deltaCell)) {
				return false;
			}
			rows++;
			String word = Cells.compact(reasonCell);
			counts.merge(word, 1, Integer::sum);
			Optional<Kind> kind = OwnershipReasons.kindOf(reasonCell);
			if (kind.isEmpty()) {
				unknown.add(Cells.squeeze(reasonCell));
				return true;
			}
			if (kind.get() == Kind.NOT_TRADE) {
				return true;
			}
			tradeRows++;
			Optional<BigDecimal> delta = Cells.number(deltaCell);
			// 매수가 줄거나 매도가 늘 수는 없다. 그러면 표를 잘못 읽은 것이다 (증감을 0으로 적은 줄은 실제 공시에 있다)
			Predicate<BigDecimal> signMatches = value -> kind.get() == Kind.BUY ? value.signum() >= 0 : value.signum() <= 0;
			if (delta.isEmpty() || !signMatches.test(delta.get())) {
				badAmount = true;
			}
			else {
				net = net.add(delta.get());
			}
			return true;
		}

		/** 사유만 센다 (증감은 보지 않는다). 사유가 빈 줄은 세지 않는다. */
		void addReason(String reasonCell) {
			if (Cells.isBlank(reasonCell)) {
				return;
			}
			rows++;
			counts.merge(Cells.compact(reasonCell), 1, Integer::sum);
			Optional<Kind> kind = OwnershipReasons.kindOf(reasonCell);
			if (kind.isEmpty()) {
				unknown.add(Cells.squeeze(reasonCell));
			}
			else if (kind.get() != Kind.NOT_TRADE) {
				tradeRows++;
			}
		}

		/** "장내매수(+) 3건, 증여(-) 1건" */
		String reasons() {
			return counts.entrySet().stream().map(e -> e.getKey() + " " + e.getValue() + "건").collect(Collectors.joining(", "));
		}
	}
}
