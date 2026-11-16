package com.companyinsight.ops;

import com.companyinsight.common.external.ExternalApiException;
import com.companyinsight.common.external.SourcePauseRegistry;
import java.time.Clock;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * 수집 작업을 실행하면서 결과를 collection_run에 남긴다.
 *
 * <p>모든 수집 작업이 같은 틀(시작 기록 → 실행 → 성공 또는 실패 기록)을 쓰도록 한곳에 모았다.
 * 실패했을 때 오류 분류를 남기고 해당 외부 소스를 정지시키는 처리도 여기서 한다.
 * 수집기마다 따로 구현하면 한쪽에서 기록이나 정지 처리가 빠지기 쉽다.
 */
@Component
public class CollectionRunRecorder {

	/** 외부 API 오류가 아닌 우리 코드·DB 오류의 분류 이름. */
	static final String INTERNAL = "INTERNAL";

	private final CollectionRunRepository runs;
	private final SourcePauseRegistry pauses;
	private final Clock clock;

	public CollectionRunRecorder(CollectionRunRepository runs, SourcePauseRegistry pauses, Clock clock) {
		this.runs = runs;
		this.pauses = pauses;
		this.clock = clock;
	}

	/**
	 * 작업을 실행하고 결과를 기록한다.
	 *
	 * <ul>
	 * <li>정상 종료: SUCCESS와 진행 수치를 남긴다.</li>
	 * <li>외부 API 오류: 오류 분류와 원래 코드를 남기고, 분류에 따라 소스를 정지시킨 뒤 예외를 다시 던진다.</li>
	 * <li>그 밖의 오류: INTERNAL로 남기고 예외를 다시 던진다.</li>
	 * </ul>
	 * 실패해도 그때까지의 진행 수치(받은 건수 등)는 함께 남는다.
	 *
	 * @param job  작업 이름 (collection_run.source)
	 * @param body 실제 작업. 진행 상황을 {@link Progress}에 누적한다.
	 * @return 작업이 돌려준 값
	 */
	public <T> T record(String job, Function<Progress, T> body) {
		long runId = runs.start(job, clock.instant());
		Progress progress = new Progress();
		try {
			T result = body.apply(progress);
			runs.finishSuccess(runId, clock.instant(), progress.fetched, progress.saved, progress.pages);
			return result;
		}
		catch (ExternalApiException e) {
			pauses.pauseFor(e);
			runs.finishFailed(runId, clock.instant(), progress.fetched, progress.saved, progress.pages,
				e.kind().name(), e.code(), e.getMessage());
			throw e;
		}
		catch (RuntimeException e) {
			runs.finishFailed(runId, clock.instant(), progress.fetched, progress.saved, progress.pages, INTERNAL, null,
				e.toString());
			throw e;
		}
	}

	/** 실행 중 누적하는 수치. 작업 하나가 한 스레드에서만 쓴다. */
	public static final class Progress {

		private int fetched;
		private int saved;
		private int pages;

		/**
		 * 한 페이지(또는 한 번의 외부 호출) 처리 결과를 더한다.
		 *
		 * @param fetched 외부에서 받은 건수
		 * @param saved   새로 저장하거나 변경한 건수
		 */
		public void addPage(int fetched, int saved) {
			this.pages++;
			this.fetched += fetched;
			this.saved += saved;
		}

		public int fetched() {
			return fetched;
		}

		public int saved() {
			return saved;
		}

		public int pages() {
			return pages;
		}
	}
}
