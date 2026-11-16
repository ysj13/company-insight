package com.companyinsight.disclosure;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 공시 목록 한 페이지.
 *
 * @param days       이 페이지의 공시를 접수일별로 묶은 것. 최근 날짜가 먼저
 * @param toneCounts 판정별 건수. 종류·회사 조건만 적용한 값이다
 * @param total      종류·회사 조건에 맞는 전체 건수 (판정 조건 적용 전)
 * @param matched    판정 조건까지 적용한 건수. 페이지 수의 기준
 * @param page       지금 페이지 (1부터)
 * @param totalPages 전체 페이지 수. 공시가 없어도 1
 */
public record DisclosurePage(List<Day> days, Map<ToneFilter, Long> toneCounts, long total, long matched, int page,
		int totalPages) {

	/** 종류·회사 조건에 맞는 공시 중 이 판정인 것의 건수. */
	public long countOf(ToneFilter tone) {
		return toneCounts.getOrDefault(tone, 0L);
	}

	/** 이전 페이지가 있는지. */
	public boolean hasPrevious() {
		return page > 1;
	}

	/** 다음 페이지가 있는지. */
	public boolean hasNext() {
		return page < totalPages;
	}

	/**
	 * 같은 날 접수된 공시 묶음.
	 *
	 * @param date  접수일
	 * @param items 그날의 공시. 나중에 접수된 것이 먼저
	 */
	public record Day(LocalDate date, List<DisclosureSummary> items) {

		private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("yyyy년 M월 d일 (E)", Locale.KOREAN);

		/** 화면에 보여주는 날짜. 예: 2026년 11월 16일 (월) */
		public String label() {
			return date.format(LABEL);
		}
	}
}
