# company-insight

국내 상장사의 공시와 뉴스를 읽고, 지금 분위기가 호재 쪽인지 악재 쪽인지를 근거와 함께 보여주는 웹 서비스.

- 기획: [docs/PLAN.md](docs/PLAN.md)
- 개발 계획: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)
- 화면 데모: [docs/demo.html](docs/demo.html)

## 기술 스택

Java 25, Spring Boot 4.1, PostgreSQL 18, Spring Data JPA, QueryDSL, Spring Batch, Flyway, Docker Compose, GitHub Actions

## 개발 환경 설치 (Windows)

새 컴퓨터에서 PowerShell로 한 번 실행하면 Git, GitHub CLI, JDK 25, Docker Desktop, IntelliJ IDEA 설치부터 저장소 clone, `.env` 생성, 로컬 DB 실행, 빌드까지 진행한다. 이미 설치된 항목은 건너뛴다.

```powershell
powershell -ExecutionPolicy Bypass -c "irm https://raw.githubusercontent.com/ysj13/company-insight/main/scripts/setup-windows.ps1 | iex"
```

직접 해야 하는 것: GitHub 로그인 승인, Docker Desktop 첫 실행 시 약관 동의, BIOS에서 CPU 가상화 켜기(꺼져 있을 때), `.env`에 API 키 입력.

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
