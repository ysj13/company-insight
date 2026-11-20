-- 공시 본문 원문. 분석 작업 큐의 본문 받기 단계가 출처에서 받은 파일을 그대로 저장한다.
-- 본문 표 읽기와 AI 판정·요약은 이 테이블에서 본문을 읽는다. 출처를 다시 부르지 않는다.
-- 공시 목록을 읽는 쿼리가 본문을 끌고 오지 않게 disclosure의 칸이 아니라 따로 둔다.
-- 본문이 없는 공시는 행을 만들지 않는다. 그 사실은 analysis_job.outcome = 'NO_BODY'로 남는다.

CREATE TABLE disclosure_body (
    disclosure_id BIGINT      PRIMARY KEY REFERENCES disclosure (id),
    content       BYTEA       NOT NULL,
    byte_size     INT         NOT NULL,
    file_count    SMALLINT    NOT NULL,
    fetched_at    TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT disclosure_body_byte_size_check CHECK (byte_size > 0),
    CONSTRAINT disclosure_body_file_count_check CHECK (file_count > 0)
);

-- 받은 파일은 이미 압축돼 있다. DB가 다시 압축하려고 시도하지 않게 한다
ALTER TABLE disclosure_body ALTER COLUMN content SET STORAGE EXTERNAL;

COMMENT ON TABLE disclosure_body IS '공시 본문 원문. 공시 한 건에 한 행. 출처가 준 압축 파일을 그대로 두고, 글자는 쓸 때마다 뽑는다 (뽑는 코드를 고치면 다음 실행부터 반영된다). 화면에는 원문을 그대로 내지 않는다';
COMMENT ON COLUMN disclosure_body.disclosure_id IS '공시 (disclosure.id)';
COMMENT ON COLUMN disclosure_body.content IS '출처가 준 본문 파일 그대로. DART는 zip이고 안에 문서 파일이 하나 이상 있다';
COMMENT ON COLUMN disclosure_body.byte_size IS 'content의 크기(바이트)';
COMMENT ON COLUMN disclosure_body.file_count IS '압축 파일 안의 문서 파일 수';
COMMENT ON COLUMN disclosure_body.fetched_at IS '출처에서 받은 시각. 다시 받아 덮어쓰면 이 값이 바뀐다 (수정 시각을 따로 두지 않는다)';
COMMENT ON COLUMN disclosure_body.created_at IS '행 생성 시각';
