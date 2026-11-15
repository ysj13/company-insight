# company-insight

국내 상장사의 공시와 뉴스를 읽고, 지금 분위기가 호재 쪽인지 악재 쪽인지를 근거와 함께 보여주는 공개 웹 서비스.
상세 기획은 `docs/PLAN.md`, 개발 계획(버전·순서)은 `docs/DEVELOPMENT.md`, 화면 데모는 `docs/demo.html`.

## 명령어

- 로컬 DB: `docker compose up -d` (`.env.example`을 `.env`로 복사해 값 입력)
- 실행: `./gradlew bootRun`
- 빌드·테스트: `./gradlew build` (Testcontainers 사용, Docker 실행 필요)

## 응답 규칙

- 설명과 답변은 한국어로 한다.
- 결정이 바뀌면 `docs/PLAN.md` 또는 `docs/DEVELOPMENT.md`를 함께 고친다.

## 반드시 지킬 것

- **종목을 추천하지 않는다.** 화면에 "AI 요약은 틀릴 수 있으며 투자 권유가 아님" 고지를 유지한다.
- **DART 데이터는 출처를 표시하고 원문 링크를 단다.** 공시 원문 전체를 복제해 보여주지 않고 요약과 핵심 수치만 보여준다.
- **뉴스는 제목과 원문 링크만 노출한다.** 본문을 저장하거나 표시하지 않는다.
- **비밀 값은 커밋하지 않는다.** API 키와 비밀번호는 `.env`에만 두고, 필요한 키 이름은 `.env.example`에 적는다.
- **브랜치 규칙:** `main`에는 직접 커밋·push하지 않는다. 작업은 `feat/`·`fix/`·`chore/`·`docs/`·`test/` 브랜치에서 하고, 기능 브랜치의 커밋·push·PR 생성은 묻지 않고 진행한다. PR Merge는 사용자가 한다. 커밋 전에 `git status`와 diff로 올라갈 파일을 확인한다. 상세는 `docs/DEVELOPMENT.md` 2절.

## 기술 스택

Java 25 LTS, Spring Boot 4.1, Gradle 9, PostgreSQL 18, JPA + QueryDSL 7(OpenFeign 포크), Spring Batch, Flyway, Caffeine, Thymeleaf, Chart.js, Docker Compose, GitHub Actions, Sentry. 버전은 `docs/DEVELOPMENT.md` 1절. 코드 규칙은 `docs/CONVENTIONS.md`.

## 설계 원칙

- 호재/악재 판단 점수는 LLM이 아니라 코드가 계산한다. LLM은 소식 건별 분류(tone, 영향도 1~3)와 요약만 한다.
- 공시 종류로 판정되는 것은 규칙으로 먼저 분류하고, 나머지만 LLM에 보낸다.
- LLM이 뽑은 금액과 날짜는 원문에 실제로 있는지 코드로 대조한다.
- 같은 공시는 접수번호(rcept_no) unique 제약으로 한 번만 저장한다.
- 외부 API 호출에는 타임아웃과 재시도를 두고, 한 소스가 실패해도 나머지 수집은 계속한다.
