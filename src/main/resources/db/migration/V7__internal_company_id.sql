-- 회사와 공시의 기준 키를 특정 출처(DART)에 묶이지 않게 바꾼다.
-- 회사: 내부 번호(id)가 기준 키. 나라, 출처, 그 출처에서의 번호를 따로 저장하고 "출처 + 번호"를 unique로 둔다.
-- 공시: 회사를 내부 번호로 참조하고, 중복 방지를 "출처 + 그 출처의 공시 번호" unique로 한다.
-- 기존 행은 모두 한국(KR), DART 출처로 옮긴다.

-- 1. company: DART 고유번호(corp_code) 기본 키 → 내부 번호
ALTER TABLE disclosure DROP CONSTRAINT disclosure_corp_code_fkey;
ALTER TABLE company DROP CONSTRAINT company_pkey;
ALTER TABLE company RENAME COLUMN corp_code TO source_key;
-- PostgreSQL 18은 NOT NULL도 이름 있는 제약이다. 컬럼 이름을 바꿔도 제약 이름은 그대로라 함께 바꾼다
ALTER TABLE company RENAME CONSTRAINT company_corp_code_not_null TO company_source_key_not_null;

-- 기본값은 기존 행을 채우는 데만 쓰고 바로 없앤다. 이후에는 저장하는 쪽이 값을 반드시 준다
ALTER TABLE company
    ADD COLUMN id      BIGINT GENERATED ALWAYS AS IDENTITY,
    ADD COLUMN country VARCHAR(2)  NOT NULL DEFAULT 'KR',
    ADD COLUMN source  VARCHAR(20) NOT NULL DEFAULT 'DART',
    -- 다른 출처의 회사 번호와 종목코드(티커)는 DART보다 길 수 있다
    ALTER COLUMN source_key TYPE VARCHAR(20),
    ALTER COLUMN source_key SET NOT NULL,
    ALTER COLUMN stock_code TYPE VARCHAR(20);
ALTER TABLE company
    ALTER COLUMN country DROP DEFAULT,
    ALTER COLUMN source DROP DEFAULT,
    ADD PRIMARY KEY (id),
    ADD CONSTRAINT company_source_key_uq UNIQUE (source, source_key);

-- 2. disclosure: 회사 참조를 내부 번호로, 접수번호 unique → (출처, 출처의 공시 번호) unique
ALTER TABLE disclosure
    ADD COLUMN company_id BIGINT,
    ADD COLUMN source     VARCHAR(20) NOT NULL DEFAULT 'DART';

UPDATE disclosure d
   SET company_id = c.id
  FROM company c
 WHERE c.source = 'DART'
   AND c.source_key = d.corp_code;

DROP INDEX disclosure_corp_date_idx;
DROP INDEX disclosure_date_idx;

ALTER TABLE disclosure
    -- 회사를 찾지 못한 공시가 하나라도 있으면 여기서 실패해 마이그레이션 전체가 되돌려진다
    ALTER COLUMN company_id SET NOT NULL,
    ALTER COLUMN source DROP DEFAULT,
    ADD CONSTRAINT disclosure_company_id_fkey FOREIGN KEY (company_id) REFERENCES company (id),
    DROP COLUMN corp_code,
    DROP CONSTRAINT disclosure_rcept_no_key;

ALTER TABLE disclosure RENAME COLUMN rcept_no TO source_key;
ALTER TABLE disclosure RENAME CONSTRAINT disclosure_rcept_no_not_null TO disclosure_source_key_not_null;

ALTER TABLE disclosure
    -- 다른 출처의 공시 번호는 DART 접수번호(14자리)보다 길 수 있다
    ALTER COLUMN source_key TYPE VARCHAR(40),
    ADD CONSTRAINT disclosure_source_key_uq UNIQUE (source, source_key);

-- 회사별 최신 공시(타임라인), 날짜별 전체 공시
CREATE INDEX disclosure_company_date_idx ON disclosure (company_id, rcept_date DESC, source_key DESC);
CREATE INDEX disclosure_date_idx ON disclosure (rcept_date DESC, source_key DESC);

COMMENT ON TABLE company IS '상장사. 출처(DART 등)의 기업 목록과 공시에서 채운다';
COMMENT ON COLUMN company.id IS '회사 ID. 다른 테이블은 이 번호로 회사를 참조한다';
COMMENT ON COLUMN company.country IS '상장한 나라. ISO 3166-1 두 글자 코드. KR 한국';
COMMENT ON COLUMN company.source IS '회사 정보를 받은 출처. DART 금융감독원 전자공시시스템. 값은 코드(Source)가 정하고 출처를 추가해도 스키마를 고치지 않는다';
COMMENT ON COLUMN company.source_key IS '출처에서 회사를 가리키는 번호. DART는 고유번호(corp_code, 8자리). 출처와 묶어 유일하다';
COMMENT ON COLUMN company.stock_code IS '종목코드. 한국은 6자리';

COMMENT ON TABLE disclosure IS '상장사 공시 목록. 출처(DART 등)의 공시검색에서 수집한다';
COMMENT ON COLUMN disclosure.company_id IS '공시 대상 회사 (company.id)';
COMMENT ON COLUMN disclosure.source IS '공시를 받은 출처. DART 금융감독원 전자공시시스템';
COMMENT ON COLUMN disclosure.source_key IS '출처에서 공시를 가리키는 번호. DART는 접수번호(rcept_no, 14자리). 출처와 묶어 유일해서 같은 공시는 한 번만 저장된다';
