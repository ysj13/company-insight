# 개발 계획

작성일 2026-11-16, 수정 2026-11-17. 기획은 `PLAN.md`, 이 문서는 기술 스택, 구조, 개발 순서를 정리한다.

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
| LLM | LLM API를 `LlmClient` 인터페이스 뒤에 두어 모델을 바꿀 수 있게 한다. 모델은 분석 기능을 설계할 때 정한다 |
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
├── web           화면(Thymeleaf 컨트롤러)
└── common        설정, 외부 API 공통(타임아웃·재시도·속도 제한)
```

### 추가와 수정이 쉬운 구조

미국·일본 상장사를 MVP 이후에 추가한다. 그때 구조를 뜯어고치지 않도록 다음을 지킨다.

- 회사는 내부 번호를 기준 키로 쓰고, 나라와 출처, 그 출처에서의 번호(DART 고유번호 등)를 따로 저장한다.
- 공시의 중복 방지는 "출처 + 그 출처의 공시 번호"로 한다. 지금의 접수번호(rcept_no) unique는 DART 출처에 대한 같은 규칙이다.
- 수집기와 제목 규칙은 출처별로 나눠, 새 출처를 추가할 때 기존 코드를 고치지 않게 한다.
- 새로 만드는 테이블(재무, 분석, 판단)은 회사의 내부 번호를 참조하고, 금액에는 통화를 함께 저장한다.
- 수집 기능은 회사 한 곳을 지정해 받을 수 있게 만든다. 기능을 완성한 뒤 테스트 회사 몇 곳으로 확인하고, 이상이 없으면 전 종목 데이터를 채운다.

### 주요 테이블 (초안)

| 테이블 | 핵심 컬럼 |
|---|---|
| `company` | id(PK), 나라, 출처, 출처에서의 번호(출처와 묶어 **unique**), stock_code, name, market |
| `disclosure` | id, 출처, 출처의 공시 번호(출처와 묶어 **unique**. DART는 rcept_no), company_id, report_name, rcept_date, kind, tone, impact, classified_by(RULE/LLM), rule_version, is_correction, is_withdrawn |
| `disclosure_summary` | disclosure_id, lines(핵심 3줄), model, verified |
| `analysis_job` | id, disclosure_id, priority, status(READY/RUNNING/DONE/FAILED), attempts, next_run_at |
| `company_score` | company_id, date, score, up_sum, down_sum (company_id+date unique) |
| `financial` | company_id, year, quarter, revenue, op_income, net_income, currency |
| `collection_run` | source, started_at, finished_at, status, fetched, saved, error, error_kind, error_code |

`company`, `disclosure`, `collection_run`은 만들어져 있다. `company`와 `disclosure`의 기준 키는 지금 DART 고유번호와 접수번호이고, 위 표의 구조로 바꾸는 것이 3절의 "구조 변경" 단계다. 나머지 테이블의 컬럼은 해당 단계에서 확정한다.

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

1. **종목 화면까지의 흐름을 먼저 완성한다.** 이 서비스가 보여주는 것은 공시 목록이 아니라 회사 한 곳에 대한 판단과 근거다. 재무 수집, 본문 분석, 판단 계산, 종목 화면을 먼저 잇고, 배포와 공개 오픈은 그 뒤에 한다.
2. **구조 변경이 새 테이블보다 먼저다.** 재무·분석·판단 테이블이 모두 회사를 참조하므로, 회사와 공시의 기준 키를 바꾸는 일은 테이블이 늘기 전에 한다.
3. **기능이 데이터보다 먼저다.** 데이터가 많이 쌓인 뒤에는 구조를 고치기 어렵다. 기능을 완성하고 테스트 회사 몇 곳의 실제 데이터로 확인한 뒤 전 종목 데이터를 채운다.
4. **규칙이 LLM보다 먼저다.** 규칙으로 처리되는 비율을 먼저 재야 LLM에 보낼 양을 알 수 있다.
5. **작업 큐가 LLM보다 먼저다.** LLM 호출은 느리고 실패하므로 재시도 구조 위에 올린다.

### 단계

단계 번호는 GitHub 이슈의 제목과 같다. 순서는 표에 적힌 순서를 따른다.

**끝난 단계**

| # | 끝난 날 | 단계 | 만든 것 | 완료 기준 |
|---|---|---|---|---|
| 1 | 11/16 | 뼈대 | Gradle, Boot 4.1, Compose(PostgreSQL), Flyway V1, 헬스체크, CI, `.env.example`, `.gitattributes`, 문서를 `docs/`로 이동 | 로컬에서 앱과 DB가 뜨고 CI 통과 |
| 3 | 11/16 | 기업 목록 | 고유번호 zip 받아 `company` 적재(하루 1회), 이름 검색 | 상장사 목록이 DB에 있음 |
| 4 | 11/16 | 공시 수집 | 상장폐지 기업 구분(고유번호 파일에 함께 들어 있음), 평일 5분 폴링, rcept_no 중복 방지, 타임아웃·재시도·속도 제한, `collection_run` 기록 | 새 공시가 5분 안에 들어오고 하루 건수 실측 |
| 5 | 11/16 | 규칙 분류 | 공시 제목 규칙으로 종류·tone·영향도 판정, 정정·철회 공시 처리, 나머지는 판정을 비워 분석 대상으로 남김(작업 등록은 6단계) | 규칙 처리 비율 측정 (74.0%, `PLAN.md` 6절) |
| 5-1 | 11/16 | 공시 목록 화면 | 수집한 공시와 분류 결과를 최근 접수 순으로 보여주는 한 페이지(`/disclosures`), 검색창 자동완성으로 회사 한 곳 고르기, DART 원문 링크. 분류 결과 확인용이고 서비스 화면에는 연결하지 않는다. 자동완성과 화면 틀은 종목 화면에 쓴다 | 분류 결과를 실제 데이터로 화면에서 확인 |

**MVP (~ 11/22)**

| # | 기간 | 단계 | 만드는 것 | 완료 기준 |
|---|---|---|---|---|
| 5-2 | 11/17 | 구조 변경 | `company`의 기준 키를 내부 번호로 바꾸고 나라·출처·출처에서의 번호를 저장, `disclosure`의 중복 방지를 "출처 + 출처의 공시 번호"로 변경, 기존 데이터 이전 | 이전 뒤 회사·공시 건수가 같고, 같은 공시를 다시 받아도 한 번만 저장됨 |
| 5-3 | 11/17~18 | 재무 수집 | OpenDART 주요계정으로 분기별 매출액·영업이익·순이익 수집(회사 한 곳을 지정해 받기), 전년 동기 대비 증감 계산 | 테스트 회사의 재무가 저장되고 증감이 실제 공시 수치와 맞음 |
| 6 | 11/18 | 작업 큐 | `analysis_job`, `SKIP LOCKED` 워커, 우선순위, 3회 실패 시 FAILED | 워커 2개가 같은 작업을 중복 처리하지 않음을 테스트로 확인 |
| 7 | 11/19 | LLM 분석 | 원문 받아 텍스트 추출 → LLM 요약·분류 → 금액·날짜 원문 대조 → 하루 예산 | 테스트 회사의 공시에 판정과 핵심 3줄이 붙음 |
| 8 | 11/20 | 판단 계산 | 회사 단위 판단 점수 계산(공시 건별 판정과 재무 실적 반영), 단계 이름 변환, 저장 | 테스트 회사의 판단이 계산되고 근거를 설명할 수 있음 |
| 9·10 | 11/21~22 | 조회와 종목 화면 | 종목 요약·타임라인 조회(QueryDSL), Thymeleaf 종목 화면(결론, 판단 근거, 실적, 타임라인, 고지 문구), 검색창에서 종목 화면으로 이동. 판단 추이와 예정 일정은 자리만 | 테스트 회사의 종목 화면이 실제 데이터로 뜸 |

**MVP 이후 (날짜는 MVP를 마친 뒤 정한다)**

| # | 단계 | 만드는 것 | 완료 기준 |
|---|---|---|---|
| 8 | 백필 | Spring Batch로 전 종목 과거 공시·재무 적재, 일별 추이 저장 | 전 종목 판단이 계산됨 |
| 2 | 배포 | Dockerfile, GHCR, VM에 Compose, Caddy HTTPS, main 합치면 자동 배포 | 공개 주소 `/actuator/health` 응답 |
| 11 | 공개 오픈 | Sentry, 운영자 텔레그램 알림, DB 백업, 로깅 정책, 개인정보·면책 문구 | 장애가 나면 알림이 옴 |
| 12 | 품질 | 오분류 점검, 성능 점검, 버그 수정, README와 설계 결정 기록 | 공개 가능한 상태 |
| 10 | 홈과 나머지 화면 | 홈(최근 본 종목, 공시 피드), 판단 추이, 예정 일정 | 실제 데이터로 화면이 뜸 |

### MVP에서 자리만 두는 것과 하지 않는 것

- 자리만 둔다: 판단 추이 그래프, 타임라인의 예정 일정.
- 하지 않는다: 전 종목 백필, 재무 체크리스트, 홈 공시 피드, 뉴스 수집, 배포.

