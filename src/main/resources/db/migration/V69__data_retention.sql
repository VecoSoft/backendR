-- V69: data retention (DataRetentionJob).
--
-- 1. shedlock: lock rows so only one API instance runs a guarded scheduled job at a time.
-- 2. search_log_daily: per-day search counts, filled from search_log rows before they are
--    deleted, so System -> Analytics keeps search history past the raw-log retention window.
-- 3. Indexes on the timestamps the retention deletes filter on.
-- 4. Child tables of community posts that did not cascade now do, so a soft-deleted post can be
--    hard-deleted in one statement. Posts are still never hard-deleted outside the retention job.

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

CREATE TABLE search_log_daily (
    day                  DATE    NOT NULL, -- Asia/Dhaka calendar day, as in Analytics
    normalized           TEXT    NOT NULL,
    area                 TEXT    NOT NULL, -- area name (or free-text label) at aggregation time; '(any area)' when none
    searches             INTEGER NOT NULL,
    zero_result_searches INTEGER NOT NULL,
    result_count_sum     BIGINT  NOT NULL,
    last_searched        TIMESTAMPTZ NOT NULL,
    last_zero_result_at  TIMESTAMPTZ,          -- latest search of the group that found nothing
    PRIMARY KEY (day, normalized, area)
);

-- search_log (idx_search_log_created) and audit_log (idx_audit_log_created) already have one.
CREATE INDEX IF NOT EXISTS idx_notification_created ON notification (created_at);
CREATE INDEX IF NOT EXISTS idx_user_login_event_created ON user_login_event (created_at);
CREATE INDEX IF NOT EXISTS idx_scheduled_job_run_started ON scheduled_job_run (started_at);
CREATE INDEX IF NOT EXISTS idx_community_post_deleted ON community_post (deleted_at) WHERE deleted_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_community_comment_deleted ON community_post_comment (deleted_at) WHERE deleted_at IS NOT NULL;
-- The leaf check before hard-deleting a comment looks up replies by parent, deleted or not.
CREATE INDEX IF NOT EXISTS idx_community_comment_parent_all ON community_post_comment (parent_comment_id)
    WHERE parent_comment_id IS NOT NULL;

DO $$
DECLARE
    fk RECORD;
    con TEXT;
BEGIN
    FOR fk IN SELECT * FROM (VALUES
            ('community_post_poll',        'post_id',   'community_post',             'id'),
            ('community_post_poll_option', 'poll_id',   'community_post_poll',        'id'),
            ('community_post_poll_vote',   'poll_id',   'community_post_poll',        'id'),
            ('community_post_poll_vote',   'option_id', 'community_post_poll_option', 'id'),
            ('community_post_photo',       'post_id',   'community_post',             'id'),
            ('community_question_follow',  'post_id',   'community_post',             'id'),
            ('community_question_pass',    'post_id',   'community_post',             'id')
        ) AS t(tbl, col, ref_tbl, ref_col)
    LOOP
        SELECT c.conname INTO con
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
        WHERE c.contype = 'f' AND c.conrelid = fk.tbl::regclass AND c.confrelid = fk.ref_tbl::regclass
          AND array_length(c.conkey, 1) = 1 AND a.attname = fk.col;
        IF con IS NULL THEN
            RAISE EXCEPTION 'V69: no foreign key %.% -> %', fk.tbl, fk.col, fk.ref_tbl;
        END IF;
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', fk.tbl, con);
        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %I (%I) ON DELETE CASCADE',
                       fk.tbl, con, fk.col, fk.ref_tbl, fk.ref_col);
    END LOOP;
END $$;
