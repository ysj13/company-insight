-- 재무 테이블을 "출처가 준 응답을 그대로 저장"하는 구조로 바꾼다.
-- 지금까지는 응답에서 계정 몇 개의 금액만 골라 계정 하나를 한 행으로 저장했다(V8).
-- 이제 보고서 한 건의 응답 하나를 한 행으로 저장하고, 필요한 계정과 금액은 조회할 때 응답에서 읽는다.
-- 계정을 추가하거나 읽는 규칙을 고쳐도 출처를 다시 부르지 않는다.
-- 기존 행은 옮기지 않는다. 응답 전체가 없어 새 구조로 바꿀 수 없으므로 다시 수집한다.
DROP TABLE financial;

CREATE TABLE financial (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id  BIGINT      NOT NULL REFERENCES company (id),
    source      VARCHAR(20) NOT NULL,
    fiscal_year INT         NOT NULL,
    report_type VARCHAR(10) NOT NULL,
    payload     JSONB       NOT NULL,
    fetched_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 같은 보고서의 응답은 한 번만 저장한다. 다시 받으면 내용이 바뀐 경우에만 수정 시각을 바꾼다
    CONSTRAINT financial_report_uq UNIQUE (company_id, source, fiscal_year, report_type)
);

COMMENT ON TABLE financial IS '정기보고서의 재무 응답. 출처가 준 응답을 보고서 한 건 단위로 그대로 저장한다. 계정과 금액은 조회할 때 여기서 읽는다';
COMMENT ON COLUMN financial.id IS '재무 행 ID';
COMMENT ON COLUMN financial.company_id IS '회사 (company.id)';
COMMENT ON COLUMN financial.source IS '응답을 받은 출처. DART 금융감독원 전자공시시스템(단일회사 주요계정)';
COMMENT ON COLUMN financial.fiscal_year IS '조회한 사업연도. 결산월이 12월이 아닌 회사는 달력의 연도와 다를 수 있다';
COMMENT ON COLUMN financial.report_type IS '조회한 보고서 종류. Q1 1분기보고서, HALF 반기보고서, Q3 3분기보고서, ANNUAL 사업보고서';
COMMENT ON COLUMN financial.payload IS '출처가 준 응답 전체(JSON). 화면에 쓰지 않는 계정과 필드도 들어 있다. JSON 값으로 저장해 공백과 키 순서는 원문과 다를 수 있다';
COMMENT ON COLUMN financial.fetched_at IS '출처에서 마지막으로 받은 시각. 내용이 같아도 받을 때마다 바뀐다';
COMMENT ON COLUMN financial.created_at IS '처음 저장한 시각';
COMMENT ON COLUMN financial.updated_at IS '응답 내용이 마지막으로 바뀐 시각';
