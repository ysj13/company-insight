package com.companyinsight.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.companyinsight.TestcontainersConfiguration;
import com.companyinsight.common.dart.DartClient;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API 문서를 켰을 때 제목과, Javadoc에서 가져온 설명이 문서에 들어가는지 확인한다.
 * 설명은 빌드할 때 담기므로, 빌드 설정이 빠지면 이 테스트가 실패한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "springdoc.api-docs.enabled=true")
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
			.andExpect(jsonPath("$.paths['/api/companies'].get.parameters[?(@.name == 'q')].description")
				.value(org.hamcrest.Matchers.hasItem(containsString("종목코드"))))
			.andExpect(jsonPath("$.paths['/api/companies'].get.responses['200'].content['application/json']").exists())
			.andExpect(jsonPath("$.paths['/api/companies'].get.responses['400'].content['application/problem+json'].schema['$ref']")
				.value("#/components/schemas/ProblemDetail"))
			// 응답 record의 @param
			.andExpect(jsonPath("$.components.schemas.CompanySummary.properties.id.description")
				.value(containsString("회사 ID")))
			// 오류 응답에는 실제로 나가는 필드만 적는다
			.andExpect(jsonPath("$.components.schemas.ProblemDetail.type").value("object"))
			.andExpect(jsonPath("$.components.schemas.ProblemDetail.properties.status").exists())
			.andExpect(jsonPath("$.components.schemas.ProblemDetail.properties.properties").doesNotExist())
			// 화면(HTML)은 API 문서에 넣지 않는다
			.andExpect(jsonPath("$.paths['/disclosures']").doesNotExist());
	}

	/**
	 * 운영에서는 API 문서를 끈다. 실행 환경의 값(.env)에 좌우되지 않게, 설정 파일에 적힌 기본값 자체를 확인한다.
	 * springdoc은 이 키가 없으면 문서를 켜므로, 키를 지우거나 기본값을 바꾸면 여기서 실패한다.
	 */
	@Test
	void 설정_파일의_기본값은_API_문서를_끈다() {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("application.yml"));
		Properties properties = yaml.getObject();

		assertThat(properties).containsEntry("springdoc.api-docs.enabled", "${API_DOCS_ENABLED:false}")
			.containsEntry("springdoc.swagger-ui.enabled", "${API_DOCS_ENABLED:false}");
	}
}
