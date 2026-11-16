-- 출처에서 받은 재무 응답의 원본. 보고서 한 건의 응답 하나가 한 행이다.
-- financial 테이블의 값은 여기서 뽑아 만든다. 뽑는 규칙을 고치거나 계정을 추가할 때
-- 출처를 다시 부르지 않고 이 원본에서 다시 뽑는다.
CREATE TABLE financial_raw (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id  BIGINT      NOT NULL REFERENCES company (id),
    source      VARCHAR(20) NOT NULL,
    fiscal_year INT         NOT NULL,
    report_type VARCHAR(10) NOT NULL,
    payload     JSONB       NOT NULL,
    fetched_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 같은 보고서의 응답은 한 번만 저장한다. 다시 받으면 내용이 바뀐 경우에만 갱신한다
    CONSTRAINT financial_raw_report_uq UNIQUE (company_id, source, fiscal_year, report_type)
);

COMMENT ON TABLE financial_raw IS '출처에서 받은 재무 응답의 원본. 보고서 한 건의 응답 하나가 한 행. financial의 값은 여기서 뽑는다';
COMMENT ON COLUMN financial_raw.id IS '원본 행 ID';
COMMENT ON COLUMN financial_raw.company_id IS '회사 (company.id)';
COMMENT ON COLUMN financial_raw.source IS '응답을 받은 출처. DART 금융감독원 전자공시시스템(단일회사 주요계정)';
COMMENT ON COLUMN financial_raw.fiscal_year IS '조회한 사업연도';
COMMENT ON COLUMN financial_raw.report_type IS '조회한 보고서 종류. Q1 1분기보고서, HALF 반기보고서, Q3 3분기보고서, ANNUAL 사업보고서';
COMMENT ON COLUMN financial_raw.payload IS '출처가 준 응답 전체(JSON). 저장하지 않는 계정과 필드도 그대로 들어 있다';
COMMENT ON COLUMN financial_raw.fetched_at IS '출처에서 마지막으로 받은 시각. 내용이 같아도 받을 때마다 바뀐다';
COMMENT ON COLUMN financial_raw.created_at IS '처음 저장한 시각';
COMMENT ON COLUMN financial_raw.updated_at IS '응답 내용이 마지막으로 바뀐 시각';
