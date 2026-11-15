# company-insight

국내 상장사의 공시와 뉴스를 읽고, 지금 분위기가 호재 쪽인지 악재 쪽인지를 근거와 함께 보여주는 웹 서비스.

- 기획: [docs/PLAN.md](docs/PLAN.md)
- 개발 계획: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)
- 화면 데모: [docs/demo.html](docs/demo.html)

## 기술 스택

Java 25, Spring Boot 4.1, PostgreSQL 18, Spring Data JPA, QueryDSL, Spring Batch, Flyway, Docker Compose, GitHub Actions

## 로컬 실행

필요: JDK 25, Docker

```bash
cp .env.example .env
docker compose up -d
./gradlew bootRun
```

헬스체크: http://localhost:8080/actuator/health

## 테스트

```bash
./gradlew test
```

테스트는 Testcontainers로 PostgreSQL을 띄우므로 Docker가 실행 중이어야 한다.

## 데이터 출처

공시·재무 데이터: 금융감독원 전자공시시스템(DART) OpenDART API
