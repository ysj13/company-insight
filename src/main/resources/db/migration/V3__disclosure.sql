-- 공시 목록. 접수번호(rcept_no)가 DART의 공시 식별자라 unique로 둬서 같은 공시는 한 번만 저장된다.
CREATE TABLE disclosure (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rcept_no     VARCHAR(14)  NOT NULL UNIQUE,
    corp_code    VARCHAR(8)   NOT NULL REFERENCES company (corp_code),
    report_name  VARCHAR(300) NOT NULL,
    filer_name   VARCHAR(200),
    rcept_date   DATE         NOT NULL,
    remark       VARCHAR(20),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 회사별 최신 공시(타임라인), 날짜별 전체 공시(홈 피드)
CREATE INDEX disclosure_corp_date_idx ON disclosure (corp_code, rcept_date DESC, rcept_no DESC);
CREATE INDEX disclosure_date_idx ON disclosure (rcept_date DESC, rcept_no DESC);

-- 공시에서 알게 되는 현재 시장 구분과 마지막 공시일. 상장폐지 기업 구분에 쓴다.
ALTER TABLE company ADD COLUMN last_disclosed_on DATE;

-- 수집 실행 기록. 수집이 멈추거나 실패가 쌓이는 것을 운영 중에 확인하기 위해 남긴다.
CREATE TABLE collection_run (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source       VARCHAR(40)  NOT NULL,
    started_at   TIMESTAMPTZ  NOT NULL,
    finished_at  TIMESTAMPTZ,
    status       VARCHAR(10)  NOT NULL,
    fetched      INT          NOT NULL DEFAULT 0,
    saved        INT          NOT NULL DEFAULT 0,
    pages        INT          NOT NULL DEFAULT 0,
    error        VARCHAR(500)
);

CREATE INDEX collection_run_source_started_idx ON collection_run (source, started_at DESC);
