package com.companyinsight.analysis.table;

import com.companyinsight.analysis.body.BodyDocument;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 공시 한 건의 본문에 든 표들. 정정 공시의 맨 앞에 붙는 정정 표(정정 전·후를 견준 표)는 뺀다.
 * 정정 공시의 본문에는 고친 내용 전체가 다시 들어 있으므로, 정정 표를 빼면 새 공시와 같은 표가 남는다.
 *
 * <p>금융위원회 서식의 정정 공시는 정정 표 뒤에 "정정 전" 표의 사본을 따로 싣기도 한다. 사본은 본문보다 앞에 오므로,
 * 한 번만 나와야 하는 항목은 마지막에 나온 것을 읽는다({@link #lastRow}).
 *
 * @param tables           정정 표를 뺀 표. 문서에 나온 순서대로
 * @param correctionTables 정정 표. 정정 공시가 아니면 비어 있다
 * @param correctionCells  정정 표의 칸에 적힌 글. 정정 공시가 아니면 비어 있다
 */
record BodyTables(List<TableGrid> tables, List<TableGrid> correctionTables, List<String> correctionCells) {

	/** 정정 표에 이 낱말이 있으면 원래 결정을 거둔 정정일 수 있다 */
	private static final List<String> WITHDRAWAL_WORDS = List.of("철회", "취소", "해제", "취하", "미발생", "미체결");

	BodyTables {
		tables = List.copyOf(tables);
		correctionTables = List.copyOf(correctionTables);
		correctionCells = List.copyOf(correctionCells);
	}

	/**
	 * 정정 표에 원래 결정을 거뒀다는 뜻의 낱말(철회, 취소, 해제, 취하, 미발생, 미체결)이 있는지.
	 * 있으면 본문 표를 새 공시처럼 읽어 방향을 정해서는 안 된다.
	 *
	 * <p>띄어쓰기를 그대로 두고 찾는다. 공백을 없애고 찾으면 "위해 제공"이 "해제"로 걸린다 (실제 정정 공시에서 봤다).
	 */
	boolean correctionMayWithdraw() {
		return correctionCells.stream().anyMatch(cell -> WITHDRAWAL_WORDS.stream().anyMatch(cell::contains));
	}

	/** 본문 문서들의 표를 읽는다. */
	static BodyTables of(List<BodyDocument> documents) {
		List<TableGrid> tables = new ArrayList<>();
		List<TableGrid> correctionTables = new ArrayList<>();
		List<String> correctionCells = new ArrayList<>();
		for (BodyDocument document : documents) {
			for (TableGrid table : TableGrid.parse(document)) {
				if (isCorrectionTable(table)) {
					correctionTables.add(table);
					table.rows().forEach(row -> row.forEach(correctionCells::add));
				}
				else {
					tables.add(table);
				}
			}
		}
		return new BodyTables(tables, correctionTables, correctionCells);
	}

	/** 정정 표인지: 한 줄에 "정정전"과 "정정후" 칸이 함께 있다. */
	static boolean isCorrectionTable(TableGrid table) {
		for (List<String> row : table.rows()) {
			boolean before = false;
			boolean after = false;
			for (String cell : row) {
				String compact = Cells.compact(cell);
				before |= compact.equals("정정전");
				after |= compact.equals("정정후");
			}
			if (before && after) {
				return true;
			}
		}
		return false;
	}

	/** 조건에 맞는 줄 가운데 문서에서 마지막에 나온 것. */
	Optional<Found> lastRow(Predicate<List<String>> matches) {
		Found last = null;
		for (TableGrid table : tables) {
			for (int i = 0; i < table.rows().size(); i++) {
				if (matches.test(table.rows().get(i))) {
					last = new Found(table, i);
				}
			}
		}
		return Optional.ofNullable(last);
	}

	/** 조건에 맞는 줄 모두. 문서에 나온 순서대로. */
	List<Found> allRows(Predicate<List<String>> matches) {
		List<Found> found = new ArrayList<>();
		for (TableGrid table : tables) {
			for (int i = 0; i < table.rows().size(); i++) {
				if (matches.test(table.rows().get(i))) {
					found.add(new Found(table, i));
				}
			}
		}
		return found;
	}

	/**
	 * 항목 이름이 첫 칸인 줄의 값: 그 줄에서 항목 이름 뒤에 처음 나오는, 이름과 다른 칸. 문서에서 마지막에 나온 줄을 읽는다.
	 *
	 * @param label {@link Cells#label}로 다듬은 항목 이름
	 * @return 그 줄이 없으면 비어 있음. 줄은 있는데 값 칸이 없으면 빈 글
	 */
	Optional<String> valueOf(String label) {
		return lastRow(row -> !row.isEmpty() && Cells.label(row.getFirst()).equals(label)).map(Found::firstValue);
	}

	/**
	 * 한 항목이 주식 종류(또는 자금 목적)별로 여러 줄일 때, 줄마다 마지막 칸의 숫자를 더한다. 값이 든 줄은 항목에 그대로 적는다
	 * ("이름: 값 앞의 칸" → 마지막 칸의 글. 값 칸이 여러 칸에 걸쳐 같은 글이 이어지면 그 앞의 칸을 쓴다).
	 * 같은 항목의 묶음이 여러 번 나오면(정정 공시) 마지막 묶음을 읽는다.
	 *
	 * @param labelMatches 첫 칸의 항목 이름({@link Cells#label})이 이 항목인지
	 * @param name         항목에 적을 이름
	 * @param items        읽은 항목을 적을 곳
	 * @return 숫자가 든 줄이 하나도 없으면 비어 있음
	 */
	Optional<BigDecimal> sumOfRows(Predicate<String> labelMatches, String name, Map<String, String> items) {
		Predicate<List<String>> matches = row -> row.size() >= 2 && labelMatches.test(Cells.label(row.getFirst()));
		Optional<Found> last = lastRow(matches);
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
				int at = row.size() - 2;
				while (at > 0 && row.get(at).equals(row.getLast())) {
					at--;
				}
				items.put(name + ": " + row.get(at), row.getLast());
			}
		}
		return Optional.ofNullable(total);
	}

	/**
	 * 줄의 어느 칸이든 항목 이름이 이것인 칸의 값: 그 칸 뒤에 처음 나오는, 이름과 다른 칸. 문서에서 마지막에 나온 줄을 읽는다.
	 * "2. 계약내역 | 매출액대비(%) | 18.23"처럼 묶음 이름 뒤에 항목 이름이 오는 줄에 쓴다.
	 *
	 * @param label {@link Cells#label}로 다듬은 항목 이름
	 * @return 그 칸이 없으면 비어 있음. 칸은 있는데 뒤에 값 칸이 없으면 빈 글
	 */
	Optional<String> valueAfterLabel(String label) {
		Optional<Found> found = lastRow(row -> labelColumn(row, label) >= 0);
		return found.map(f -> f.valueAfter(labelColumn(f.row(), label)));
	}

	/** 줄에서 항목 이름이 이것인 첫 칸의 자리. 없으면 -1. */
	private static int labelColumn(List<String> row, String label) {
		for (int i = 0; i < row.size(); i++) {
			if (Cells.label(row.get(i)).equals(label)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * 찾은 줄.
	 *
	 * @param table 그 줄이 든 표
	 * @param index 표 안에서 몇 번째 줄인지
	 */
	record Found(TableGrid table, int index) {

		/** 찾은 줄의 칸들. */
		List<String> row() {
			return table.rows().get(index);
		}

		/** 첫 칸(항목 이름) 뒤에 처음 나오는, 첫 칸과 다른 칸. 없으면 빈 글. */
		String firstValue() {
			return valueAfter(0);
		}

		/** 이름 칸 뒤에 처음 나오는, 이름 칸과 다른 칸. 여러 칸에 걸친 이름은 같은 글이 이어지므로 건너뛴다. */
		String valueAfter(int labelColumn) {
			List<String> row = row();
			String label = TableGrid.at(row, labelColumn);
			for (int i = labelColumn + 1; i < row.size(); i++) {
				if (!row.get(i).equals(label)) {
					return row.get(i);
				}
			}
			return "";
		}
	}
}
