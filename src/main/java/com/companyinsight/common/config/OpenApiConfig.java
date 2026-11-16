package com.companyinsight.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 문서(Swagger UI)의 제목과 공통 오류 응답.
 *
 * <p>API와 파라미터, 응답 필드의 설명은 여기서 쓰지 않는다. 컨트롤러와 DTO의 Javadoc을 빌드할 때 함께 담아
 * springdoc이 읽게 했다(build.gradle.kts의 therapi-runtime-javadoc). 설명을 코드 주석 한 곳에만 쓰기 위해서다.
 * API 문서를 켰을 때만 만든다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class OpenApiConfig {

	private static final String PROBLEM_SCHEMA = "ProblemDetail";

	private static final String PROBLEM_JSON = "application/problem+json";

	/** 문서 맨 위의 제목과 설명. */
	@Bean
	OpenAPI openApi() {
		// 실행 파일(jar)로 띄우면 빌드 버전이 나오고, IDE나 테스트에서는 버전 정보가 없다
		String version = getClass().getPackage().getImplementationVersion();
		return new OpenAPI().info(new Info().title("company-insight API")
			.description("국내 상장사의 공시를 읽고 분위기가 호재 쪽인지 악재 쪽인지를 근거와 함께 보여주는 서비스의 조회 API. "
					+ "공시 출처는 금융감독원 전자공시시스템(DART)이다. AI 요약은 틀릴 수 있으며 투자 권유가 아니다.")
			.version(version == null ? "dev" : version));
	}

	/**
	 * 오류 응답 본문(RFC 9457 Problem Details)의 형식을 문서에 등록한다.
	 * Spring의 ProblemDetail 클래스에서 자동으로 뽑으면 실제 응답에는 없는 properties 필드가 생겨, 실제로 나가는 필드만 직접 적는다.
	 */
	@Bean
	OpenApiCustomizer problemDetailSchema() {
		return openApi -> openApi.getComponents()
			.addSchemas(PROBLEM_SCHEMA, new ObjectSchema().description("오류 응답 (RFC 9457 Problem Details)")
				.addProperty("type", new StringSchema().description("오류 종류를 가리키는 주소. 따로 정하지 않은 오류에는 없다"))
				.addProperty("title", new StringSchema().description("오류 이름. 예: Bad Request"))
				.addProperty("status", new IntegerSchema().description("HTTP 상태 코드"))
				.addProperty("detail", new StringSchema().description("오류 설명"))
				.addProperty("instance", new StringSchema().description("요청한 경로")));
	}

	/**
	 * 파라미터나 본문을 받는 API에 400 응답을 적는다. 입력 검증 실패는 컨트롤러마다 처리하지 않고
	 * Spring이 공통으로 Problem Details로 응답하므로 문서에도 공통으로 넣는다.
	 */
	@Bean
	OperationCustomizer badRequestResponse() {
		return (operation, handlerMethod) -> {
			boolean takesInput = (operation.getParameters() != null && !operation.getParameters().isEmpty())
					|| operation.getRequestBody() != null;
			if (takesInput && operation.getResponses() != null && operation.getResponses().get("400") == null) {
				Schema<?> problem = new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA);
				operation.getResponses()
					.addApiResponse("400", new ApiResponse().description("요청 파라미터가 올바르지 않음")
						.content(new Content().addMediaType(PROBLEM_JSON, new MediaType().schema(problem))));
			}
			return operation;
		};
	}
}
