-- 테이블과 컬럼 설명. 이후 마이그레이션은 테이블·컬럼을 만들 때 같은 파일에서 COMMENT를 함께 단다.

COMMENT ON TABLE company IS '상장사. DART 고유번호 파일과 공시에서 채운다';
COMMENT ON COLUMN company.corp_code IS 'DART 고유번호 (8자리)';
COMMENT ON COLUMN company.stock_code IS '종목코드 (6자리)';
COMMENT ON COLUMN company.name IS '회사명';
COMMENT ON COLUMN company.market IS '시장 구분. Y 유가증권, K 코스닥, N 코넥스. 공시에서 확인되기 전에는 NULL';
COMMENT ON COLUMN company.modify_date IS 'DART 기업 정보 최종 변경일';
COMMENT ON COLUMN company.last_disclosed_on IS '마지막 공시 접수일';
COMMENT ON COLUMN company.created_at IS '행 생성 시각';
COMMENT ON COLUMN company.updated_at IS '행 수정 시각';

COMMENT ON TABLE disclosure IS '상장사 공시 목록. DART 공시검색에서 수집한다';
COMMENT ON COLUMN disclosure.id IS '공시 ID';
COMMENT ON COLUMN disclosure.rcept_no IS 'DART 접수번호 (14자리). 공시 원문 식별자';
COMMENT ON COLUMN disclosure.corp_code IS '공시 대상 회사의 DART 고유번호';
COMMENT ON COLUMN disclosure.report_name IS '보고서명 (공시 제목)';
COMMENT ON COLUMN disclosure.filer_name IS '공시 제출인';
COMMENT ON COLUMN disclosure.rcept_date IS '접수일';
COMMENT ON COLUMN disclosure.remark IS 'DART 비고. 유 유가증권시장본부 소관, 코 코스닥시장본부 소관, 정 정정 있음 등';
COMMENT ON COLUMN disclosure.created_at IS '행 생성 시각 (수집 시각)';

COMMENT ON TABLE collection_run IS '수집 작업 실행 기록';
COMMENT ON COLUMN collection_run.id IS '실행 ID';
COMMENT ON COLUMN collection_run.source IS '작업 이름. 예: DART_DISCLOSURE_POLL, DART_DISCLOSURE_RECONCILE, DART_COMPANY_SYNC';
COMMENT ON COLUMN collection_run.started_at IS '시작 시각';
COMMENT ON COLUMN collection_run.finished_at IS '종료 시각. 실행 중이거나 중단됐으면 NULL';
COMMENT ON COLUMN collection_run.status IS '상태. RUNNING, SUCCESS, FAILED';
COMMENT ON COLUMN collection_run.fetched IS '외부에서 받은 건수';
COMMENT ON COLUMN collection_run.saved IS '새로 저장하거나 변경한 건수';
COMMENT ON COLUMN collection_run.pages IS '읽은 페이지 수';
COMMENT ON COLUMN collection_run.error IS '오류 메시지 (최대 500자)';
COMMENT ON COLUMN collection_run.error_kind IS '오류 분류. 외부 API 오류는 TRANSIENT, RATE_LIMITED, QUOTA_EXCEEDED, AUTH, BAD_REQUEST, PARSE, UNKNOWN. 내부 오류는 INTERNAL';
COMMENT ON COLUMN collection_run.error_code IS '외부 API가 돌려준 원래 오류 코드. 예: DART 020';
