package com.companyinsight.common.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 조건을 조합하는 조회에 쓰는 QueryDSL 쿼리 팩토리를 빈으로 둔다. */
@Configuration(proxyBeanMethods = false)
public class QuerydslConfig {

	/** 주입되는 EntityManager는 트랜잭션마다 실제 대상을 찾아 주는 프록시라 하나를 함께 써도 된다. */
	@Bean
	JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
		return new JPAQueryFactory(entityManager);
	}
}
