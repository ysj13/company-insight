package com.companyinsight.ops;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 수집 실행 기록(collection_run) 저장과 조회.
 *
 * <p>수집 작업은 이 클래스를 직접 쓰지 않고 {@link CollectionRunRecorder}를 통해 기록한다.
 * 조회 메서드는 "직전 실행이 성공했는가" 같은 판단에 쓴다.
 */
@Repository
public class CollectionRunRepository {

	/** error 컬럼 길이. */
	private static final int ERROR_MAX = 500;

	private final JdbcClient jdbc;

	public CollectionRunRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 실행 시작을 기록한다. 상태는 RUNNING.
	 *
	 * @param source 작업 이름
	 * @return 실행 ID. 종료 기록에 쓴다.
	 */
	public long start(String source, Instant startedAt) {
		return jdbc.sql("""
				INSERT INTO collection_run (source, started_at, status) VALUES (:source, :startedAt, :status)
				RETURNING id
				""")
			.param("source", source)
			.param("startedAt", OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC))
			.param("status", RunStatus.RUNNING.name())
			.query(Long.class)
			.single();
	}

	/**
	 * 이 작업의 가장 최근 실행이 성공으로 끝났는지.
	 *
	 * @return 기록이 없으면 true. 최근 실행이 RUNNING(중단됨)이거나 FAILED면 false.
	 */
	public boolean lastRunSucceeded(String source) {
		return jdbc.sql("SELECT status FROM collection_run WHERE source = :source ORDER BY started_at DESC, id DESC LIMIT 1")
			.param("source", source)
			.query(String.class)
			.optional()
			.map(RunStatus.SUCCESS.name()::equals)
			.orElse(true);
	}

	/** 이 작업이 한 번이라도 성공한 적이 있는지. */
	public boolean hasSucceeded(String source) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM collection_run WHERE source = :source AND status = :status)")
			.param("source", source)
			.param("status", RunStatus.SUCCESS.name())
			.query(Boolean.class)
			.single();
	}

	/** 정상 종료를 기록한다. */
	public void finishSuccess(long id, Instant finishedAt, int fetched, int saved, int pages) {
		finish(id, finishedAt, RunStatus.SUCCESS, fetched, saved, pages, null, null, null);
	}

	/**
	 * 실패 종료를 기록한다.
	 *
	 * @param errorKind 외부 API 오류면 분류(ErrorKind 이름), 아니면 "INTERNAL"
	 * @param errorCode 소스의 원래 코드 (예: DART "020"). 없으면 null.
	 * @param error     오류 메시지. 500자까지만 저장한다.
	 */
	public void finishFailed(long id, Instant finishedAt, int fetched, int saved, int pages, String errorKind,
			String errorCode, String error) {
		finish(id, finishedAt, RunStatus.FAILED, fetched, saved, pages, errorKind, errorCode, error);
	}

	private void finish(long id, Instant finishedAt, RunStatus status, int fetched, int saved, int pages,
			String errorKind, String errorCode, String error) {
		jdbc.sql("""
				UPDATE collection_run
				   SET finished_at = :finishedAt, status = :status, fetched = :fetched, saved = :saved,
					   pages = :pages, error_kind = :errorKind, error_code = :errorCode, error = :error
				 WHERE id = :id
				""")
			.param("finishedAt", OffsetDateTime.ofInstant(finishedAt, ZoneOffset.UTC))
			.param("status", status.name())
			.param("fetched", fetched)
			.param("saved", saved)
			.param("pages", pages)
			.param("errorKind", errorKind)
			.param("errorCode", errorCode)
			.param("error", error == null ? null : error.substring(0, Math.min(error.length(), ERROR_MAX)))
			.param("id", id)
			.update();
	}
}
