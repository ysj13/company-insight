package com.companyinsight.analysis.table;

import com.companyinsight.analysis.body.BodyDocument;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 본문을 고친 정정 공시([기재정정])의 맨 앞에 붙는 정정 내용: 고친 공시의 제출일, 정정 사유, 정정 전·후 항목.
 *
 * <p>서식이 둘이다.
 * <ul>
 * <li>거래소 서식: 정정 표 한 장에 "2. 정정관련 공시서류제출일 | 2025-08-29", "3. 정정사유 | …"가 있고,
 *   "정정항목 | 정정전 | 정정후" 머리 줄 아래에 바뀐 항목이 한 줄씩 온다.
 * <li>금융위원회 서식: "2. 정정대상 공시서류의 최초제출일 : 2025년 06월 23일"이 표 위의 글이나 따로 된 작은 표에 있고,
 *   정정 표는 "항 목 | 정정사유 | 정 정 전 | 정 정 후"처럼 항목마다 사유가 따로 붙는다.
 *   "정정요구ㆍ명령관련 여부" 칸이 있는 서식도 있다.
 * </ul>
 *
 * <p>날짜는 표가 아니라 문서의 글에서 찾는다(금융위원회 서식은 표 밖의 문단에 적기도 한다). 문서에서 처음 나온 것을 읽는다.
 * 거래소 서식의 날짜는 바로 앞 판(원래 공시나 앞선 정정)의 제출일이고, 금융위원회 서식의 날짜는 원래 공시의 최초 제출일이다
 * (2026-11-21 실제 공시 4,441건에서 확인).
 *
 * @param correctedFiledOn 고친 공시의 제출일. 읽지 못했으면 null
 * @param firstFiling     날짜를 금융위원회 서식의 "최초제출일"에서 읽었는지 (거래소 서식의 "정정관련 공시서류제출일"이면 false)
 * @param reasons         정정 사유. 나온 순서대로, 같은 것은 한 번만
 * @param changes         바뀐 항목. 정정 표에 나온 순서대로
 * @param authorityOrdered 정정 표에 "정정요구ㆍ명령관련 여부: 예"인 줄이 있는지 (금융당국이 요구한 정정)
 */
record CorrectionTable(LocalDate correctedFiledOn, boolean firstFiling, List<String> reasons, List<Change> changes, boolean authorityOrdered) {

	/** 고친 공시의 제출일 항목 이름과 날짜. 이름과 날짜 사이에는 공백, 쌍점, 칸 나눔(" | ")만 온다 */
	private static final Pattern FILED_ON = Pattern.compile(
			"(?:(최\\s*초\\s*제\\s*출\\s*일)|정\\s*정\\s*관\\s*련\\s*공\\s*시\\s*서\\s*류\\s*제\\s*출\\s*일)[\\s:：|]{0,12}"
					+ "(\\d{4})\\s*(?:[-./]|년)\\s*(\\d{1,2})\\s*(?:[-./]|월)\\s*(\\d{1,2})");

	/** 칸 하나에 담는 글의 최대 길이. 실행 기록이 커지지 않게 자른다 */
	private static final int CELL_MAX = 200;

	/** 바뀐 항목의 최대 개수 */
	private static final int CHANGES_MAX = 40;

	CorrectionTable {
		reasons = List.copyOf(reasons);
		changes = List.copyOf(changes);
	}

	/**
	 * 정정 공시의 본문에서 정정 내용을 읽는다.
	 *
	 * @param documents 본문 문서들
	 * @param tables    같은 본문의 표 ({@link BodyTables#of})
	 * @return 정정 표가 없고 제출일도 찾지 못하면 비어 있음
	 */
	static Optional<CorrectionTable> read(List<BodyDocument> documents, BodyTables tables) {
		Optional<FiledOn> filedOn = filedOn(documents);
		LocalDate correctedFiledOn = filedOn.map(FiledOn::date).orElse(null);
		if (correctedFiledOn == null && tables.correctionTables().isEmpty()) {
			return Optional.empty();
		}
		List<String> reasons = new ArrayList<>();
		List<Change> changes = new ArrayList<>();
		boolean authorityOrdered = false;
		for (TableGrid table : tables.correctionTables()) {
			authorityOrdered |= readTable(table, reasons, changes);
		}
		return Optional.of(new CorrectionTable(correctedFiledOn, filedOn.map(FiledOn::firstFiling).orElse(false), reasons,
				changes, authorityOrdered));
	}

	/** 문서들의 글에서 처음 나온, 고친 공시의 제출일. 없거나 날짜가 아니면 비어 있음. */
	static Optional<FiledOn> filedOn(List<BodyDocument> documents) {
		for (BodyDocument document : documents) {
			Matcher matcher = FILED_ON.matcher(document.text());
			if (matcher.find()) {
				try {
					return Optional.of(new FiledOn(LocalDate.of(Integer.parseInt(matcher.group(2)),
							Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4))), matcher.group(1) != null));
				}
				catch (DateTimeException e) {
					return Optional.empty();
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * 정정 표에서 읽은 날.
	 *
	 * @param date        날짜
	 * @param firstFiling 금융위원회 서식의 "최초제출일"에서 읽었는지
	 */
	record FiledOn(LocalDate date, boolean firstFiling) {
	}

	/**
	 * 정정 표 한 장을 읽는다. 머리 줄("정정전"·"정정후" 칸이 있는 줄) 위의 "정정사유" 줄은 사유로,
	 * 머리 줄 아래는 바뀐 항목으로 읽는다.
	 *
	 * @return 금융당국이 요구한 정정인 줄이 있었는지
	 */
	private static boolean readTable(TableGrid table, List<String> reasons, List<Change> changes) {
		int header = -1;
		int before = -1;
		int after = -1;
		int reasonColumn = -1;
		int authorityColumn = -1;
		boolean authorityOrdered = false;
		for (int i = 0; i < table.rows().size(); i++) {
			List<String> row = table.rows().get(i);
			if (header < 0) {
				int b = column(row, "정정전");
				int a = column(row, "정정후");
				if (b >= 0 && a >= 0) {
					header = i;
					before = b;
					after = a;
					reasonColumn = column(row, "정정사유");
					authorityColumn = columnContaining(row, "정정요구");
					continue;
				}
				if (!row.isEmpty() && Cells.label(row.getFirst()).equals("정정사유")) {
					addReason(reasons, new BodyTables.Found(table, i).firstValue());
				}
				continue;
			}
			if (row.size() <= Math.max(before, after)) {
				continue;
			}
			String item = Cells.squeeze(row.getFirst());
			if (reasonColumn >= 0 && reasonColumn < row.size()) {
				addReason(reasons, row.get(reasonColumn));
			}
			if (authorityColumn >= 0 && authorityColumn < row.size() && Cells.compact(row.get(authorityColumn)).equals("예")) {
				authorityOrdered = true;
			}
			if (changes.size() < CHANGES_MAX) {
				changes.add(new Change(cut(item), cut(Cells.squeeze(row.get(before))), cut(Cells.squeeze(row.get(after)))));
			}
		}
		return authorityOrdered;
	}

	/** 칸의 글(공백을 없앤 것)이 이것과 같은 첫 칸의 자리. 없으면 -1. */
	private static int column(List<String> row, String compactText) {
		for (int i = 0; i < row.size(); i++) {
			if (Cells.compact(row.get(i)).equals(compactText)) {
				return i;
			}
		}
		return -1;
	}

	/** 칸의 글(공백을 없앤 것)에 이 글이 든 첫 칸의 자리. 없으면 -1. */
	private static int columnContaining(List<String> row, String compactText) {
		for (int i = 0; i < row.size(); i++) {
			if (Cells.compact(row.get(i)).contains(compactText)) {
				return i;
			}
		}
		return -1;
	}

	private static void addReason(List<String> reasons, String reason) {
		String squeezed = cut(Cells.squeeze(reason));
		if (!Cells.isBlank(squeezed) && !reasons.contains(squeezed)) {
			reasons.add(squeezed);
		}
	}

	private static String cut(String text) {
		return text.length() <= CELL_MAX ? text : text.substring(0, CELL_MAX) + "…";
	}

	/** 실행 기록에 남길 모양. */
	Map<String, Object> toResult() {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("correctedFiledOn", correctedFiledOn == null ? null : correctedFiledOn.toString());
		result.put("firstFiling", firstFiling);
		result.put("reasons", reasons);
		result.put("changes", changes.stream().map(c -> List.of(c.item(), c.before(), c.after())).toList());
		if (authorityOrdered) {
			result.put("authorityOrdered", true);
		}
		return result;
	}

	/**
	 * 정정 표의 바뀐 항목 한 줄.
	 *
	 * @param item   항목 이름 (적힌 그대로, 공백만 다듬음)
	 * @param before 정정 전
	 * @param after  정정 후
	 */
	record Change(String item, String before, String after) {
	}
}
