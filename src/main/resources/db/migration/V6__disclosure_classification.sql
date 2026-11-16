-- 공시 건별 분류. 제목 규칙으로 판정되는 것은 수집할 때 채우고, 나머지는 tone을 비워 두어 이후 분석 대상으로 남긴다.
ALTER TABLE disclosure
    ADD COLUMN kind          VARCHAR(20) NOT NULL DEFAULT 'ETC',
    ADD COLUMN tone          VARCHAR(10),
    ADD COLUMN impact        SMALLINT,
    ADD COLUMN classified_by VARCHAR(10),
    ADD COLUMN rule_version  SMALLINT    NOT NULL DEFAULT 0,
    ADD COLUMN is_correction BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN is_withdrawn  BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD CONSTRAINT disclosure_kind_check CHECK (kind IN ('PERIODIC', 'EARNINGS', 'MAJOR', 'OWNERSHIP', 'IR', 'ISSUANCE', 'GOVERNANCE', 'MARKET', 'ETC')),
    ADD CONSTRAINT disclosure_tone_check CHECK (tone IN ('POSITIVE', 'NEGATIVE', 'NEUTRAL')),
    ADD CONSTRAINT disclosure_classified_by_check CHECK (classified_by IN ('RULE', 'LLM')),
    -- 영향도의 상한은 DB에 두지 않는다. 범위(지금은 1~3)는 분류 규칙이 정하고, 바꿔도 스키마를 고치지 않게 한다
    ADD CONSTRAINT disclosure_impact_check CHECK (impact >= 1),
    -- 영향도는 호재·악재에만 있다. tone이 NULL이면 비교 결과도 NULL이 되어 CHECK를 통과하므로 COALESCE로 막는다
    ADD CONSTRAINT disclosure_tone_impact_check CHECK (COALESCE(tone IN ('POSITIVE', 'NEGATIVE'), false) = (impact IS NOT NULL)),
    -- 판정된 공시에는 판정 주체가 있다
    ADD CONSTRAINT disclosure_tone_classified_by_check CHECK ((tone IS NULL) = (classified_by IS NULL));

-- 규칙이 바뀌면 옛 버전으로 분류된 공시를 찾아 다시 분류한다
CREATE INDEX disclosure_rule_version_idx ON disclosure (rule_version, id);

COMMENT ON COLUMN disclosure.remark IS 'DART 비고. 유 유가증권시장본부 소관, 코 코스닥시장본부 소관, 정 이후 정정 있음, 철 철회된 보고서 등. 수집할 때마다 최신 값으로 갱신한다';
COMMENT ON COLUMN disclosure.kind IS '공시 종류. PERIODIC 정기보고서, EARNINGS 실적, MAJOR 주요사항, OWNERSHIP 지분, IR 기업설명회·일정, ISSUANCE 증권 발행, GOVERNANCE 주주총회·절차, MARKET 거래소 시장조치, ETC 기타';
COMMENT ON COLUMN disclosure.tone IS '판정. POSITIVE 호재, NEGATIVE 악재, NEUTRAL 중립. 아직 판정하지 않았으면 NULL';
COMMENT ON COLUMN disclosure.impact IS '영향도. 1 이상이고 클수록 영향이 크다. 호재·악재일 때만 값이 있다. 범위는 분류 규칙이 정한다 (규칙 버전 1은 1~3)';
COMMENT ON COLUMN disclosure.classified_by IS '판정 주체. RULE 제목 규칙, LLM. 아직 판정하지 않았으면 NULL';
COMMENT ON COLUMN disclosure.rule_version IS '적용한 제목 규칙의 버전. 0이면 규칙을 적용하지 않은 행';
COMMENT ON COLUMN disclosure.is_correction IS '이미 낸 공시의 후속 공시인지. 보고서명 앞에 [기재정정], [첨부정정], [첨부추가] 같은 머리말이 붙은 공시. 같은 소식이 두 번 반영되지 않게 점수 계산에서 뺀다';
COMMENT ON COLUMN disclosure.updated_at IS '행 수정 시각. 비고가 바뀌었거나 다시 분류했을 때 갱신한다';
COMMENT ON COLUMN disclosure.is_withdrawn IS '철회된 공시인지 (비고에 철). 호재·악재로 세지 않는다';
