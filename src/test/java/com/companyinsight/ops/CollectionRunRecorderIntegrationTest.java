package com.companyinsight.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.companyinsight.TestcontainersConfiguration;
import com.companyinsight.common.external.ErrorKind;
import com.companyinsight.common.external.ExternalApiException;
import com.companyinsight.common.external.SourcePauseRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CollectionRunRecorderIntegrationTest {

	private static final String JOB = "TEST_JOB";

	@Autowired
	CollectionRunRecorder recorder;

	@Autowired
	JdbcClient jdbc;

	@MockitoBean
	SourcePauseRegistry pauses;

	@BeforeEach
	void clean() {
		jdbc.sql("DELETE FROM collection_run").update();
	}

	@Test
	void 성공하면_수치와_함께_SUCCESS로_남기고_결과를_돌려준다() {
		String result = recorder.record(JOB, progress -> {
			progress.addFetched(100);
			progress.addSaved(70);
			progress.addFetched(30);
			progress.addSaved(5);
			return "done";
		});

		assertThat(result).isEqualTo("done");
		assertThat(lastRun())
			.containsEntry("status", "SUCCESS")
			.containsEntry("fetched", 130)
			.containsEntry("saved", 75)
			.containsEntry("pages", 2)
			.containsEntry("error_kind", null);
		assertThat(lastRun().get("finished_at")).isNotNull();
	}

	@Test
	void 저장_중_내부_오류가_나면_받은_건수까지_남기고_INTERNAL로_기록한다() {
		assertThatThrownBy(() -> recorder.record(JOB, progress -> {
			progress.addFetched(100);
			progress.addSaved(100);
			progress.addFetched(40); // 받았지만 저장하다 실패
			throw new IllegalStateException("db down");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(lastRun())
			.containsEntry("status", "FAILED")
			.containsEntry("fetched", 140)
			.containsEntry("saved", 100)
			.containsEntry("pages", 2)
			.containsEntry("error_kind", "INTERNAL")
			.containsEntry("error_code", null);
		assertThat((String) lastRun().get("error")).contains("db down");
		verify(pauses, never()).pauseFor(any());
	}

	@Test
	void 외부_오류는_분류와_코드를_남기고_소스_정지를_요청한다() {
		ExternalApiException auth = ExternalApiException.of("DART", ErrorKind.AUTH, "010", "미등록 키");

		assertThatThrownBy(() -> recorder.record(JOB, progress -> {
			throw auth;
		})).isSameAs(auth);

		assertThat(lastRun())
			.containsEntry("status", "FAILED")
			.containsEntry("error_kind", "AUTH")
			.containsEntry("error_code", "010");
		verify(pauses).pauseFor(auth);
	}

	private Map<String, Object> lastRun() {
		return jdbc.sql("SELECT status, fetched, saved, pages, error_kind, error_code, error, finished_at FROM collection_run")
			.query().singleRow();
	}
}
