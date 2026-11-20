package com.companyinsight.analysis.table;

import com.companyinsight.analysis.body.BodyDocument;
import com.companyinsight.analysis.body.BodyFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/**
 * 본문의 표 하나를 가로·세로 자리가 맞는 격자로 펼친 것. 여러 칸에 걸친 칸(colspan, rowspan)은 걸친 자리마다 같은 글이 들어간다.
 * 그래서 "머리 줄에서 이 이름이 있는 열"과 "이 줄의 그 열"을 자리로 맞춰 읽을 수 있다.
 *
 * @param rows 줄마다 칸의 글. 칸의 글은 앞뒤 공백을 떼고 이어진 공백을 하나로 줄였다
 */
record TableGrid(List<List<String>> rows) {

	/** 칸으로 읽는 요소. 전용 XML에는 TU, TE가 더 있다 */
	private static final Set<String> CELL_TAGS = Set.of("td", "th", "tu", "te");

	/** 한 칸이 걸칠 수 있는 자리 수의 상한. 잘못 적힌 큰 값이 격자를 터뜨리지 않게 한다 */
	private static final int MAX_SPAN = 200;

	TableGrid {
		rows = rows.stream().map(List::copyOf).toList();
	}

	/**
	 * 문서의 표를 나온 순서대로 읽는다. 표 안에 든 표는 따로 한 표로 읽고, 바깥 표의 줄에는 넣지 않는다.
	 */
	static List<TableGrid> parse(BodyDocument document) {
		// 전용 XML을 HTML로 읽으면 TU·TE 칸이 표 밖으로 밀린다
		Document parsed = document.format() == BodyFormat.XML ? Jsoup.parse(document.markup(), "", Parser.xmlParser())
				: Jsoup.parse(document.markup());
		List<TableGrid> tables = new ArrayList<>();
		for (Element table : parsed.getAllElements()) {
			if (!"table".equals(name(table))) {
				continue;
			}
			List<Element> rows = new ArrayList<>();
			for (Element row : table.getAllElements()) {
				if ("tr".equals(name(row)) && nearestTable(row) == table) {
					rows.add(row);
				}
			}
			tables.add(grid(rows));
		}
		return tables;
	}

	/** 표의 줄들을 격자로 펼친다. */
	private static TableGrid grid(List<Element> rowElements) {
		List<List<String>> grid = new ArrayList<>();
		// 위 줄에서 아래로 걸쳐 내려오는 칸: 열 자리 → (남은 줄 수, 글)
		Map<Integer, Carry> carries = new HashMap<>();
		for (Element rowElement : rowElements) {
			List<String> row = new ArrayList<>();
			int column = 0;
			for (Element cell : rowElement.children()) {
				if (!CELL_TAGS.contains(name(cell))) {
					continue;
				}
				column = fillCarried(row, carries, column);
				String text = Cells.squeeze(cell.text());
				int colspan = span(cell, "colspan");
				int rowspan = span(cell, "rowspan");
				for (int i = 0; i < colspan; i++) {
					row.add(text);
					if (rowspan > 1) {
						carries.put(column, new Carry(rowspan - 1, text));
					}
					column++;
				}
			}
			// 줄 끝에 남은, 위에서 내려오는 칸
			while (carries.containsKey(column)) {
				column = fillCarried(row, carries, column);
			}
			grid.add(row);
		}
		return new TableGrid(grid);
	}

	/** 지금 자리에 위에서 내려오는 칸이 있으면 이어지는 만큼 채우고 다음 빈 자리를 돌려준다. */
	private static int fillCarried(List<String> row, Map<Integer, Carry> carries, int column) {
		while (carries.containsKey(column)) {
			Carry carry = carries.get(column);
			row.add(carry.text());
			if (carry.rowsLeft() <= 1) {
				carries.remove(column);
			}
			else {
				carries.put(column, new Carry(carry.rowsLeft() - 1, carry.text()));
			}
			column++;
		}
		return column;
	}

	private static int span(Element cell, String attribute) {
		// HTML 파서는 속성 이름을 소문자로 바꾸고, XML 파서는 적힌 그대로 둔다
		String value = cell.attr(attribute);
		if (value.isEmpty()) {
			value = cell.attr(attribute.toUpperCase(Locale.ROOT));
		}
		try {
			return Math.clamp(Integer.parseInt(value.strip()), 1, MAX_SPAN);
		}
		catch (NumberFormatException e) {
			return 1;
		}
	}

	private static Element nearestTable(Element element) {
		for (Element parent = element.parent(); parent != null; parent = parent.parent()) {
			if ("table".equals(name(parent))) {
				return parent;
			}
		}
		return null;
	}

	private static String name(Element element) {
		return element.tagName().toLowerCase(Locale.ROOT);
	}

	/** 줄의 한 자리에 있는 글. 그 자리가 없으면 빈 글. */
	static String at(List<String> row, int column) {
		return column >= 0 && column < row.size() ? row.get(column) : "";
	}

	/** 위 줄에서 아래로 걸쳐 내려오는 칸. */
	private record Carry(int rowsLeft, String text) {
	}
}
