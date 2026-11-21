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
 * 채무보증·담보 제공(H1), 금전 대여(H2), 차입(H3)의 표를 읽는다.
 *
 * <p>방향과 크기는 금액이 자기자본의 몇 %인지("자기자본대비(%)")로 정한다. 비율이 작으면 자회사·계열사에 대한 일상적인 보증·대여나
 * 운영자금 차입으로 보고 중립이다. 보증·대여는 일정 비율부터 악재 1, 더 크면 악재 2이고, 차입은 자기 사업에 쓰는 돈이라 더 높은 비율부터 악재 1이다.
 * 상대방이 자회사인지는 가리지 않는다(회사가 글로 적은 칸이라 코드로 나누기 어렵다).
 *
 * <p>비율 칸을 읽지 못하면 방향을 추측하지 않고 AI가 읽게 한다.
 */
@Component
class GuaranteeReader implements TableReader {

	private static final String EQUITY_RATIO = "자기자본대비(%)";

	/**
	 * 요약에 쓸 항목 이름. 서식마다 있는 것만 읽힌다. 채무자 이름은 넣지 않는다(본문 아래 잔액 표의 머리 줄도 "채무자"로 시작한다)
	 */
	private static final List<String> ITEMS = List.of("대여상대", "회사와의관계", "채권자", "채무(차입)금액(원)", "채무보증금액(원)",
			"담보설정금액(원)", "담보제공재산", "대여금액(원)", "이율(%)", "금전대여목적", "차입금액(원)", "차입목적", "자기자본(원)",
			"채무보증총잔액(원)", "담보제공총잔액(원)", "금전대여총잔액(원)");

	private final ImpactProperties.Guarantee guarantee;
	private final ImpactProperties.Borrowing borrowing;

	GuaranteeReader(ImpactProperties properties) {
		this.guarantee = properties.guarantee();
		this.borrowing = properties.borrowing();
	}

	@Override
	public Set<String> typeCodes() {
		return Set.of("H1", "H2", "H3");
	}

	@Override
	public TableReading read(ReadTarget target, BodyTables tables) {
		Map<String, String> items = new LinkedHashMap<>();
		List<String> unread = new ArrayList<>();
		for (String label : ITEMS) {
			tables.valueAfterLabel(label).filter(value -> !Cells.isBlank(value)).ifPresent(value -> items.put(label, value));
		}
		Optional<String> cell = tables.valueAfterLabel(EQUITY_RATIO);
		cell.ifPresent(value -> items.put(EQUITY_RATIO, value));
		BigDecimal ratio = cell.flatMap(Cells::number).filter(number -> number.signum() >= 0).orElse(null);
		if (ratio == null) {
			unread.add(EQUITY_RATIO);
			return TableReading.unreadable(cell.isEmpty() ? "NO_RATIO" : "UNKNOWN_RATIO", items, unread);
		}
		int size = "H3".equals(target.typeCode()) ? borrowing.sizeOf(ratio) : guarantee.sizeOf(ratio);
		if (size == 0) {
			return TableReading.neutral("BELOW_THRESHOLD", ratio, items, unread);
		}
		return TableReading.judged(Tone.NEGATIVE, size, "LARGE_AGAINST_EQUITY", ratio, items, unread);
	}
}
