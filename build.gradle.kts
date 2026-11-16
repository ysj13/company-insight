plugins {
	java
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.companyinsight"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

val querydslVersion = "7.7"
// springdoc 3.2.0이 맞춰 둔 버전
val therapiVersion = "0.15.0"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-batch-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-cache")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0")
	// API 문서의 설명을 Javadoc에서 가져온다. 빌드할 때 Javadoc을 리소스로 담고(scribe), springdoc이 실행 중에 읽는다
	runtimeOnly("com.github.therapi:therapi-runtime-javadoc:$therapiVersion")
	annotationProcessor("com.github.therapi:therapi-runtime-javadoc-scribe:$therapiVersion")
	implementation("com.github.ben-manes.caffeine:caffeine")
	implementation("io.github.resilience4j:resilience4j-ratelimiter:2.4.0")
	implementation("io.github.openfeign.querydsl:querydsl-jpa:$querydslVersion")
	annotationProcessor("io.github.openfeign.querydsl:querydsl-apt:$querydslVersion:jpa")
	annotationProcessor("jakarta.persistence:jakarta.persistence-api")
	compileOnly("org.projectlombok:lombok")
	runtimeOnly("org.postgresql:postgresql")
	annotationProcessor("org.projectlombok:lombok")

	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-batch-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-cache-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
	options.compilerArgs.add("-parameters")
}

tasks.named<JavaCompile>("compileJava") {
	// Javadoc을 담는 범위를 이 프로젝트의 코드로 좁힌다. 테스트 코드에는 이 처리기가 없어 본 코드 컴파일에만 준다
	options.compilerArgs.add("-Ajavadoc.packages=com.companyinsight")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
