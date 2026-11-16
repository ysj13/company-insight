package com.companyinsight.disclosure;

/**
 * 공시 목록을 판정으로 거를 때 고르는 값.
 *
 * <p>{@link Tone}에는 "아직 판정하지 않음"이 없어(DB에서는 NULL) 목록 조건용으로 따로 둔다.
 */
public enum ToneFilter {

	/** 호재 */
	POSITIVE("호재"),

	/** 악재 */
	NEGATIVE("악재"),

	/** 중립 */
	NEUTRAL("중립"),

	/** 제목만으로 판정하지 못해 판정을 비워 둔 공시 */
	UNDECIDED("미판정");

	private final String label;

	ToneFilter(String label) {
		this.label = label;
	}

	/** 화면에 보여주는 이름 */
	public String label() {
		return label;
	}

	/**
	 * 공시의 판정에 해당하는 값.
	 *
	 * @param tone 판정. 아직 판정하지 않았으면 null
	 */
	public static ToneFilter of(Tone tone) {
		if (tone == null) {
			return UNDECIDED;
		}
		return switch (tone) {
			case POSITIVE -> POSITIVE;
			case NEGATIVE -> NEGATIVE;
			case NEUTRAL -> NEUTRAL;
		};
	}
}
