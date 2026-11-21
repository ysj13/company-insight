package com.companyinsight.analysis;

/**
 * 실행 기록에 남길 것이 있는 실패. 횟수로 센다. 예를 들어 AI가 답은 했는데 숫자가 원문과 맞지 않을 때,
 * 그 답을 실행 기록의 결과에 남겨 무엇이 틀렸는지 나중에 볼 수 있게 한다.
 */
public class StageFailedException extends RuntimeException {

	private final String errorKind;
	private final String resultJson;
	private final AiUsage aiUsage;

	/**
	 * @param errorKind  실패 분류 (예: VERIFY 대조 실패). 20자까지
	 * @param message    설명
	 * @param resultJson 실행 기록에 남길 것(JSON 글). 없으면 null
	 * @param aiUsage    AI를 불렀으면 그 기록. 아니면 null
	 */
	public StageFailedException(String errorKind, String message, String resultJson, AiUsage aiUsage) {
		super(message);
		this.errorKind = errorKind;
		this.resultJson = resultJson;
		this.aiUsage = aiUsage;
	}

	/** 실패 분류. */
	public String errorKind() {
		return errorKind;
	}

	/** 실행 기록에 남길 것. 없으면 null. */
	public String resultJson() {
		return resultJson;
	}

	/** AI 호출 기록. 없으면 null. */
	public AiUsage aiUsage() {
		return aiUsage;
	}
}
