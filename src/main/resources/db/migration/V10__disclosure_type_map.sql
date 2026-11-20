-- 공시 유형과 "제목 → 유형" 대응표를 테이블로 둔다.
-- 지금까지는 자바 코드의 규칙이 공시를 종류 9가지(disclosure.kind)로 나눴다. 이제 유형 목록과 제목 규칙을 테이블에 두고,
-- 분류기는 앱이 뜰 때 이 테이블을 읽어 메모리에서 찾는다. 유형이나 규칙을 더할 때 스키마와 코드를 고치지 않는다.
-- 처음 값은 V11에 있다. 값을 고칠 때도 마이그레이션으로만 고치고, 그때마다 title_rule_version에 한 줄을 더한다.

CREATE TABLE disclosure_type (
    code         VARCHAR(4)  PRIMARY KEY,
    bundle       VARCHAR(1)  NOT NULL,
    name         VARCHAR(60) NOT NULL,
    judge_method VARCHAR(10) NOT NULL,
    default_tone VARCHAR(10),
    fetch_body   BOOLEAN     NOT NULL DEFAULT true,
    sort_order   SMALLINT    NOT NULL,
    CONSTRAINT disclosure_type_judge_method_check CHECK (judge_method IN ('TITLE', 'CODE', 'AI')),
    CONSTRAINT disclosure_type_default_tone_check CHECK (default_tone IN ('POSITIVE', 'NEGATIVE', 'NEUTRAL')),
    -- 방향을 제목이 정하지 않는 유형에는 기본 방향이 없다
    CONSTRAINT disclosure_type_tone_method_check CHECK (default_tone IS NULL OR judge_method = 'TITLE'),
    CONSTRAINT disclosure_type_sort_order_uq UNIQUE (sort_order)
);

COMMENT ON TABLE disclosure_type IS '공시 유형. 공시 한 건은 제목 규칙으로 이 중 하나를 받는다';
COMMENT ON COLUMN disclosure_type.code IS '유형 번호. 묶음 글자 + 숫자 (예: A1 정기보고서, K1 포괄 제목, Z9 미분류)';
COMMENT ON COLUMN disclosure_type.bundle IS '묶음 글자. A 정기·절차, B 실적·재무, C 공급계약·기술, D 자금 조달, L 전환·행사·사채 취득, E 주주환원·주식 수, F 합병·인수·투자, G 경영권·임원, H 보증·대여·내부거래, I 소송·사건, J 시장조치, R 리츠·스팩 전용, K 포괄 제목·꼬리 미상, Z 미분류';
COMMENT ON COLUMN disclosure_type.name IS '유형 이름';
COMMENT ON COLUMN disclosure_type.judge_method IS '방향(호재·악재·중립)을 정하는 방법. TITLE 제목으로 정해진다, CODE 본문 표를 코드가 읽는다, AI 본문을 AI가 읽는다. 규칙 줄(title_rule.judge_method)이 따로 정하면 그 값이 먼저다';
COMMENT ON COLUMN disclosure_type.default_tone IS '제목으로 정해지는 기본 방향. POSITIVE 호재, NEGATIVE 악재, NEUTRAL 중립. 규칙 줄(title_rule.tone)이 따로 정하면 그 값이 먼저다. 방향이 서식·꼬리마다 달라 규칙 줄에만 적는 유형과 TITLE이 아닌 유형은 NULL';
COMMENT ON COLUMN disclosure_type.fetch_body IS '공시 본문을 받을지. 본문이 큰 정기보고서와 증권 발행 서류만 false';
COMMENT ON COLUMN disclosure_type.sort_order IS '보여주는 순서';

CREATE TABLE title_rule (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source        VARCHAR(20)  NOT NULL,
    match_kind    VARCHAR(10)  NOT NULL,
    pattern       VARCHAR(300) NOT NULL,
    type_code     VARCHAR(4)   NOT NULL REFERENCES disclosure_type (code),
    tone          VARCHAR(10),
    judge_method  VARCHAR(10),
    case_code     VARCHAR(30),
    memo          TEXT,
    added_version INT          NOT NULL,
    CONSTRAINT title_rule_match_kind_check CHECK (match_kind IN ('EXACT', 'FORM')),
    CONSTRAINT title_rule_tone_check CHECK (tone IN ('POSITIVE', 'NEGATIVE', 'NEUTRAL')),
    CONSTRAINT title_rule_judge_method_check CHECK (judge_method IN ('TITLE', 'CODE', 'AI')),
    -- 같은 모양의 같은 글자는 한 줄만. 한 제목에 같은 순위의 규칙이 둘 걸리지 않는다
    CONSTRAINT title_rule_pattern_uq UNIQUE (source, match_kind, pattern)
);

CREATE INDEX title_rule_type_code_idx ON title_rule (type_code);

COMMENT ON TABLE title_rule IS '공시 제목 → 유형 대응표. 머리말·공백·기간 표기·속성 꼬리를 뗀 제목으로 찾는다. 제목 전체가 같은 줄(EXACT)이 먼저이고, 없으면 서식 이름 줄(FORM) 가운데 가장 긴 것이다';
COMMENT ON COLUMN title_rule.id IS '규칙 ID. 공시가 어느 줄에 걸렸는지 disclosure.title_rule_id에 남긴다';
COMMENT ON COLUMN title_rule.source IS '이 규칙을 적용하는 공시의 출처. DART 금융감독원 전자공시시스템. 출처마다 제목의 글자가 달라 줄을 따로 둔다';
COMMENT ON COLUMN title_rule.match_kind IS '대응 방식. EXACT 제목 전체가 pattern과 같다, FORM 제목이 pattern과 같거나 pattern 바로 뒤가 여는 괄호다(괄호 뒤의 글자는 보지 않는다)';
COMMENT ON COLUMN title_rule.pattern IS '맞춰 볼 글자. 다듬은 제목(공백 없음, 가운뎃점은 ㆍ)과 같은 모양으로 적는다';
COMMENT ON COLUMN title_rule.type_code IS '이 줄에 걸린 공시의 유형 (disclosure_type.code)';
COMMENT ON COLUMN title_rule.tone IS '이 줄이 정하는 방향. 비우면 유형의 default_tone. 한 유형 안에서 서식이나 꼬리에 따라 방향이 다를 때 적는다';
COMMENT ON COLUMN title_rule.judge_method IS '이 줄의 판정 방법. 비우면 유형의 값. 한 유형 안에서 서식에 따라 방법이 다를 때 적는다 (예: 소송의 제기는 제목, 판결은 AI)';
COMMENT ON COLUMN title_rule.case_code IS '제목이 정하는 경우. 같은 유형 안에서 크기를 달리 매길 때 쓰는 이름이다. 아직 나누지 않은 줄은 NULL';
COMMENT ON COLUMN title_rule.memo IS '왜 이 유형·방향인지 (본문을 몇 건 봤는지 등)';
COMMENT ON COLUMN title_rule.added_version IS '이 줄이 들어온 대응표 버전 (title_rule_version.version)';

CREATE TABLE title_attribute (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source    VARCHAR(20) NOT NULL,
    token     VARCHAR(60) NOT NULL,
    attribute VARCHAR(20) NOT NULL,
    CONSTRAINT title_attribute_attribute_check CHECK (attribute IN ('SUBSIDIARY', 'VOLUNTARY', 'FAIR', 'GUIDE')),
    CONSTRAINT title_attribute_token_uq UNIQUE (source, token)
);

COMMENT ON TABLE title_attribute IS '제목의 속성 꼬리. 유형을 바꾸지 않고 공시의 성격만 알려 주는 꼬리라 대응표를 찾기 전에 떼어 속성으로 적는다';
COMMENT ON COLUMN title_attribute.id IS '속성 꼬리 ID';
COMMENT ON COLUMN title_attribute.source IS '이 꼬리를 쓰는 공시의 출처';
COMMENT ON COLUMN title_attribute.token IS '제목에서 떼는 글자. 괄호까지 적는다 (예: (자회사의주요경영사항))';
COMMENT ON COLUMN title_attribute.attribute IS '속성. SUBSIDIARY 자회사의 공시, VOLUNTARY 자율공시, FAIR 공정공시, GUIDE 안내공시';

CREATE TABLE title_rule_version (
    version    INT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    note       TEXT        NOT NULL
);

COMMENT ON TABLE title_rule_version IS '대응표 버전. 유형·제목 규칙·속성 꼬리를 고치는 마이그레이션마다 한 줄을 더한다. 가장 큰 번호가 지금 버전이고, 그보다 낮은 버전으로 분류된 공시는 다시 분류한다';
COMMENT ON COLUMN title_rule_version.version IS '버전 번호. 1부터 하나씩 올린다';
COMMENT ON COLUMN title_rule_version.applied_at IS '이 DB에 적용된 시각';
COMMENT ON COLUMN title_rule_version.note IS '무엇을 바꿨는지';

-- 공시: 종류 9가지(kind)와 자바 규칙의 버전(rule_version)을 걷어내고 유형과 걸린 규칙을 둔다.
-- 이미 저장된 공시는 title_rule_version = 0으로 두어, 앱이 뜬 뒤 재분류가 새 대응표로 채운다.
DROP INDEX disclosure_rule_version_idx;

ALTER TABLE disclosure
    DROP CONSTRAINT disclosure_kind_check,
    DROP CONSTRAINT disclosure_tone_impact_check,
    DROP COLUMN kind,
    DROP COLUMN rule_version,
    ADD COLUMN type_code          VARCHAR(4) REFERENCES disclosure_type (code),
    ADD COLUMN title_rule_id      BIGINT REFERENCES title_rule (id),
    ADD COLUMN title_rule_version INT        NOT NULL DEFAULT 0,
    ADD COLUMN case_code          VARCHAR(30),
    ADD COLUMN normalized_title   VARCHAR(300),
    ADD COLUMN title_attributes   VARCHAR(20)[] NOT NULL DEFAULT '{}',
    -- 영향도는 호재·악재에만 있다. 제목 단계는 방향만 정하고 영향도는 뒤 단계가 채우므로, 방향이 있는데 영향도가 빈 것은 허용한다
    ADD CONSTRAINT disclosure_impact_tone_check CHECK (impact IS NULL OR COALESCE(tone IN ('POSITIVE', 'NEGATIVE'), false));

-- 옛 규칙이 매긴 방향과 영향도는 새 대응표와 기준이 달라 비운다. 재분류가 방향을 다시 채운다
UPDATE disclosure
   SET tone = NULL, impact = NULL, classified_by = NULL
 WHERE classified_by IS DISTINCT FROM 'LLM';

-- 옛 버전으로 남은 공시를 찾는다
CREATE INDEX disclosure_title_rule_version_idx ON disclosure (title_rule_version, id);
-- 유형별 목록과 건수, 미분류 목록
CREATE INDEX disclosure_type_code_idx ON disclosure (type_code);

COMMENT ON COLUMN disclosure.type_code IS '공시 유형 (disclosure_type.code). 대응표에 없는 제목은 Z9. 아직 분류하지 않은 행은 NULL';
COMMENT ON COLUMN disclosure.title_rule_id IS '걸린 제목 규칙 (title_rule.id). 대응표에 없는 제목이면 NULL';
COMMENT ON COLUMN disclosure.title_rule_version IS '분류에 쓴 대응표 버전 (title_rule_version.version). 0이면 아직 분류하지 않은 행';
COMMENT ON COLUMN disclosure.case_code IS '제목이 정한 경우 (title_rule.case_code). 없으면 NULL';
COMMENT ON COLUMN disclosure.normalized_title IS '대응표를 찾을 때 쓴 제목. 보고서명에서 머리말, 공백, 기간 표기, 속성 꼬리를 뗀 것';
COMMENT ON COLUMN disclosure.title_attributes IS '제목에서 뗀 속성. SUBSIDIARY 자회사의 공시, VOLUNTARY 자율공시, FAIR 공정공시, GUIDE 안내공시, WITHDRAWAL 꼬리에 철회·취하가 든 공시. 없으면 빈 배열';
COMMENT ON COLUMN disclosure.tone IS '판정. POSITIVE 호재, NEGATIVE 악재, NEUTRAL 중립. 아직 판정하지 않았으면 NULL (본문을 읽어 정할 공시)';
COMMENT ON COLUMN disclosure.impact IS '영향도. 1 이상이고 클수록 영향이 크다. 호재·악재일 때만 값이 있다. 제목 단계는 채우지 않는다';
COMMENT ON COLUMN disclosure.classified_by IS '판정 주체. RULE 제목 규칙(대응표), LLM. 아직 판정하지 않았으면 NULL';
