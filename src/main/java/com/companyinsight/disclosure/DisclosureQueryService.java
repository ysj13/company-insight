package com.companyinsight.disclosure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공시 목록 조회. */
@Service
@Transactional(readOnly = true)
public class DisclosureQueryService {

	/** 한 페이지에 보여주는 공시 수 */
	static final int PAGE_SIZE = 50;

	private final DisclosureQueryRepository repository;

	public DisclosureQueryService(DisclosureQueryRepository repository) {
		this.repository = repository;
	}

	/**
	 * 조건에 맞는 공시를 최근 접수 순으로 한 페이지 가져온다.
	 *
	 * @param page 페이지 번호(1부터). 범위를 벗어나면 첫 페이지 또는 마지막 페이지로 맞춘다
	 */
	public DisclosurePage search(DisclosureSearchCondition condition, int page) {
		Map<ToneFilter, Long> toneCounts = repository.countByTone(condition);
		long total = toneCounts.values().stream().mapToLong(Long::longValue).sum();
		long matched = condition.tone() == null ? total : toneCounts.get(condition.tone());

		int totalPages = (int) Math.max(1, (matched + PAGE_SIZE - 1) / PAGE_SIZE);
		int current = Math.clamp(page, 1, totalPages);
		List<DisclosureSummary> items = repository.find(condition, (long) (current - 1) * PAGE_SIZE, PAGE_SIZE);
		return new DisclosurePage(groupByDay(items), toneCounts, total, matched, current, totalPages);
	}

	/** 접수일 순으로 정렬된 목록을 날짜별로 묶는다. */
	private static List<DisclosurePage.Day> groupByDay(List<DisclosureSummary> items) {
		List<DisclosurePage.Day> days = new ArrayList<>();
		List<DisclosureSummary> sameDay = new ArrayList<>();
		for (DisclosureSummary item : items) {
			if (!sameDay.isEmpty() && !sameDay.getFirst().receiptDate().equals(item.receiptDate())) {
				days.add(new DisclosurePage.Day(sameDay.getFirst().receiptDate(), List.copyOf(sameDay)));
				sameDay.clear();
			}
			sameDay.add(item);
		}
		if (!sameDay.isEmpty()) {
			days.add(new DisclosurePage.Day(sameDay.getFirst().receiptDate(), List.copyOf(sameDay)));
		}
		return days;
	}
}
