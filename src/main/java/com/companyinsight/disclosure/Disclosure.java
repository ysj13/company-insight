package com.companyinsight.disclosure;

import com.companyinsight.common.source.Source;
import com.companyinsight.company.Company;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 공시 한 건. 조회 전용 매핑이다.
 *
 * <p>저장과 분류 갱신은 {@link DisclosureRepository}가 JDBC로 한다(대량 insert, ON CONFLICT).
 * 이 엔티티는 조건을 조합하는 조회({@link DisclosureQueryRepository})에 쓰며, 조회에 쓰는 컬럼만 매핑한다.
 */
@Entity
@Immutable
@Table(name = "disclosure")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Disclosure {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 공시를 받은 출처 */
	@Enumerated(EnumType.STRING)
	private Source source;

	/** 출처의 공시 번호. DART는 접수번호 14자리. 원문 링크를 만드는 값 */
	private String sourceKey;

	/** 공시를 낸 회사 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "company_id")
	private Company company;

	/** 보고서명 (공시 제목) */
	private String reportName;

	/** 접수일 */
	@Column(name = "rcept_date")
	private LocalDate receiptDate;

	/** 공시 종류 */
	@Enumerated(EnumType.STRING)
	private DisclosureKind kind;

	/** 판정. 아직 판정하지 않았으면 null */
	@Enumerated(EnumType.STRING)
	private Tone tone;

	/** 영향도. 호재·악재일 때만 값이 있다. DB 컬럼이 smallint라 타입을 맞춘다 */
	@JdbcTypeCode(SqlTypes.SMALLINT)
	private Integer impact;

	/** 이미 낸 공시의 정정·첨부 추가인지 */
	@Column(name = "is_correction")
	private boolean correction;

	/** 철회된 공시인지 */
	@Column(name = "is_withdrawn")
	private boolean withdrawn;
}
