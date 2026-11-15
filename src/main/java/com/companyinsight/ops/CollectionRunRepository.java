package com.companyinsight.ops;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 수집 실행 기록(collection_run). */
@Repository
public class CollectionRunRepository {

	private static final int ERROR_MAX = 500;

	private final JdbcClient jdbc;

	public CollectionRunRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public long start(String source, Instant startedAt) {
		return jdbc.sql("""
				INSERT INTO collection_run (source, started_at, status) VALUES (:source, :startedAt, 'RUNNING')
				RETURNING id
				""")
			.param("source", source)
			.param("startedAt", OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC))
			.query(Long.class)
			.single();
	}

	/** 이 작업의 가장 최근 실행이 성공으로 끝났는지. 기록이 없으면 true. RUNNING(중단됨)과 FAILED는 false. */
	public boolean lastRunSucceeded(String source) {
		return jdbc.sql("SELECT status FROM collection_run WHERE source = :source ORDER BY started_at DESC, id DESC LIMIT 1")
			.param("source", source)
			.query(String.class)
			.optional()
			.map("SUCCESS"::equals)
			.orElse(true);
	}

	public void finishSuccess(long id, Instant finishedAt, int fetched, int saved, int pages) {
		finish(id, finishedAt, "SUCCESS", fetched, saved, pages, null, null, null);
	}

	/**
	 * @param errorKind 외부 API 오류면 분류(ErrorKind 이름), 아니면 "INTERNAL"
	 * @param errorCode 소스의 원래 코드 (예: DART "020")
	 */
	public void finishFailed(long id, Instant finishedAt, int fetched, int saved, int pages, String errorKind,
			String errorCode, String error) {
		finish(id, finishedAt, "FAILED", fetched, saved, pages, errorKind, errorCode, error);
	}

	private void finish(long id, Instant finishedAt, String status, int fetched, int saved, int pages,
			String errorKind, String errorCode, String error) {
		jdbc.sql("""
				UPDATE collection_run
				   SET finished_at = :finishedAt, status = :status, fetched = :fetched, saved = :saved,
				       pages = :pages, error_kind = :errorKind, error_code = :errorCode, error = :error
				 WHERE id = :id
				""")
			.param("finishedAt", OffsetDateTime.ofInstant(finishedAt, ZoneOffset.UTC))
			.param("status", status)
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
