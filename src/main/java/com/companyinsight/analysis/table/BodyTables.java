package com.companyinsight.analysis.table;

import com.companyinsight.analysis.body.BodyDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 공시 한 건의 본문에 든 표들. 정정 공시의 맨 앞에 붙는 정정 표(정정 전·후를 견준 표)는 뺀다.
 * 정정 공시의 본문에는 고친 내용 전체가 다시 들어 있으므로, 정정 표를 빼면 새 공시와 같은 표가 남는다.
 *
 * <p>금융위원회 서식의 정정 공시는 정정 표 뒤에 "정정 전" 표의 사본을 따로 싣기도 한다. 사본은 본문보다 앞에 오므로,
 * 한 번만 나와야 하는 항목은 마지막에 나온 것을 읽는다({@link #lastRow}).
 *
 * @param tables          정정 표를 뺀 표. 문서에 나온 순서대로
 * @param correctionCells 정정 표의 칸에 적힌 글. 정정 공시가 아니면 비어 있다
 */
record BodyTables(List<TableGrid> tables, List<String> correctionCells) {

	/** 정정 표에 이 낱말이 있으면 원래 결정을 거둔 정정일 수 있다 */
	private static final List<String> WITHDRAWAL_WORDS = List.of("철회", "취소", "해제", "취하", "미발생", "미체결");

	BodyTables {
		tables = List.copyOf(tables);
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
		List<String> correctionCells = new ArrayList<>();
		for (BodyDocument document : documents) {
			for (TableGrid table : TableGrid.parse(document)) {
				if (isCorrectionTable(table)) {
					table.rows().forEach(row -> row.forEach(correctionCells::add));
				}
				else {
					tables.add(table);
				}
			}
		}
		return new BodyTables(tables, correctionCells);
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
