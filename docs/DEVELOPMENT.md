# 개발 계획

작성일 2026-11-16. 기획은 `PLAN.md`, 이 문서는 기술 스택, 구조, 개발 순서를 정리한다.

## 1. 기술 스택

| 영역 | 기술 |
|---|---|
| 언어 | Java 25 (Temurin) |
| 프레임워크 | Spring Boot 4.1 |
| 빌드 | Gradle 9 (Kotlin DSL, Wrapper) |
| DB | PostgreSQL 18 (`pg_trgm`) |
| 데이터 접근 | Spring Data JPA, QueryDSL 7 (OpenFeign) |
| 배치 | Spring Batch 6 |
| 스키마 | Flyway |
| 캐시 | Caffeine |
| 외부 API 호출 | Spring `RestClient`, 가상 스레드, `@Retryable`, `@ConcurrencyLimit`, Resilience4j RateLimiter |
| LLM | Gemini API (`LlmClient` 인터페이스) |
| API 문서 | springdoc-openapi |
| 화면 | Thymeleaf(서버 렌더링), JavaScript, Chart.js (`static/vendor`에 고정 버전으로 포함) |
| 테스트 | JUnit 5, AssertJ, Testcontainers (PostgreSQL), `MockRestServiceServer` |
| 배포 | Docker Compose (앱, PostgreSQL, Caddy), GHCR, GitHub Actions |
| 서버 | 클라우드 VM 1대 |
| 관측 | Actuator, Sentry, 텔레그램 운영 알림 |

### 프로젝트 구조

Gradle 모듈 하나, 기능별 패키지로 나눈다.

```
com.companyinsight
├── company       기업 목록(고유번호), 검색
├── disclosure    공시 수집, 규칙 분류
├── analysis      작업 큐, 워커, LLM 요약, 원문 대조 검증
├── score         호재/악재 점수, 일별 추이
├── financial     재무 수집, 체크리스트
├── backfill      Spring Batch 백필
├── ops           수집 실행 기록, 운영자 알림
└── common        설정, 외부 API 공통(타임아웃·재시도·속도 제한)
```

### 주요 테이블 (초안)

| 테이블 | 핵심 컬럼 |
|---|---|
| `company` | corp_code(PK), stock_code, name, market |
| `disclosure` | id, rcept_no(**unique**), corp_code, report_nm, rcept_dt, category, tone, impact, classified_by(RULE/LLM) |
| `disclosure_summary` | disclosure_id, lines(핵심 3줄), model, verified |
| `analysis_job` | id, disclosure_id, priority, status(READY/RUNNING/DONE/FAILED), attempts, next_run_at |
| `company_score` | corp_code, date, score, up_sum, down_sum (corp_code+date unique) |
| `financial` | corp_code, year, quarter, revenue, op_income, debt_ratio, dividend |
| `collection_run` | source, started_at, finished_at, status, fetched, saved, error |

## 2. 개발 도구

| 용도 | 도구 |
|---|---|
| 코드 작성 | Claude Code |
| 코드 읽기·디버깅 | IntelliJ IDEA |
| JDK | Temurin 25 |
| 로컬 PostgreSQL, 테스트 | Docker Desktop |
| DB 조회 | DBeaver 또는 IntelliJ Database 도구 |
| API 호출 확인 | `.http` 파일 (키는 `.env`에서 읽음) |
| 버전 관리 | Git, GitHub CLI |

### 브랜치 전략 (GitHub Flow)

- `main`은 항상 배포 가능한 상태다. 직접 push할 수 없고, PR로만 합친다.
- 작업은 `main`에서 브랜치를 만들어 하고, 3절의 단계 하나를 브랜치 하나·PR 하나로 한다.
- 브랜치 이름은 커밋 접두어와 같다.

| 접두어 | 용도 | 예 |
|---|---|---|
| `feat/` | 기능 | `feat/disclosure-polling` |
| `fix/` | 버그 수정 | `fix/duplicate-rcept-no` |
| `chore/` | 설정, 빌드, 배포 | `chore/deploy-pipeline` |
| `docs/` | 문서 | `docs/readme-architecture` |
| `test/` | 테스트 | `test/job-queue-concurrency` |

- 커밋 메시지도 같은 접두어(`feat:`, `fix:`, `chore:`, `docs:`, `test:`)로 시작한다.
- PR은 CI(`build`)가 통과하고 브랜치가 최신 `main`을 반영해야 합칠 수 있다.
- 합칠 때는 Squash merge만 쓴다. 합친 브랜치는 자동 삭제된다.
- 공개 오픈 시점에 `v0.1.0` 태그를 붙이고, 이후 큰 기능마다 버전을 올린다.

### 역할

| 단계 | 담당 |
|---|---|
| 브랜치 생성, 코드 작성, 테스트, 커밋, push, PR 생성 | 에이전트 |
| CI 실패 수정 | 에이전트 |
| PR 검토와 Merge | 사용자 |

- PR을 만들면 코드를 작성한 에이전트와 별개의 리뷰 에이전트가 diff를 검토한다. 아래 "코드 리뷰" 참고.
- 비밀 값은 `.env`에만 둔다.

### 코드 리뷰

코드를 작성한 에이전트는 자기 코드의 문제를 잘 보지 못한다. 그래서 리뷰는 작성 과정을 모르는 별도 에이전트가 한다.

1. 코드를 바꾸는 PR은 **Draft**로 만든다. Draft는 Merge할 수 없으므로 리뷰 도중에 합쳐지지 않는다.
2. PR을 만든 직후 별도 리뷰 에이전트에게 검토를 맡긴다. 리뷰 에이전트는 작성 과정을 모르는 상태에서 diff와 저장소 규칙(`CLAUDE.md`, `docs/CONVENTIONS.md`)만 보고 검토하며, 파일을 고치지 않고 보고만 한다.
3. 보는 것: 동작 오류, 누락된 예외·경계 처리, 동시성, 보안(비밀 값 노출, 입력 검증), 규칙 위반, 빠진 테스트.
4. 확인된 문제는 같은 브랜치에서 고쳐 push하고, 로컬 빌드와 CI를 다시 통과시킨다.
5. 리뷰 결과(지적 사항, 고친 것, 고치지 않은 것과 이유)를 PR 댓글로 남긴다.
6. 리뷰 반영을 push한 뒤 PR을 Ready for review로 바꾼다. 이때부터 Merge할 수 있다.
7. 문서만 바꾼 PR은 리뷰를 생략하고 바로 Ready로 만들 수 있다.

### PR 완료 기준

1. 새 로직에 테스트가 있다. 점수 계산, 규칙 분류, 원문 대조 검증은 단위 테스트, DB 쿼리와 작업 큐는 Testcontainers 테스트.
2. CI가 통과한다.
3. 독립 코드 리뷰를 거쳤고, 확인된 문제를 고쳤다.
4. 결정이 바뀌었으면 `PLAN.md` 또는 이 문서를 함께 고쳤다.

## 3. 기능 개발 순서

### 순서를 정한 원칙

1. **배포를 먼저 한다.** 기능이 하나도 없어도 2일차에 공개 주소에서 응답하게 만든다. 이후 모든 기능은 만들자마자 운영 서버에서 돈다.
2. **데이터가 화면보다 먼저다.** 실제 공시가 쌓여야 분류 규칙과 점수를 실제 데이터로 맞출 수 있다.
3. **규칙이 LLM보다 먼저다.** 규칙으로 처리되는 비율을 먼저 재야 LLM에 보낼 양과 무료 한도 안에 들어오는지 알 수 있다.
4. **작업 큐가 LLM보다 먼저다.** LLM 호출은 느리고 실패하므로 재시도 구조 위에 올린다.

### 단계

| # | 기간 | 단계 | 만드는 것 | 완료 기준 |
|---|---|---|---|---|
| 0 | 11/17 오전 | 준비 | 개발 환경, OpenDART·Gemini 키, VM, 도메인 | 키가 `.env`에 들어감 |
| 1 | 11/17 | 뼈대 | Gradle, Boot 4.1, Compose(PostgreSQL), Flyway V1, 헬스체크, CI, `.env.example`, `.gitattributes`, 문서를 `docs/`로 이동 | 로컬에서 앱과 DB가 뜨고 CI 통과 |
| 2 | 11/18 | 배포 | Dockerfile, GHCR, VM에 Compose, Caddy HTTPS, main 합치면 자동 배포 | 공개 주소 `/actuator/health` 응답 |
| 3 | 11/18 | 기업 목록 | 고유번호 zip 받아 `company` 적재(하루 1회), 이름 검색 | 상장사 약 2,700곳이 DB에 있음 |
| 4 | 11/19 | 공시 수집 | 상장폐지 기업 구분(고유번호 파일에 함께 들어 있음), 평일 5분 폴링, rcept_no 중복 방지, 타임아웃·재시도·속도 제한, `collection_run` 기록 | 새 공시가 5분 안에 들어오고 하루 건수 실측 |
| 5 | 11/20 | 규칙 분류 | 공시 제목 규칙으로 종류·tone·영향도 판정, 정정·철회 공시 처리, 나머지는 판정을 비워 분석 대상으로 남김(작업 등록은 6단계). 재무 수집은 별도 PR | 규칙 처리 비율 측정 (74.0%, `PLAN.md` 6절) |
| 6 | 11/21 | 작업 큐 | `analysis_job`, `SKIP LOCKED` 워커, 우선순위, 3회 실패 시 FAILED | 워커 2개가 같은 작업을 중복 처리하지 않음을 테스트로 확인 |
| 7 | 11/22 | LLM 분석 | 원문 받아 텍스트 추출 → Gemini 요약·분류 → 금액·날짜 원문 대조 → 하루 예산 | 주요 30개 종목 공시에 핵심 3줄이 붙음 |
| 8 | 11/23 | 점수·백필 | 점수 계산, 일별 추이 저장, Spring Batch 3개월 백필 | 주요 30개 종목 판단 추이가 계산됨 |
| 9 | 11/24 | 조회 API | 종목 요약, 타임라인(QueryDSL), 홈 피드, 검색 | `.http`로 모든 API 확인 |
| 10 | 11/25 | 화면 | Thymeleaf로 종목 화면(판단 추이는 Chart.js)과 홈(검색·공시 피드) | 실제 데이터로 화면이 뜸 |
| 11 | 11/26~27 | 공개 오픈 | Sentry, 운영자 텔레그램 알림, DB 백업, 개인정보·면책 문구 | 장애가 나면 알림이 옴 |
| 12 | 11/28~30 | 품질 | 오분류 점검, 버그 수정, README와 설계 결정 기록 | MVP 완료 |

### 밀릴 때 줄이는 순서

1. 백필 기간 3개월 → 1개월
2. LLM 분석 대상 주요 30개 → 10개
3. 홈 공시 피드 → 검색창만
4. 공개 오픈 날짜(11/27)는 지킨다.

