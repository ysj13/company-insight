-- 분석 작업 큐. 제목만으로 끝나지 않는 일(본문 받기, 본문 표 읽기, AI 판정, AI 요약)을 공시 한 건의 한 단계씩 줄로 세워 처리한다.
-- analysis_job은 그 단계가 지금 어디 서 있는지(할 일 목록)이고 덮어쓴다.
-- analysis_run은 그 단계를 한 번 실행한 기록이고 쌓기만 한다.
-- 공시가 처리 중인지, 멈췄는지, 판정이 보류됐는지는 이 두 테이블에서 읽는다. disclosure에 상태 칸을 두지 않는다(두 곳에 적으면 어긋난다).

CREATE TABLE analysis_job (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    disclosure_id  BIGINT      NOT NULL REFERENCES disclosure (id),
    stage          VARCHAR(20) NOT NULL,
    status         VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    outcome        VARCHAR(20),
    priority       SMALLINT    NOT NULL,
    run_after      TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts       SMALLINT    NOT NULL DEFAULT 0,
    deferrals      SMALLINT    NOT NULL DEFAULT 0,
    enqueue_reason VARCHAR(20) NOT NULL,
    locked_at      TIMESTAMPTZ,
    last_run_id    BIGINT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 같은 공시의 같은 단계는 한 줄만. 같은 일이 두 번 줄 서지 않는다
    CONSTRAINT analysis_job_disclosure_stage_uq UNIQUE (disclosure_id, stage),
    CONSTRAINT analysis_job_status_check CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    -- 끝난 줄에만 결과가 있다
    CONSTRAINT analysis_job_outcome_check CHECK ((status = 'DONE') = (outcome IS NOT NULL)),
    -- 꺼낸 시각은 실행 중인 줄에만 있다. 이 값이 잠금 구실을 한다
    CONSTRAINT analysis_job_locked_at_check CHECK ((status = 'RUNNING') = (locked_at IS NOT NULL))
);

-- 꺼내기. 대기 줄만 담아 끝난 줄이 쌓여도 크기가 그대로다
CREATE INDEX analysis_job_pending_idx ON analysis_job (stage, priority, run_after, id) WHERE status = 'PENDING';
-- 멈춘 것 목록
CREATE INDEX analysis_job_failed_idx ON analysis_job (stage) WHERE status = 'FAILED';
-- 끝을 보지 못한 실행 찾기
CREATE INDEX analysis_job_running_idx ON analysis_job (locked_at) WHERE status = 'RUNNING';

COMMENT ON TABLE analysis_job IS '분석 작업 큐. 한 줄은 공시 한 건의 한 단계가 지금 어디 서 있는지다. 다시 돌리면 같은 줄을 대기로 되돌려 쓴다. 실행마다의 기록은 analysis_run';
COMMENT ON COLUMN analysis_job.id IS '작업 ID';
COMMENT ON COLUMN analysis_job.disclosure_id IS '공시 (disclosure.id)';
COMMENT ON COLUMN analysis_job.stage IS '단계. FETCH_BODY 본문 받기, READ_TABLE 본문 표 읽기, AI_JUDGE AI 판정, AI_SUMMARY AI 요약. 단계를 더할 때 스키마를 고치지 않게 CHECK를 두지 않는다';
COMMENT ON COLUMN analysis_job.status IS '상태. PENDING 대기, RUNNING 실행 중, DONE 끝남, FAILED 멈춤(실패가 3번 쌓였다. 사람이 다시 시도를 시켜야 움직인다)';
COMMENT ON COLUMN analysis_job.outcome IS '끝났을 때의 결과. ENDED 이 단계에서 끝남, PASSED 다음 단계로 넘김, NO_BODY 본문 없음, HELD 판정 보류. 끝나지 않은 줄은 NULL';
COMMENT ON COLUMN analysis_job.priority IS '우선순위. 작을수록 먼저 꺼낸다. 새 공시 100, 새 공시의 AI 요약 200, 다시 돌리기와 과거분 500';
COMMENT ON COLUMN analysis_job.run_after IS '이 시각이 지나야 꺼낸다. 미룰 때 이 값만 뒤로 민다';
COMMENT ON COLUMN analysis_job.attempts IS '실패로 센 횟수. 3이 되면 FAILED. 대기로 되돌릴 때 0으로 돌린다';
COMMENT ON COLUMN analysis_job.deferrals IS '실패로 세지 않고 미룬 횟수 (외부 서비스의 일시 과부하, 하루 한도). 끝없이 미뤄지는 줄을 찾는 데 쓴다';
COMMENT ON COLUMN analysis_job.enqueue_reason IS '이 줄이 대기가 된 이유. NEW 새로 줄 섬, RETRY 사람이 다시 시도, RERUN 고친 뒤 다시 돌리기, PICKED_TYPE AI가 유형을 고른 뒤';
COMMENT ON COLUMN analysis_job.locked_at IS '꺼낸 시각. 실행 중인 줄에만 있다. 끝낼 때 이 값이 그대로인 줄만 고쳐, 잠금을 잃은 뒤 늦게 끝난 실행이 덮어쓰지 못하게 한다';
COMMENT ON COLUMN analysis_job.last_run_id IS '마지막 실행 기록 (analysis_run.id). 아직 실행한 적이 없으면 NULL';
COMMENT ON COLUMN analysis_job.created_at IS '행 생성 시각';
COMMENT ON COLUMN analysis_job.updated_at IS '행 수정 시각';

CREATE TABLE analysis_run (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id             BIGINT      NOT NULL REFERENCES analysis_job (id),
    disclosure_id      BIGINT      NOT NULL REFERENCES disclosure (id),
    stage              VARCHAR(20) NOT NULL,
    started_at         TIMESTAMPTZ NOT NULL,
    finished_at        TIMESTAMPTZ NOT NULL,
    outcome            VARCHAR(20) NOT NULL,
    enqueue_reason     VARCHAR(20) NOT NULL,
    pass_reason        VARCHAR(40),
    error_kind         VARCHAR(20),
    error_code         VARCHAR(20),
    error              VARCHAR(500),
    result             JSONB,
    setting_version_id BIGINT,
    input_tokens       INT,
    output_tokens      INT,
    CONSTRAINT analysis_run_outcome_check CHECK (outcome IN ('ENDED', 'PASSED', 'NO_BODY', 'HELD', 'FAILED', 'DEFERRED', 'LOST'))
);

-- 한 작업의 실행 기록을 최근 것부터
CREATE INDEX analysis_run_job_idx ON analysis_run (job_id, id DESC);
-- 하루 AI 호출 수와 토큰
CREATE INDEX analysis_run_ai_finished_idx ON analysis_run (finished_at) WHERE setting_version_id IS NOT NULL;

COMMENT ON TABLE analysis_run IS '분석 작업의 실행 기록. 한 단계를 한 번 실행할 때마다 한 줄을 쌓고 고치지 않는다. 다시 돌려도 앞선 실행의 결과가 남는다';
COMMENT ON COLUMN analysis_run.id IS '실행 ID';
COMMENT ON COLUMN analysis_run.job_id IS '작업 (analysis_job.id)';
COMMENT ON COLUMN analysis_run.disclosure_id IS '공시 (disclosure.id). 작업의 값을 그대로 적는다';
COMMENT ON COLUMN analysis_run.stage IS '단계. 작업의 값을 그대로 적는다 (analysis_job.stage)';
COMMENT ON COLUMN analysis_run.started_at IS '실행 시작 시각 (작업을 꺼낸 시각)';
COMMENT ON COLUMN analysis_run.finished_at IS '실행 종료 시각. 끝을 보지 못한 실행(LOST)은 그것을 알아챈 시각';
COMMENT ON COLUMN analysis_run.outcome IS '실행 결과. ENDED 이 단계에서 끝남, PASSED 다음 단계로 넘김, NO_BODY 본문 없음, HELD 판정 보류, FAILED 실패, DEFERRED 실패로 세지 않고 미룸, LOST 끝을 보지 못함(앱이 죽었거나 너무 오래 걸렸다)';
COMMENT ON COLUMN analysis_run.enqueue_reason IS '이 실행이 처리한 대기의 이유 (analysis_job.enqueue_reason의 그때 값)';
COMMENT ON COLUMN analysis_run.pass_reason IS '다음 단계로 넘긴 이유 (예: TONE_BY_AI AI가 방향을 정하는 유형, UNREADABLE 표를 읽지 못함). 넘기지 않았으면 NULL';
COMMENT ON COLUMN analysis_run.error_kind IS '실패하거나 미룬 이유의 분류. 외부 API 오류 분류(TRANSIENT, QUOTA_EXCEEDED 등), INTERNAL 우리 코드·DB 오류';
COMMENT ON COLUMN analysis_run.error_code IS '외부 소스의 원래 오류 코드. 없으면 NULL';
COMMENT ON COLUMN analysis_run.error IS '오류 메시지. 500자까지';
COMMENT ON COLUMN analysis_run.result IS '단계가 남긴 것. 모양은 단계마다 다르다 (읽은 항목, 근거 문장, 요약 등). 없으면 NULL';
COMMENT ON COLUMN analysis_run.setting_version_id IS 'AI 단계의 실행에 쓴 모델·지시문 설정의 버전. AI 단계가 아니면 NULL';
COMMENT ON COLUMN analysis_run.input_tokens IS 'AI 단계의 입력 토큰 수. AI 단계가 아니면 NULL';
COMMENT ON COLUMN analysis_run.output_tokens IS 'AI 단계의 출력 토큰 수. AI 단계가 아니면 NULL';
