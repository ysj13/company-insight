# 코드 규칙

## 1. 패키지

기능별로 나눈다. 여러 기능이 함께 쓰는 코드만 `common`에 둔다.

```
com.companyinsight
├── company      기업 목록, 검색
├── disclosure   공시 수집, 분류
├── analysis     작업 큐, LLM 요약
├── score        호재/악재 점수
├── financial    재무
├── backfill     과거 데이터 배치
├── web          화면 (Thymeleaf 컨트롤러)
├── ops          수집 기록, 운영 알림
└── common       외부 API 클라이언트, 공통 오류, 설정
```

## 2. 계층

- 호출은 한 방향: `Controller → Service → Repository`. Controller는 Repository를 직접 부르지 않는다. 단순 조회도 같다.
- 트랜잭션(`@Transactional`)은 Service 또는 Repository에 둔다. Controller에는 두지 않는다.
- 정기 실행은 `XxxScheduler`가 시간에 맞춰 Service를 부르기만 한다. 로직은 Service에 둔다.

| 역할 | 이름 |
|---|---|
| HTTP API | `XxxController` (`@RestController`, `/api/**`) |
| 화면 | `XxxPageController` (`@Controller`, Thymeleaf) |
| 업무 로직 | `XxxService`, 수집은 `XxxCollector` |
| DB 접근 | `XxxRepository` |
| 외부 API | `XxxClient` |
| 정기 실행 | `XxxScheduler` |
| 설정 | `XxxProperties` (record + `@ConfigurationProperties("app.xxx")`), `XxxConfig` |

### 공통 코드

- 두 기능 이상이 실제로 쓰는 코드만 `common`(또는 `ops`)에 둔다. 미리 만들지 않고, 두 번째 사용처가 생길 때 옮긴다.
- 만능 `Util` 클래스에 모으지 않는다. 역할이 분명한 이름의 작은 클래스로 둔다.

| 공통 코드 | 용도 |
|---|---|
| `common.dart.DartText` | DART 문자열 다듬기(NUL 제거, 길이 자르기, 공백 정리), `yyyyMMdd` 날짜 변환 |
| `common.external.ExternalApiException`, `ErrorKind` | 외부 API 오류 분류 |
| `common.external.SourcePauseRegistry` | 외부 소스 일시 정지 |
| `common.schedule.ExclusiveJobRunner` | 정기 작업 중복 실행 방지 |
| `ops.CollectionRunRecorder` | 수집 실행 기록 (시작 → 실행 → 성공·실패) |

### 코드값

- 정해진 값 집합(시장 구분, 실행 상태, 오류 분류, 공시 종류 등)은 Java `enum`으로 둔다. 문자열 리터럴을 코드에 흩어 쓰지 않는다.
- DB에는 `varchar`로 저장한다. 값은 enum 이름을 쓰되, 외부 시스템의 코드를 그대로 보관해야 하면 enum에 그 코드를 필드로 둔다(예: `Market.dartCode()`).
- 컬럼 `COMMENT`에 값과 의미를 적는다.

## 3. 데이터 접근

| 용도 | 도구 |
|---|---|
| 엔티티 단건 조회·저장, 연관관계 | Spring Data JPA |
| 조건을 조합하는 동적 조회 | QueryDSL |
| 대량 저장(배치), `ON CONFLICT`·`ILIKE`·`SKIP LOCKED` 등 PostgreSQL 전용 문법 | `JdbcTemplate`, `JdbcClient` |

- 저장을 JDBC로 하는 테이블(`company`, `disclosure`)의 엔티티는 조회 전용(`@Immutable`)으로 두고, 조회에 쓰는 컬럼만 매핑한다. 쓰는 경로를 한 곳으로 유지하기 위해서다.
- 스키마는 Flyway로만 바꾼다. Hibernate는 `ddl-auto: validate`.
- 테이블과 컬럼에는 한글 `COMMENT`를 단다. 테이블·컬럼을 만드는 마이그레이션 파일에서 함께 작성하고, 코드값이 있으면 값의 의미도 적는다.
- 모든 테이블에 `created_at`, 수정되는 테이블에는 `updated_at`을 둔다. 실행 기록 테이블(`collection_run`)은 `started_at`·`finished_at`이 그 역할을 한다.
- 외부에서 받은 문자열은 컬럼 길이에 맞춰 잘라 저장한다. 형식이 틀린 한 건은 건너뛰고 경고 로그를 남긴다.
- 응답에 엔티티를 그대로 내보내지 않는다. DTO(record)로 바꾼다.

## 4. 이름

| 대상 | 규칙 | 예 |
|---|---|---|
| 테이블 | 단수 명사, snake_case | `company`, `collection_run` |
| 컬럼 | snake_case | `rcept_date`, `last_disclosed_on` |
| 기본 키 | 자연키가 있으면 그 이름, 없으면 `id` (bigint identity) | `company.corp_code`, `disclosure.id` |
| 외부에서 온 값 | 원본 필드명을 따르되 줄임말은 풀어 쓴다 | `rcept_no`, `rcept_dt` → `rcept_date` |
| 시각 컬럼 | `_at` | `created_at`, `started_at` |
| 날짜 컬럼 (직접 만든 값) | `_on` | `last_disclosed_on` |
| 참·거짓 컬럼 | `is_`, `has_` | `is_verified` |
| 상태·분류 컬럼 | `varchar` + 대문자 코드값 | `status = 'FAILED'` |
| 인덱스 | `{테이블}_{컬럼}_idx` | `disclosure_corp_date_idx` |
| 마이그레이션 | `V{번호}__{설명}.sql`. 적용된 파일은 수정하지 않고 새 버전을 추가 | `V4__collection_run_error_kind.sql` |
| API 경로 | `/api/{복수 명사}`, kebab-case | `/api/companies` |
| 화면 경로 | `/{복수 명사}/{식별자}` | `/companies/005930` |
| JSON 필드 | camelCase | `stockCode` |
| DTO | record. `XxxRequest`, `XxxResponse`, 목록 항목은 `XxxSummary` | `CompanySummary` |
| 설정 키 | `app.{기능}.{항목}`, kebab-case | `app.dart.api-key` |
| 상수 | UPPER_SNAKE_CASE | `MAX_PAGE_COUNT` |
| 테스트 메서드 | 한글로 동작을 설명, 단어는 `_`로 연결 | `같은_공시가_다시_와도_한_번만_저장된다` |

## 5. API 응답

- 성공: JSON 본문. 목록은 배열 또는 페이지 객체.
- API 문서는 springdoc-openapi가 자동 생성한다. 컨트롤러에 문서용 애너테이션을 쌓지 않는다. 운영에서는 끈다(`API_DOCS_ENABLED`).
- 오류: RFC 9457 Problem Details (`application/problem+json`). Spring `ProblemDetail`을 쓴다.

| 상황 | 상태 코드 |
|---|---|
| 파라미터 검증 실패 | 400 |
| 대상 없음 | 404 |
| 서버 오류 | 500 (내부 메시지·스택은 응답에 넣지 않음) |

- 외부 API 오류를 사용자 응답으로 그대로 내보내지 않는다. 저장된 데이터로 응답하고, 실패는 수집 기록과 운영 알림으로 다룬다.

## 6. 외부 API 오류 처리

### 분류

외부 API 클라이언트는 응답을 아래 분류(`ErrorKind`)로 바꿔 `ExternalApiException`으로 던진다. 다시 시도할 수 있는 분류는 `RetryableExternalApiException`이다.

| 분류 | 의미 | 행동 |
|---|---|---|
| (데이터 없음) | 결과 0건 | 예외 없이 빈 결과 |
| `TRANSIENT` | 네트워크, 타임아웃, 5xx, 점검 | 지수 백오프로 최대 2번 재시도, 실패하면 다음 주기에 다시 |
| `RATE_LIMITED` | 짧은 시간 과다 호출 | 재시도, 속도 제한 설정 점검 |
| `QUOTA_EXCEEDED` | 하루 한도 소진 | 해당 소스를 자정(KST)까지 정지 |
| `AUTH` | 키 없음·틀림·만료·IP 차단 | 해당 소스를 1시간 정지 |
| `BAD_REQUEST` | 요청 파라미터 오류 | 재시도 안 함 |
| `PARSE` | 응답 형식 해석 실패 | 재시도 안 함 |
| `UNKNOWN` | 그 외 | 재시도 안 함 |

### 소스별 매핑

| 분류 | DART (HTTP 200 + `status`) | 네이버 검색 API | Gemini API |
|---|---|---|---|
| 데이터 없음 | `013`, `014` | 빈 `items` | - |
| `TRANSIENT` | `800`, `900`, 네트워크, 5xx | 500, 네트워크 | 500, 503, 504 |
| `RATE_LIMITED` | 429 | 429 (초당) | 429 (분당) |
| `QUOTA_EXCEEDED` | `020`, `021` | 429 (일 한도) | 429 (일 한도) |
| `AUTH` | `010`, `011`, `012`, `901`, 401, 403 | 401, 403 | 401, 403 |
| `BAD_REQUEST` | `100`, `101`, 그 외 4xx | 400 | 400 |

DART는 오류도 HTTP 200으로 준다. HTTP 상태만 보지 않고 본문(`status`, zip 여부)을 확인한다.

### 공통 규칙

- 모든 외부 호출에 연결·응답 타임아웃을 둔다.
- 재시도는 `TRANSIENT`, `RATE_LIMITED`만 한다.
- 호출 속도는 소스별 `RateLimiter`로 제한한다.
- 정지된 소스는 호출하지 않고 건너뛴다(`SourcePauseRegistry`). 정지는 메모리에만 두며 재시작하면 풀린다.
- 예외 메시지와 로그에 요청 URL, 헤더, 인증키를 넣지 않는다.
- 한 소스가 실패해도 다른 소스 수집은 계속한다. 한 건 실패가 배치 전체를 멈추지 않는다.
- 정기 작업은 `ExclusiveJobRunner`로 실행해 같은 그룹의 작업이 겹치지 않게 한다.
- 수집 작업은 `CollectionRunRecorder.record()` 안에서 실행한다. 기록과 소스 정지를 수집기마다 따로 구현하지 않는다.
- 수집 실행 결과는 `collection_run`에 남긴다. 실패하면 `error_kind`(분류 또는 `INTERNAL`)와 `error_code`(원래 코드)를 함께 남긴다.

## 7. 시간

- 시각은 `timestamptz`, 날짜만 필요하면 `date`.
- "오늘"은 `Clock` 빈(Asia/Seoul)으로 계산한다. `LocalDate.now()`를 직접 부르지 않는다.
- 스케줄 cron에는 `zone = "Asia/Seoul"`을 명시한다.

## 8. 테스트

| 대상 | 방식 |
|---|---|
| 순수 로직 (파서, 점수 계산, 분류 규칙) | 단위 테스트 |
| 외부 API 클라이언트 | `MockRestServiceServer`로 응답 형태별 검증 |
| DB 쿼리, 저장, 작업 큐 | Testcontainers(PostgreSQL 18) 통합 테스트 |
| 서비스 + 외부 API | 클라이언트를 `@MockitoBean`으로 대체 |

- 테스트에서는 실제 외부 API를 호출하지 않는다. 스케줄과 시작 시 동기화는 `src/test/resources/config/application.properties`에서 끈다.

## 9. 화면

- Thymeleaf로 서버에서 HTML을 만든다. 검색 엔진과 링크 미리보기가 읽을 내용(판단, 요약, 공시 목록)은 HTML 텍스트로 넣는다.
- 그래프는 Chart.js. 외부 CDN을 쓰지 않고 `src/main/resources/static/vendor/`에 버전을 고정해 포함한다.
- 프런트 빌드 단계 없이 정적 파일로 서빙한다. 템플릿은 `templates/{기능}/`, 공통 스타일은 `static/css/app.css`.
- 코드값의 화면 이름은 enum의 `label()`에 둔다. 숫자 코드(영향도 등)는 그대로 내보이지 않고 말로 바꿔 보여준다.
- 화면 안의 링크 주소에 들어가는 사용자 입력(검색어)은 인코딩한다.

## 10. 코드 스타일

- 들여쓰기는 탭. `.editorconfig`를 따른다.
- DTO와 설정은 record. Lombok은 JPA 엔티티에만 필요한 만큼 쓴다.

### 주석

- 주석은 한국어로 쓴다.
- 모든 클래스(record, enum, interface 포함)에 Javadoc으로 무엇을 하는지, 왜 필요한지 적는다.
- `public` 메서드에 Javadoc을 단다. 무엇을 하는지, 이름만으로 알 수 없는 파라미터·반환값의 의미, 예외가 나는 조건. 단순 접근자와 생성자는 생략할 수 있다.
- `private` 메서드와 상수는 이름으로 부족할 때 단다.
- 코드 안쪽 주석은 "왜 이렇게 했는가"를 적는다. 외부 API의 특이한 동작, 이 방식을 고른 이유, 지켜야 할 조건. 코드를 그대로 풀어 쓴 주석은 달지 않는다.
- record는 `@param`으로 각 구성 요소를, enum은 상수마다 의미를 적는다.
- 로그와 예외 메시지에 비밀 값, 개인정보를 넣지 않는다.
