package com.companyinsight.common.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.companyinsight.TestcontainersConfiguration;
import com.companyinsight.common.dart.DartClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API 문서를 켰을 때 제목과, Javadoc에서 가져온 설명이 문서에 들어가는지 확인한다.
 * 설명은 빌드할 때 담기므로, 빌드 설정이 빠지면 이 테스트가 실패한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true" })
@AutoConfigureMockMvc
class ApiDocsIntegrationTest {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	DartClient dart;

	@Test
	void 문서에_제목과_Javadoc_설명과_오류_응답이_나온다() throws Exception {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.info.title").value("company-insight API"))
			.andExpect(jsonPath("$.info.description").value(containsString("투자 권유가 아니다")))
			// 컨트롤러 메서드의 Javadoc
			.andExpect(jsonPath("$.paths['/api/companies'].get.description").value(containsString("기업 검색")))
			.andExpect(jsonPath("$.paths['/api/companies'].get.parameters[0].description")
				.value(containsString("기업명 일부 또는 종목코드 앞부분")))
			.andExpect(jsonPath("$.paths['/api/companies'].get.responses['200'].content['application/json']").exists())
			.andExpect(jsonPath("$.paths['/api/companies'].get.responses['400'].content['application/problem+json'].schema['$ref']")
				.value("#/components/schemas/ProblemDetail"))
			// 응답 record의 @param
			.andExpect(jsonPath("$.components.schemas.CompanySummary.properties.id.description")
				.value(containsString("회사 ID")))
			.andExpect(jsonPath("$.components.schemas.ProblemDetail.properties.status").exists());
	}
}
