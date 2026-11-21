-- 본문 표 읽기 단계가 남기는 것.
-- 1) 판정 주체에 TABLE(본문 표를 코드가 읽어 정함)을 더한다.
-- 2) 크기를 정한 원래 비율을 공시에 저장한다. 경계값을 고치면 이 값으로 크기를 다시 정한다.

ALTER TABLE disclosure
    DROP CONSTRAINT disclosure_classified_by_check,
    ADD CONSTRAINT disclosure_classified_by_check CHECK (classified_by IN ('RULE', 'TABLE', 'LLM')),
    ADD COLUMN impact_ratio NUMERIC(18, 4);

COMMENT ON COLUMN disclosure.classified_by IS '판정 주체. RULE 제목 규칙(대응표), TABLE 본문 표를 코드가 읽음, LLM. 아직 판정하지 않았으면 NULL';
COMMENT ON COLUMN disclosure.impact_ratio IS '영향도를 정할 때 본 원래 비율(%). 본문 표에서 읽었거나 본문의 두 숫자로 계산한 값. 어떤 비율인지는 유형이 정한다 (실적은 영업이익 증감율, 유상증자는 증자 전 발행주식총수 대비 신주 수). 읽지 못했거나 비율로 정하지 않는 유형이면 NULL';
