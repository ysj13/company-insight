package com.companyinsight.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.companyinsight.TestcontainersConfiguration;
import com.companyinsight.common.dart.DartClient;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CompanyIntegrationTest {

	@Autowired
	CompanySyncService syncService;

	@Autowired
	CompanyRepository repository;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MockMvc mvc;

	@MockitoBean
	DartClient dartClient;

	@BeforeEach
	void clean() {
		jdbc.sql("DELETE FROM disclosure").update();
		jdbc.sql("DELETE FROM company").update();
		given(dartClient.isConfigured()).willReturn(true);
	}

	@Test
	void 동기화하면_상장사가_저장되고_다시_돌려도_변경이_없다() {
		given(dartClient.downloadCorpCodeZip()).willReturn(CorpCodeZipFixture.zip());

		CompanySyncService.SyncResult first = syncService.sync();
		CompanySyncService.SyncResult second = syncService.sync();

		assertThat(first.listed()).isEqualTo(3);
		assertThat(first.changed()).isEqualTo(3);
		assertThat(second.changed()).isZero();
		assertThat(repository.count()).isEqualTo(3);
	}

	@Test
	void 이름이_바뀐_회사만_갱신된다() {
		repository.upsertAll(List.of(new CorpCode("00126380", "005930", "삼성전자", LocalDate.of(2025, 6, 30))));

		int changed = repository.upsertAll(List.of(
			new CorpCode("00126380", "005930", "삼성전자(주)", LocalDate.of(2025, 7, 1)),
			new CorpCode("00164779", "000660", "SK하이닉스", null)));

		assertThat(changed).isEqualTo(2);
		assertThat(repository.search("삼성전자(주)")).extracting(CompanySummary::name).containsExactly("삼성전자(주)");
	}

	@Test
	void 키가_없으면_건너뛴다() {
		given(dartClient.isConfigured()).willReturn(false);

		assertThat(syncService.sync().skipped()).isTrue();
		assertThat(repository.count()).isZero();
	}

	@Test
	void 검색은_정확히_일치_앞부분_포함_순() throws Exception {
		repository.upsertAll(List.of(
			new CorpCode("1", "000001", "삼성SDI", null),
			new CorpCode("2", "000002", "삼성", null),
			new CorpCode("3", "000003", "신세계삼성", null),
			new CorpCode("4", "005930", "삼성전자", null)));

		mvc.perform(get("/api/companies").param("q", "삼성"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].name").value(org.hamcrest.Matchers.contains("삼성", "삼성전자", "삼성SDI", "신세계삼성")));
	}

	@Test
	void 종목코드_앞부분으로_찾는다() throws Exception {
		repository.upsertAll(List.of(new CorpCode("4", "005930", "삼성전자", null)));

		mvc.perform(get("/api/companies").param("q", "0059"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].stockCode").value("005930"));
	}

	@Test
	void LIKE_특수문자는_글자_그대로_찾는다() {
		repository.upsertAll(List.of(new CorpCode("5", "000005", "에이비씨", null)));

		assertThat(repository.search("%")).isEmpty();
		assertThat(repository.search("_")).isEmpty();
	}

	@Test
	void 검색어가_비면_400_Problem_Details() throws Exception {
		mvc.perform(get("/api/companies").param("q", " "))
			.andExpect(status().isBadRequest())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
				.contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(400));
	}
}
