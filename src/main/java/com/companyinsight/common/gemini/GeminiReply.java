package com.companyinsight.common.gemini;

/**
 * Gemini가 돌려준 답.
 *
 * @param text         답 글 (JSON 응답을 요청했으면 JSON 글). 생각 토큰의 글은 뺐다
 * @param finishReason 끝난 이유 (STOP 등)
 * @param inputTokens  입력 토큰 수. 응답에 없으면 null
 * @param outputTokens 출력 토큰 수(답 + 생각). 응답에 없으면 null
 */
public record GeminiReply(String text, String finishReason, Integer inputTokens, Integer outputTokens) {
}
