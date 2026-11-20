package com.companyinsight.disclosure;

import com.companyinsight.analysis.AnalysisQueueService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 이미 저장한 공시를 지금의 대응표로 다시 분류한다.
 *
 * <p>새 공시는 저장할 때 분류된다. 이 서비스는 대응표가 생기기 전에 저장한 공시와,
 * 대응표를 고친 뒤(마이그레이션이 버전을 올린 뒤) 옛 버전으로 남아 있는 공시를 맞춘다.
 */
@Service
public class DisclosureClassificationService {

	private static final Logger log = LoggerFactory.getLogger(DisclosureClassificationService.class);

	/** 한 번에 읽고 쓰는 건수. 한 트랜잭션이 오래 잠그지 않게 나눈다. */
	private static final int BATCH_SIZE = 1000;

	private final DisclosureRepository repository;
	private final DisclosureClassifier classifier;
	private final AnalysisQueueService analysisQueue;
	private final TransactionTemplate transaction;

	public DisclosureClassificationService(DisclosureRepository repository, DisclosureClassifier classifier,
			AnalysisQueueService analysisQueue, TransactionTemplate transaction) {
		this.repository = repository;
		this.classifier = classifier;
		this.analysisQueue = analysisQueue;
		this.transaction = transaction;
	}

	/**
	 * 옛 대응표 버전으로 남아 있는 공시를 모두 다시 분류한다. LLM이 판정한 공시는 유형만 맞추고 판정은 그대로 둔다.
	 * 중간에 멈춰도 다시 실행하면 남은 것부터 이어서 한다.
	 *
	 * <p>제목만 보므로 전부 다시 해도 가볍다. 다만 유형·방향·경우가 실제로 바뀐 공시만 뒤 단계(본문 표 읽기, 판정, 요약)를
	 * 다시 지나야 하므로, 그 공시만 분석 작업 큐로 다시 보낸다. 분류를 고치는 것과 큐로 보내는 것은 묶음마다
	 * 한 트랜잭션이라 한쪽만 반영되지 않는다.
	 *
	 * @return 다시 분류한 건수
	 */
	public int reclassifyOutdated() {
		int version = classifier.version();
		int total = 0;
		int changed = 0;
		int requeued = 0;
		long afterId = 0;
		while (true) {
			List<DisclosureRepository.Outdated> rows = repository.findOutdated(version, afterId, BATCH_SIZE);
			if (rows.isEmpty()) {
				break;
			}
			Map<Long, Classification> byId = new LinkedHashMap<>();
			List<Long> changedIds = new ArrayList<>();
			for (DisclosureRepository.Outdated row : rows) {
				Classification c = classifier.classify(row.reportName(), row.remark());
				byId.put(row.id(), c);
				if (row.differsFrom(c)) {
					changedIds.add(row.id());
				}
			}
			requeued += transaction.execute(status -> {
				repository.updateClassifications(byId, version);
				return analysisQueue.requeueReclassified(changedIds);
			});
			changed += changedIds.size();
			total += rows.size();
			afterId = rows.getLast().id();
		}
		if (total > 0) {
			log.info("공시 재분류: 대응표 버전 {}로 {}건, 그중 유형·방향·경우가 바뀐 공시 {}건, 표 읽기부터 다시 지나는 공시 {}건", version, total,
				changed, requeued);
		}
		return total;
	}
}
