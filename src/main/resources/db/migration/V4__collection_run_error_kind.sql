-- 실패 원인을 분류(TRANSIENT, QUOTA_EXCEEDED, AUTH ...)와 소스의 원래 코드로 남긴다. 알림 기준과 원인 분석에 쓴다.
ALTER TABLE collection_run ADD COLUMN error_kind VARCHAR(20);
ALTER TABLE collection_run ADD COLUMN error_code VARCHAR(20);
