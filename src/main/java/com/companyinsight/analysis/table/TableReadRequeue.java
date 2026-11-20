package com.companyinsight.analysis.table;

import com.companyinsight.analysis.AnalysisQueueService;
import com.companyinsight.analysis.AnalysisStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 읽는 규칙이 없어 판정 없이 끝났던 표 읽기 작업을, 그 유형의 규칙이 생긴 뒤 다시 줄 세운다.
 *
 * <p>규칙({@link TableReader})은 코드로 더하므로 새 규칙은 앱을 새로 띄울 때 생긴다. 그래서 앱이 뜬 직후 한 번,
 * 지금 규칙이 있는 유형 가운데 "규칙 없음"으로 끝나 있는 작업을 찾아 대기로 되돌린다.
 * 되돌릴 것이 없으면 아무 일도 하지 않으므로 띄울 때마다 실행해도 된다. 실패해도 앱은 뜨고 다음에 띄울 때 다시 시도한다.
 */
@Component
class TableReadRequeue {

	private static final Logger log = LoggerFactory.getLogger(TableReadRequeue.class);

	private final TableReadHandler handler;
	private final AnalysisQueueService queue;

	TableReadRequeue(TableReadHandler handler, AnalysisQueueService queue) {
		this.handler = handler;
		this.queue = queue;
	}

	/** 앱이 뜬 직후, 규칙이 생긴 유형의 건너뛴 작업을 다시 줄 세운다. */
	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		try {
			queue.requeueSkipped(AnalysisStage.READ_TABLE, TableReadHandler.NO_READER, handler.readableTypeCodes());
		}
		catch (RuntimeException e) {
			// 다시 줄 세우지 못해도 앱은 떠야 한다. 다음에 띄울 때 다시 시도한다
			log.warn("건너뛰었던 표 읽기 작업을 다시 줄 세우지 못했습니다", e);
		}
	}
}
