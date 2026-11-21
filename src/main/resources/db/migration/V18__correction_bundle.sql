-- 정정 공시의 묶음 잇기.
-- 본문을 고친 정정 공시([기재정정])는 새 접수번호로 오고 원래 공시를 가리키는 값이 없다. 본문의 정정 표에 고친 공시의
-- 제출일이 있다(거래소 서식은 바로 앞 판의 제출일, 금융위원회 서식은 원래 공시의 최초 제출일). 그날 낸 같은 제목의 공시를
-- 앞 공시로 이어 사슬을 만들고, 한 사슬(묶음)에서는 가장 늦게 낸 공시 하나만 회사 판단에 센다. 그 날짜는 묶음의 최초 제출일이다.
-- 앞선 공시의 판정은 지우지 않고 대체한 공시만 적는다.

ALTER TABLE disclosure
    ADD COLUMN corrected_filed_on DATE,
    ADD COLUMN corrects_id        BIGINT REFERENCES disclosure (id),
    ADD COLUMN correction_link    VARCHAR(20),
    ADD COLUMN bundle_id          BIGINT REFERENCES disclosure (id),
    ADD COLUMN superseded_by      BIGINT REFERENCES disclosure (id),
    ADD COLUMN first_filed_on     DATE,
    ADD CONSTRAINT disclosure_correction_link_check
        CHECK (correction_link IN ('LINKED', 'NO_PREDECESSOR', 'AMBIGUOUS', 'NO_DATE')),
    -- 앞 공시는 이은 정정에만 있다
    ADD CONSTRAINT disclosure_corrects_link_check CHECK ((corrects_id IS NOT NULL) = COALESCE(correction_link = 'LINKED', false)),
    -- 대체된 공시는 묶음에 들어 있다
    ADD CONSTRAINT disclosure_superseded_bundle_check CHECK (superseded_by IS NULL OR bundle_id IS NOT NULL);

COMMENT ON COLUMN disclosure.corrected_filed_on IS '정정 표에서 읽은, 이 정정이 고친 공시의 제출일. 거래소 서식은 바로 앞 판(원래 공시나 앞선 정정)의 제출일, 금융위원회 서식은 원래 공시의 최초 제출일이다. 정정 공시가 아니거나 읽지 못했으면 NULL';
COMMENT ON COLUMN disclosure.corrects_id IS '이 정정이 고친 앞 공시(원래 공시나 앞선 정정)의 id. 찾지 못했으면 NULL';
COMMENT ON COLUMN disclosure.correction_link IS '정정 공시의 앞 공시를 찾은 결과. LINKED 찾음, NO_PREDECESSOR 고친 날에 저장된 앞 공시가 없음(원래 공시가 수집 기간 밖 등), AMBIGUOUS 앞 공시 후보가 여러 사건이라 고르지 못함, NO_DATE 고친 공시의 제출일을 읽지 못함. 정정 공시가 아니거나 아직 읽지 않았으면 NULL';
COMMENT ON COLUMN disclosure.bundle_id IS '정정 묶음 번호. 묶음의 첫 공시(원래 공시, 없으면 맨 앞 정정)의 id. 묶이지 않았으면 NULL';
COMMENT ON COLUMN disclosure.superseded_by IS '이 공시를 대체한 공시(묶음에서 가장 늦게 낸 공시)의 id. 회사 판단에는 대체되지 않은 공시만 센다. 묶음의 마지막이거나 묶이지 않았으면 NULL';
COMMENT ON COLUMN disclosure.first_filed_on IS '회사 판단에 쓰는 최초 제출일. 묶음의 구성원과 정정 공시에만 있다: 원래 공시가 묶음에 있으면 그 접수일, 없으면 맨 앞 정정이 고친 공시의 제출일. NULL이면 접수일을 쓴다';

-- 앞 공시는 회사·접수일 색인(disclosure_company_date_idx)으로 찾는다. 묶음은 앞 공시를 거슬러 올라가고 내려가며 찾는다
CREATE INDEX disclosure_corrects_idx ON disclosure (corrects_id) WHERE corrects_id IS NOT NULL;

-- 회사 판단(호재·악재 점수)에 세는 공시. 판단 계산은 이 뷰만 읽는다.
-- 1) 묶음에서 대체된 공시는 세지 않는다 (가장 늦게 낸 공시 하나만 센다)
-- 2) 첨부만 고치거나 더한 공시([첨부정정], [첨부추가], [발행조건확정])는 기록만 하고 세지 않는다
-- 3) 앞 공시를 고르지 못했거나(AMBIGUOUS) 고친 날을 읽지 못한(NO_DATE) 정정은 세지 않는다. 앞 공시 후보(원래 공시)를 센다
-- 날짜는 묶음의 최초 제출일이다(정정일로 옮기지 않는다). 없으면 접수일.
CREATE VIEW disclosure_counted AS
SELECT d.id, d.company_id, d.source, d.source_key, d.report_name, d.rcept_date,
       COALESCE(d.first_filed_on, d.rcept_date) AS event_date,
       d.type_code, d.tone, d.impact, d.impact_ratio, d.classified_by, d.bundle_id
  FROM disclosure d
 WHERE d.superseded_by IS NULL
   AND COALESCE(d.correction_link, '') NOT IN ('AMBIGUOUS', 'NO_DATE')
   AND COALESCE(substring(regexp_replace(d.report_name, '[\s\u00a0\u3000]+', '', 'g') from '^((\[[^]]*\])+)'), '')
       !~ '\[(첨부정정|첨부추가|발행조건확정)\]';

COMMENT ON VIEW disclosure_counted IS '회사 판단에 세는 공시. 정정 묶음에서 가장 늦게 낸 공시 하나만, 첨부만 고친 공시와 잇지 못한 정정은 빼고. event_date는 회사 판단에 쓰는 날(묶음의 최초 제출일, 없으면 접수일)';
