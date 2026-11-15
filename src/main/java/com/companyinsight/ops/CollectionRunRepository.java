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

	public void finish(long id, Instant finishedAt, String status, int fetched, int saved, int pages, String error) {
		jdbc.sql("""
				UPDATE collection_run
				   SET finished_at = :finishedAt, status = :status, fetched = :fetched, saved = :saved,
				       pages = :pages, error = :error
				 WHERE id = :id
				""")
			.param("finishedAt", OffsetDateTime.ofInstant(finishedAt, ZoneOffset.UTC))
			.param("status", status)
			.param("fetched", fetched)
			.param("saved", saved)
			.param("pages", pages)
			.param("error", error == null ? null : error.substring(0, Math.min(error.length(), ERROR_MAX)))
			.param("id", id)
			.update();
	}
}
