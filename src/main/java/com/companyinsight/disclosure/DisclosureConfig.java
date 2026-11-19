package com.companyinsight.disclosure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 회사를 지정한 공시 수집 설정({@link DisclosureCollectProperties}) 등록. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DisclosureCollectProperties.class)
class DisclosureConfig {
}
