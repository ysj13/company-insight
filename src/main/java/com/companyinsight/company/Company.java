package com.companyinsight.company;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 상장사. 조회 전용 매핑이다.
 *
 * <p>저장과 갱신은 {@link CompanyRepository}가 JDBC로 한다(대량 upsert). 이 엔티티는 공시 목록처럼
 * 조건을 조합하는 조회에서 회사명과 종목코드를 함께 읽기 위해 둔다. 조회에 쓰는 컬럼만 매핑한다.
 */
@Entity
@Immutable
@Table(name = "company")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Company {

	/** 회사 ID. 출처와 무관한 내부 번호 */
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 종목코드. 한국은 6자리 */
	private String stockCode;

	/** 회사명 */
	private String name;
}
