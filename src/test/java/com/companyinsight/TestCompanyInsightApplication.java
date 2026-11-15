package com.companyinsight;

import org.springframework.boot.SpringApplication;

public class TestCompanyInsightApplication {

	public static void main(String[] args) {
		SpringApplication.from(CompanyInsightApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
