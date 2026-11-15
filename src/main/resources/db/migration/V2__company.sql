-- 상장사 목록. DART 고유번호(corp_code)가 기준 키, 종목코드는 화면·검색용.
CREATE TABLE company (
    corp_code    VARCHAR(8)   PRIMARY KEY,
    stock_code   VARCHAR(6)   NOT NULL,
    name         VARCHAR(200) NOT NULL,
    market       VARCHAR(10),
    modify_date  DATE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 종목코드로 찾기. 상장폐지 후 재사용된 코드가 있을 수 있어 unique로 두지 않는다.
CREATE INDEX company_stock_code_idx ON company (stock_code);

-- 기업명 부분 검색 ("삼성" → 삼성전자, 삼성SDI). ILIKE '%검색어%'에 인덱스를 쓴다.
CREATE INDEX company_name_trgm_idx ON company USING gin (name gin_trgm_ops);
