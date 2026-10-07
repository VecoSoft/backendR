# Data retention

`DataRetentionJob` (V69) runs once a day (`RETENTION_CRON`, default `0 30 3 * * *`, Dhaka time) and
deletes old rows in batches of `RETENTION_BATCH_SIZE` (default 5000). Each batch is its own short
transaction, with a 50 ms pause between batches. One run does at most `RETENTION_MAX_BATCHES`
(default 200) batches per table; anything left over goes on the next run.

ShedLock (table `shedlock`) makes sure only one API instance runs it at a time. Every run is
recorded on **Admin → System health → Scheduled jobs** with a summary of what it deleted, and
can be started there with **Run now**.

## What is kept, and for how long

| Data | Table | Default | Env var |
|---|---|---|---|
| Read notifications | `notification` | 90 days | `RETENTION_NOTIFICATIONS_READ_DAYS` |
| Unread notifications | `notification` | 180 days | `RETENTION_NOTIFICATIONS_UNREAD_DAYS` |
| Search log | `search_log` | 180 days | `RETENTION_SEARCH_LOG_DAYS` |
| Login events | `user_login_event` | 180 days | `RETENTION_LOGIN_EVENTS_DAYS` |
| Scheduled job runs | `scheduled_job_run` | 60 days | `RETENTION_JOB_RUNS_DAYS` |
| Admin audit log | `audit_log` | 730 days | `RETENTION_AUDIT_LOG_DAYS` |
| Deleted posts and comments | `community_post`, `community_post_comment` | 30 days after deletion | `RETENTION_SOFT_DELETED_CONTENT_DAYS` |

An admin can override any period on **System health → Data retention** (stored in `admin_config`,
section `RETENTION`; a reason is required and the change is audited). A blank field goes back to
the env default. `RETENTION_ENABLED=false` turns the job off.

On the droplet, `deploy/docker-compose.prod.yml` passes only `RETENTION_ENABLED` through, so the
periods are the defaults above unless an admin changes them in the panel. To set a period by
environment instead, add the variable to the backend's `environment:` list there.

Details:

- **Notifications:** age is measured from `created_at`. A notification is "read" when its status is
  `READ` or it has a `read_at`. SMS notifications are never read, so they follow the unread period.
- **Search log:** before raw rows are deleted, they are rolled up into `search_log_daily` (per Dhaka
  day, query and area: searches, zero-result searches, result total, last searched) in the same
  statement. Analytics reads both tables, so top-query and zero-result reports keep their history.
  Only whole days are rolled up.
- **Audit log:** only configuration and content housekeeping types follow a shorter audit setting
  (`DataRetentionSettings.UNPROTECTED_AUDIT_TYPES`). Every other type (moderation, orders, offers,
  boosts, payments, users, security, and any type added later) is kept for **at least 730 days**,
  whatever the setting says.
- **Deleted content:** comments are removed first, and only comments with no replies. A deleted
  comment that still has replies stays as its thread's placeholder until the replies are gone.
  Posts take their comments, reactions, polls, photo rows, mentions and promotion sidecar with them
  (`ON DELETE CASCADE`). **Posts with a boost are never hard-deleted**, because the boost is a
  payment record. Uploaded files are not removed from storage.

## Indexes

V69 adds `created_at` indexes on `notification` and `user_login_event`, a `started_at` index on
`scheduled_job_run`, and partial `deleted_at` indexes on community posts and comments.
`search_log` and `audit_log` already had `created_at` indexes.

## Vacuum

Autovacuum is on (DigitalOcean Managed PostgreSQL runs it by default). It reclaims the space
freed by these deletes and keeps planner statistics current, so no manual step is required.

As a weekly safety net, run `VACUUM (ANALYZE)` on the tables this job deletes from. Do it in quiet
hours, for example Sunday after the 03:30 run:

```sql
VACUUM (ANALYZE) notification, search_log, search_log_daily, user_login_event,
    scheduled_job_run, audit_log, community_post, community_post_comment;
```

On the droplet (managed Postgres; credentials come from `deploy/.env`):

```bash
/opt/jachai/backendR/deploy/psql.sh -c "VACUUM (ANALYZE) notification, search_log, search_log_daily, user_login_event, scheduled_job_run, audit_log, community_post, community_post_comment;"
```

As a weekly cron entry for the `deploy` user (Sunday 04:30 Dhaka = Saturday 22:30 UTC):

```
30 22 * * 6 /opt/jachai/backendR/deploy/psql.sh -c "VACUUM (ANALYZE) notification, search_log, search_log_daily, user_login_event, scheduled_job_run, audit_log, community_post, community_post_comment;" >> /home/deploy/jachai-vacuum.log 2>&1
```

Plain `VACUUM` does not lock out reads or writes. It also does not shrink files on disk: the freed
space is reused for new rows. Don't run `VACUUM FULL` routinely, because it locks the table while it
rewrites it. Check sizes on **System health → Largest tables**.
