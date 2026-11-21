package com.companyinsight.analysis;

/**
 * AI를 한 번 부른 기록. AI 단계의 실행 한 번이 AI 호출 한 번이다. 실행 기록({@code analysis_run})에 함께 남겨
 * 하루 호출 수, 토큰, 어느 모델·지시문으로 판정했는지를 실행 기록만 보고 알 수 있게 한다.
 *
 * @param settingVersionId 부를 때 쓴 모델·지시문 설정의 버전({@code setting_version.id})
 * @param inputTokens      입력 토큰 수. 응답에 없으면 null
 * @param outputTokens     출력 토큰 수(생각 토큰 포함). 응답에 없으면 null
 */
public record AiUsage(long settingVersionId, Integer inputTokens, Integer outputTokens) {

	/** 응답을 받지 못한 호출(과부하, 한도). 토큰을 알 수 없다. */
	public static AiUsage withoutTokens(long settingVersionId) {
		return new AiUsage(settingVersionId, null, null);
	}
}
