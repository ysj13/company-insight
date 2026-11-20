package com.companyinsight.disclosure;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 공시 유형. 조회 전용 매핑이다. 값은 마이그레이션으로만 넣고 고친다.
 * 조회에 쓰는 컬럼만 매핑한다. 판정 방법과 기본 방향은 {@link TitleRuleBook}이 읽는다.
 */
@Entity
@Immutable
@Table(name = "disclosure_type")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DisclosureType {

	/** 대응표에 없는 제목이 받는 유형 번호 */
	public static final String UNCLASSIFIED = "Z9";

	/** 유형 번호 (예: A1, K1) */
	@Id
	private String code;

	/** 묶음 */
	@Enumerated(EnumType.STRING)
	private DisclosureBundle bundle;

	/** 유형 이름 */
	private String name;
}
