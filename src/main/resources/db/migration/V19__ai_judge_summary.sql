-- AI 판정·요약 단계가 남기는 것.
-- 1) 설정 묶음의 버전. 앱이 뜰 때 묶음(지금은 AI 판정·AI 요약의 모델 + 지시문)의 값 전체로 해시를 내고,
--    그 묶음의 최신 줄과 다르면 번호를 하나 올린 줄을 더한다. 사람이 번호를 올리지 않는다.
-- 2) 공시 한 건의 지금 요약(쉬운 말 한 줄 제목 + 요약 문장). 원문과 대조를 통과한 것만 들어온다.
-- 3) 포괄 제목·꼬리 미상·미분류 공시에 AI가 고른 유형. 대응표가 붙인 type_code는 그대로 둔다.

CREATE TABLE setting_version (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bundle        VARCHAR(20) NOT NULL,
    version       INT         NOT NULL,
    hash          VARCHAR(64) NOT NULL,
    settings      JSONB       NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT setting_version_version_uq UNIQUE (bundle, version),
    CONSTRAINT setting_version_hash_uq UNIQUE (bundle, hash),
    CONSTRAINT setting_version_version_check CHECK (version >= 1)
);

COMMENT ON TABLE setting_version IS '판정 결과를 바꾸는 설정 묶음의 버전. 앱이 뜰 때 값 전체의 해시가 그 묶음의 기존 줄과 다르면 번호를 하나 올린 줄을 더한다. 번호는 DB마다 따로 매겨진다(값이 함께 남아 문제가 되지 않는다)';
COMMENT ON COLUMN setting_version.id IS '설정 버전 ID. 실행 기록(analysis_run.setting_version_id)이 가리킨다';
COMMENT ON COLUMN setting_version.bundle IS '설정 묶음. AI_JUDGE AI 판정의 모델·지시문, AI_SUMMARY AI 요약의 모델·지시문. 묶음마다 번호를 따로 매긴다';
COMMENT ON COLUMN setting_version.version IS '묶음 안의 번호. 1부터';
COMMENT ON COLUMN setting_version.hash IS '값 전체(settings)를 정해진 순서로 적은 글의 SHA-256 (16진수)';
COMMENT ON COLUMN setting_version.settings IS '그 번호의 값 전체 (모델 이름, 지시문 글, 응답 모양 등). "몇 번은 무슨 값이었나"를 SQL로 본다';
COMMENT ON COLUMN setting_version.first_seen_at IS '이 값을 처음 본 시각 (앱이 뜬 시각)';

-- 같은 해시가 다시 오면(값을 되돌린 경우) 새 번호를 만들지 않고 그 줄을 다시 쓴다. 그래서 (묶음, 해시)도 unique다

ALTER TABLE analysis_run
    ADD CONSTRAINT analysis_run_setting_version_fk FOREIGN KEY (setting_version_id) REFERENCES setting_version (id);

CREATE TABLE disclosure_summary (
    disclosure_id BIGINT      PRIMARY KEY REFERENCES disclosure (id),
    headline      TEXT,
    lines         TEXT[]      NOT NULL,
    written_by    VARCHAR(10) NOT NULL,
    run_id        BIGINT      REFERENCES analysis_run (id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT disclosure_summary_written_by_check CHECK (written_by IN ('AI', 'CODE')),
    CONSTRAINT disclosure_summary_lines_check CHECK (cardinality(lines) >= 1),
    -- AI가 쓴 요약은 어느 실행이 썼는지(모델·지시문 버전, 대조 결과)를 따라갈 수 있어야 한다
    CONSTRAINT disclosure_summary_run_check CHECK (written_by <> 'AI' OR run_id IS NOT NULL)
);

COMMENT ON TABLE disclosure_summary IS '공시 한 건의 지금 요약. 호재·악재로 판정된 공시에만 있다(중립 공시는 요약하지 않는다). 원문과 숫자·날짜 대조를 통과한 것만 들어오고, 대조에 실패한 답은 실행 기록(analysis_run.result)에만 남는다. 다시 쓰면 덮어쓴다';
COMMENT ON COLUMN disclosure_summary.disclosure_id IS '공시 (disclosure.id)';
COMMENT ON COLUMN disclosure_summary.headline IS '공시 패널 맨 위의 "무슨 일인지 쉬운 말 한 줄". 없으면 화면은 공시 이름(report_name)을 쓴다';
COMMENT ON COLUMN disclosure_summary.lines IS '요약 문장. 줄 수는 정하지 않고 본문에 있는 중요한 내용만 쓴다 (원문 복제를 막는 상한은 코드에 있다). 제목과 같은 말을 되풀이하지 않는다';
COMMENT ON COLUMN disclosure_summary.written_by IS '쓴 주체. AI, CODE(코드가 읽은 항목으로 씀)';
COMMENT ON COLUMN disclosure_summary.run_id IS '이 요약을 쓴 실행 (analysis_run.id). 모델·지시문 버전과 대조 결과는 거기서 읽는다';
COMMENT ON COLUMN disclosure_summary.created_at IS '행 생성 시각';
COMMENT ON COLUMN disclosure_summary.updated_at IS '마지막으로 다시 쓴 시각';

ALTER TABLE disclosure
    ADD COLUMN picked_type_code VARCHAR(4) REFERENCES disclosure_type (code);

COMMENT ON COLUMN disclosure.picked_type_code IS 'AI가 본문을 읽고 고른 유형 (disclosure_type.code). 포괄 제목(K1)·꼬리 미상(K2)·미분류(Z9)에만 있고, 72유형 가운데 맞는 것이 없으면 NULL. 판정에 쓰는 유형은 이 값이 있으면 이것, 없으면 type_code다. 대응표가 붙인 type_code는 그대로 둔다';

-- 회사 판단이 읽는 뷰에 AI가 고른 유형을 더한다 (칸은 끝에만 더할 수 있다)
CREATE OR REPLACE VIEW disclosure_counted AS
SELECT d.id, d.company_id, d.source, d.source_key, d.report_name, d.rcept_date,
       COALESCE(d.first_filed_on, d.rcept_date) AS event_date,
       d.type_code, d.tone, d.impact, d.impact_ratio, d.classified_by, d.bundle_id, d.picked_type_code
  FROM disclosure d
  JOIN disclosure r ON r.id = COALESCE(d.bundle_id, d.id)
 WHERE d.superseded_by IS NULL
   AND (position('[기재정정]' in disclosure_prefixes(d.report_name)) > 0
        OR disclosure_prefixes(d.report_name) !~ '\[(첨부정정|첨부추가|발행조건확정)\]')
   AND (position('[기재정정]' in disclosure_prefixes(r.report_name)) = 0 OR r.correction_link = 'NO_PREDECESSOR');

COMMENT ON VIEW disclosure_counted IS '회사 판단에 세는 공시. 정정 묶음에서 가장 늦게 낸 공시 하나만, 첨부만 고친 공시와 앞 공시를 고르지 못하거나 아직 잇지 않은 정정(과 그 뒤 정정)은 빼고. event_date는 회사 판단에 쓰는 날(묶음의 최초 제출일, 없으면 접수일). picked_type_code는 AI가 고른 유형(포괄 제목 등)';
