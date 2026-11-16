-- 정기보고서(분기·반기·사업보고서)의 주요 재무 계정 금액.
-- 보고서 한 건의 계정 하나가 한 행이다. 계정을 추가해도 컬럼을 늘리지 않는다.
-- 출처가 준 값을 그대로 저장하고, 4분기 금액이나 전년 동기 대비 증감 같은 계산은 조회할 때 코드가 한다.
CREATE TABLE financial (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id              BIGINT        NOT NULL REFERENCES company (id),
    fiscal_year             INT           NOT NULL,
    report_type             VARCHAR(10)   NOT NULL,
    statement_type          VARCHAR(15)   NOT NULL,
    account                 VARCHAR(30)   NOT NULL,
    -- 원 단위가 아닌 통화(소수 단위가 있는 통화)도 담을 수 있게 소수 둘째 자리까지 둔다
    amount                  NUMERIC(24, 2) NOT NULL,
    cumulative_amount       NUMERIC(24, 2),
    prior_amount            NUMERIC(24, 2),
    prior_cumulative_amount NUMERIC(24, 2),
    currency                VARCHAR(3)    NOT NULL,
    source                  VARCHAR(20)   NOT NULL,
    source_key              VARCHAR(40)   NOT NULL,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- 같은 보고서의 같은 계정은 한 번만 저장한다. 다시 받으면 값이 바뀐 행만 갱신한다
    CONSTRAINT financial_report_account_uq UNIQUE (company_id, fiscal_year, report_type, statement_type, account)
);

COMMENT ON TABLE financial IS '정기보고서의 주요 재무 계정 금액. 보고서 한 건의 계정 하나가 한 행';
COMMENT ON COLUMN financial.id IS '재무 행 ID';
COMMENT ON COLUMN financial.company_id IS '회사 (company.id)';
COMMENT ON COLUMN financial.fiscal_year IS '사업연도. 결산월이 12월이 아닌 회사는 달력의 연도와 다를 수 있다';
COMMENT ON COLUMN financial.report_type IS '보고서 종류. Q1 1분기보고서, HALF 반기보고서, Q3 3분기보고서, ANNUAL 사업보고서';
COMMENT ON COLUMN financial.statement_type IS '재무제표 구분. CONSOLIDATED 연결재무제표, SEPARATE 별도(개별)재무제표';
COMMENT ON COLUMN financial.account IS '계정. REVENUE 매출액, OPERATING_INCOME 영업이익, NET_INCOME 당기순이익';
COMMENT ON COLUMN financial.amount IS '당기 금액. 분기·반기보고서는 그 분기 3개월, 사업보고서는 1년 전체';
COMMENT ON COLUMN financial.cumulative_amount IS '당기 누적 금액(사업연도 시작부터 보고서 기준일까지). 사업보고서에는 없다';
COMMENT ON COLUMN financial.prior_amount IS '같은 보고서에 실린 전년 같은 기간의 금액. 분기·반기보고서는 전년 같은 분기 3개월, 사업보고서는 전년 1년 전체';
COMMENT ON COLUMN financial.prior_cumulative_amount IS '같은 보고서에 실린 전년 같은 기간의 누적 금액. 사업보고서에는 없다';
COMMENT ON COLUMN financial.currency IS '통화. ISO 4217 세 글자 코드. KRW 원';
COMMENT ON COLUMN financial.source IS '금액을 받은 출처. DART 금융감독원 전자공시시스템';
COMMENT ON COLUMN financial.source_key IS '금액의 근거가 된 보고서를 출처에서 가리키는 번호. DART는 접수번호';
COMMENT ON COLUMN financial.created_at IS '처음 저장한 시각';
COMMENT ON COLUMN financial.updated_at IS '마지막으로 값이 바뀐 시각';
