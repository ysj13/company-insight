-- 대응 방식을 하나 더한다: 서식 이름 + 꼬리에 든 낱말(FORM_WORD). 대응표 버전 3.
-- 꼬리를 회사가 자유롭게 적는 서식은 제목 전체 줄(EXACT)로는 본 제목만 걸 수 있다.
-- "기업가치제고계획(2027년이행현황)"처럼 처음 보는 꼬리도 꼬리에 든 낱말로 방향을 가른다.
-- 찾는 순서: 제목 전체 줄 → 서식 이름 + 낱말 줄(가장 긴 서식 이름부터) → 서식 이름 줄.

ALTER TABLE title_rule ADD COLUMN tail_word VARCHAR(50);

ALTER TABLE title_rule DROP CONSTRAINT title_rule_match_kind_check;
ALTER TABLE title_rule ADD CONSTRAINT title_rule_match_kind_check CHECK (match_kind IN ('EXACT', 'FORM', 'FORM_WORD'));
-- 낱말은 FORM_WORD 줄에만 있고, FORM_WORD 줄에는 반드시 있다
ALTER TABLE title_rule ADD CONSTRAINT title_rule_tail_word_check CHECK ((match_kind = 'FORM_WORD') = (tail_word IS NOT NULL));

-- 같은 서식에 낱말이 다른 줄을 여럿 둘 수 있게 낱말까지 묶는다. 낱말이 없는 줄끼리는 전과 같이 한 줄만
ALTER TABLE title_rule DROP CONSTRAINT title_rule_pattern_uq;
ALTER TABLE title_rule ADD CONSTRAINT title_rule_pattern_uq UNIQUE NULLS NOT DISTINCT (source, match_kind, pattern, tail_word);

COMMENT ON TABLE title_rule IS '공시 제목 → 유형 대응표. 머리말·공백·기간 표기·속성 꼬리를 뗀 제목으로 찾는다. 제목 전체가 같은 줄(EXACT)이 먼저이고, 다음은 서식 이름 뒤 꼬리에 낱말이 든 줄(FORM_WORD), 없으면 서식 이름 줄(FORM) 가운데 가장 긴 것이다';
COMMENT ON COLUMN title_rule.match_kind IS '대응 방식. EXACT 제목 전체가 pattern과 같다, FORM 제목이 pattern과 같거나 pattern 바로 뒤가 여는 괄호다(괄호 뒤의 글자는 보지 않는다), FORM_WORD pattern 바로 뒤가 여는 괄호이고 그 뒤의 글자에 tail_word가 들어 있다';
COMMENT ON COLUMN title_rule.tail_word IS 'FORM_WORD 줄에서 서식 이름 뒤의 꼬리에 들어 있어야 하는 낱말. 다듬은 제목과 같은 모양(공백 없음)으로 적는다. 다른 대응 방식에서는 NULL';

INSERT INTO title_rule_version (version, note) VALUES
    (3, '대응 방식 FORM_WORD(서식 이름 + 꼬리에 든 낱말) 추가. 기업가치 제고 계획의 이행현황·재공시 꼬리와 소송 제기의 항소·상고 꼬리를 낱말로 건다. 종류주식의 보통주 전환은 악재로. 제목 규칙 622줄(EXACT 284, FORM 332, FORM_WORD 8)');

-- 종류주식(우선주)이 보통주로 바뀌면 보통주 수가 늘어난다. 같은 유형(L2)의 전환청구권 행사, 전환주식의 전환청구권 행사와 같은 방향(유형의 기본 방향, 악재)으로 둔다.
UPDATE title_rule
   SET tone = NULL, memo = '우선주가 보통주로 바뀌어 보통주 수가 늘어난다. 전환주식의 전환청구권 행사와 같은 방향'
 WHERE source = 'DART' AND match_kind = 'FORM' AND pattern = '종류주식의보통주식으로전환';

INSERT INTO title_rule (source, match_kind, pattern, tail_word, type_code, tone, judge_method, case_code, memo, added_version) VALUES
    -- 기업가치 제고 계획(E7): 계획을 낸 것이 호재다. 이행현황은 이미 낸 계획의 진행 보고, 재공시는 같은 계획을 다시 낸 것이다.
    ('DART', 'FORM_WORD', '기업가치제고계획', '이행현황', 'E7', 'NEUTRAL', NULL, NULL, '이미 낸 계획의 이행 보고', 3),
    ('DART', 'FORM_WORD', '기업가치제고계획', '재공시', 'E7', 'NEUTRAL', NULL, NULL, '같은 계획을 다시 낸 것', 3),
    ('DART', 'EXACT', '기업가치제고계획(고배당기업)', NULL, 'E7', 'NEUTRAL', NULL, NULL, '고배당기업 표시를 위해 다시 낸 것으로 본다', 3),
    -- 이행현황과 새 계획을 함께 낸 제목. 제목 전체 줄이 낱말 줄보다 먼저라 호재로 남는다.
    ('DART', 'EXACT', '기업가치제고계획((2025년이행현황및2026년기업가치제고계획))', NULL, 'E7', 'POSITIVE', NULL, NULL, '이행현황과 함께 새 계획을 냈다', 3),
    -- 소송의 제기·신청: 항소와 상고는 회사가 낸 것일 수도, 상대가 낸 것일 수도 있어 제목으로 방향을 붙이지 않는다.
    ('DART', 'FORM_WORD', '소송등의제기ㆍ신청(경영권분쟁소송)', '항소', 'G5', NULL, 'AI', NULL, '누가 냈는지는 본문에 있다', 3),
    ('DART', 'FORM_WORD', '소송등의제기ㆍ신청(경영권분쟁소송)', '상고', 'G5', NULL, 'AI', NULL, '누가 냈는지는 본문에 있다', 3),
    ('DART', 'FORM_WORD', '소송등의제기ㆍ신청(일정금액이상의청구)', '항소', 'I1', NULL, 'AI', NULL, '누가 냈는지는 본문에 있다', 3),
    ('DART', 'FORM_WORD', '소송등의제기ㆍ신청(일정금액이상의청구)', '상고', 'I1', NULL, 'AI', NULL, '누가 냈는지는 본문에 있다', 3),
    ('DART', 'FORM_WORD', '소송등의제기ㆍ신청(자율공시:일정금액미만의청구)', '항소', 'I1', NULL, 'AI', NULL, '누가 냈는지는 본문에 있다', 3),
    ('DART', 'FORM_WORD', '소송등의제기ㆍ신청(자율공시:일정금액미만의청구)', '상고', 'I1', NULL, 'AI', NULL, '누가 냈는지는 본문에 있다', 3);
