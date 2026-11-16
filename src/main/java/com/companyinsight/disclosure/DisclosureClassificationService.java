package com.companyinsight.disclosure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 이미 저장한 공시를 지금의 제목 규칙으로 다시 분류한다.
 *
 * <p>새 공시는 저장할 때 분류된다. 이 서비스는 규칙이 생기기 전에 저장한 공시와,
 * 규칙을 바꾼 뒤({@link DisclosureClassifier#RULE_VERSION}을 올린 뒤) 옛 규칙으로 남아 있는 공시를 맞춘다.
 */
@Service
public class DisclosureClassificationService {

	private static final Logger log = LoggerFactory.getLogger(DisclosureClassificationService.class);

	/** 한 번에 읽고 쓰는 건수. 한 트랜잭션이 오래 잠그지 않게 나눈다. */
	private static final int BATCH_SIZE = 1000;

	private final DisclosureRepository repository;
	private final DisclosureClassifier classifier;

	public DisclosureClassificationService(DisclosureRepository repository, DisclosureClassifier classifier) {
		this.repository = repository;
		this.classifier = classifier;
	}

	/**
	 * 옛 규칙 버전으로 남아 있는 공시를 모두 다시 분류한다. LLM이 판정한 공시는 그대로 둔다.
	 * 중간에 멈춰도 다시 실행하면 남은 것부터 이어서 한다.
	 *
	 * @return 다시 분류한 건수
	 */
	public int reclassifyOutdated() {
		int total = 0;
		long afterId = 0;
		while (true) {
			List<DisclosureRepository.Outdated> rows = repository.findOutdated(DisclosureClassifier.RULE_VERSION,
				afterId, BATCH_SIZE);
			if (rows.isEmpty()) {
				break;
			}
			Map<Long, Classification> byId = new LinkedHashMap<>();
			for (DisclosureRepository.Outdated row : rows) {
				byId.put(row.id(), classifier.classify(row.reportName(), row.remark()));
			}
			repository.updateClassifications(byId, DisclosureClassifier.RULE_VERSION);
			total += rows.size();
			afterId = rows.getLast().id();
		}
		if (total > 0) {
			log.info("공시 재분류: 규칙 버전 {}로 {}건", DisclosureClassifier.RULE_VERSION, total);
		}
		return total;
	}
}
