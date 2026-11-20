-- 대응표 버전 4. 소송의 제기·신청 서식에서 꼬리에 "항고"가 든 제목도 본문을 읽는다.
-- 항고(재항고, 즉시항고 포함)는 법원의 결정에 불복하는 것이다. 항소·상고와 같이 회사가 낸 것일 수도, 상대가 낸 것일 수도 있어 제목으로 방향을 붙이지 않는다.
-- 낱말 "항고" 한 줄이 "재항고"와 "즉시항고"에도 걸린다.

INSERT INTO title_rule_version (version, note) VALUES
    (4, '소송 제기의 항고·재항고·즉시항고 꼬리를 낱말로 걸어 본문을 읽는다. 제목 규칙 627줄(EXACT 284, FORM 332, FORM_WORD 11)');

INSERT INTO title_rule (source, match_kind, pattern, tail_word, type_code, tone, judge_method, case_code, memo, added_version) VALUES
    ('DART', 'FORM_WORD', '소송등의제기ㆍ신청(경영권분쟁소송)', '항고', 'G5', NULL, 'AI', NULL, '누가 냈는지는 본문에 있다. 재항고와 즉시항고도 이 줄에 걸린다', 4),
    ('DART', 'FORM_WORD', '소송등의제기ㆍ신청(일정금액이상의청구)', '항고', 'I1', NULL, 'AI', NULL, '누가 냈는지는 본문에 있다. 재항고와 즉시항고도 이 줄에 걸린다', 4),
    ('DART', 'FORM_WORD', '소송등의제기ㆍ신청(자율공시:일정금액미만의청구)', '항고', 'I1', NULL, 'AI', NULL, '누가 냈는지는 본문에 있다. 재항고와 즉시항고도 이 줄에 걸린다', 4);
