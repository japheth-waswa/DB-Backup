# AppBackup

Scheduled, restorable logical backups of SQL and NoSQL databases, plus arbitrary directories.
Every run is archived with a SHA-256 checksum, optionally shipped to Backblaze B2 and recorded
in an audit trail you can query over the API.

| Engine | Dump format | Restore with |
|---|---|---|
| PostgreSQL | SQL files (pre-data, one file per table, post-data) | `psql` |
| MySQL / MariaDB | SQL files | `mysql` |
| Microsoft SQL Server | SQL files with `GO` batches | `sqlcmd` |
| Oracle | SQL files for SQL*Plus | `sqlplus` / SQLcl |
| MongoDB | `mongodump` layout (`.bson` + `.metadata.json`) | `mongorestore` |
| Redis | `RESTORE ... ABSTTL REPLACE` commands in RESP | `redis-cli --pipe` |

Every engine has an integration test that dumps a deliberately awkward database, restores it
with the engine's own tool and compares the data row by row (see `src/test`).

## How a run works

1. The job is queued. At most `BACKUP_APP_MAX_CONCURRENT_BACKUPS` jobs run at once; a job never
   overlaps with itself (the second run is recorded as `SKIPPED`).
2. The destination is checked for free space (`BACKUP_APP_MIN_FREE_DISK_MB`).
3. The database is dumped by a coordinator connection plus up to
   `BACKUP_APP_MAX_CONNECTIONS_PER_BACKUP - 1` workers. Rows are **streamed**, never paged with
   `OFFSET`, and every connection is closed when the run ends, whatever happens.
4. The dump is zipped, checksummed and moved into the destination atomically
   (`db-<timestamp>.zip` + `db-<timestamp>.zip.sha256`).
5. The archive is uploaded (retried on transient errors), then optional retention runs.
6. The outcome is stored in `backup_runs`: `SUCCESS`, `PARTIAL` (some tables failed, listed in
   the message), `FAILED` or `SKIPPED`.

### Consistency

| Engine | Point in time |
|---|---|
| PostgreSQL | One exported snapshot shared by all workers |
| Oracle | `AS OF SCN` for every table when the user may read the SCN (`GRANT EXECUTE ON DBMS_FLASHBACK`), otherwise per table |
| SQL Server | Snapshot isolation per connection when `ALLOW_SNAPSHOT_ISOLATION` is on, otherwise `READ COMMITTED` |
| MySQL / MariaDB | Consistent snapshot per connection; use `tableBatchesOf: 1` for one snapshot overall |
| MongoDB / Redis | Per collection / per key, like `mongodump` without `--oplog` and `SCAN` |

## Configuration

All settings come from environment variables; see `.env-docker.example`.

| Variable | Default | Purpose |
|---|---|---|
| `BACKUP_APP_API_KEY` | – | **Required.** Sent in the `X-API-Key` header. Without it the API answers 503. |
| `BACKUP_APP_ENCRYPTION_KEY` | – | Base64, 32 bytes. Encrypts stored passwords and cloud keys (AES-256-GCM). Keep a copy safe. |
| `BACKUP_APP_MAX_CONCURRENT_BACKUPS` | 2 | Jobs running at the same time |
| `BACKUP_APP_MAX_CONNECTIONS_PER_BACKUP` | 3 | Connections one backup may hold on the target |
| `BACKUP_APP_RUN_ON_STARTUP` | true | Run every job once when the service starts |
| `BACKUP_APP_MIN_FREE_DISK_MB` | 512 | Refuse to start a backup below this |
| `BACKUP_APP_RETENTION_KEEP_LAST` | 0 | Local archives kept per job (0 keeps all) |
| `BACKUP_APP_CLOUD_RETENTION_DAYS` | 0 | Delete remote archives older than this (0 never deletes) |
| `BACKUP_APP_RUN_HISTORY_DAYS` | 90 | Run history kept |

Generate keys with `openssl rand -hex 32` (API key) and `openssl rand -base64 32` (encryption key).
Existing plaintext credentials are encrypted automatically on the first start with a key.

## API

All endpoints except `GET /actuator/health` require `X-API-Key`.

| Method | Path | |
|---|---|---|
| `POST` | `/backup` | Register a job and run it now |
| `GET` | `/backups` | List jobs (no credentials) with their last run |
| `GET` | `/backups/{id}` | One job |
| `GET` | `/backups/{id}/runs?limit=20` | Run history |
| `POST` | `/backups/{id}/run` | Run a job now |
| `DELETE` | `/backups/{id}` | Remove a job and its history; archives stay on disk |
| `GET` | `/init` | Run every job now (kept for older scripts) |

### Register a backup

```shell
http POST :4934/backup X-API-Key:$BACKUP_APP_API_KEY \
    schedulerType=EVERY_6_HOURS \
    destination=/backups/shop \
    otherDirsToBackup:='["/data/uploads"]' \
    cloudType=BACK_BLAZE \
    cloudDestinationDir=shop \
    dbConfig:='{
        "dbType": "POSTGRESQL",
        "host": "shop_postgres",
        "port": 5432,
        "username": "***",
        "password": "***",
        "dbName": "shop",
        "tableBatchesOf": 2
    }' \
    blazeCloudConfig:='{"keyId": "***", "keyName": "***", "applicationKey": "***", "bucketName": "***"}'
```

`dbType` is one of `POSTGRESQL`, `MYSQL`, `MARIADB`, `ORACLE`, `MSSQL`, `MONGODB`, `REDIS`.

* **Oracle**: `dbName` is the service name (e.g. `FREEPDB1`); the connecting user's schema is dumped.
* **SQL Server**: pass driver settings in `options`, e.g. `"options": "encrypt=true;trustServerCertificate=true"`.
* **MongoDB**: credentials are optional; `"options": "authSource=admin"`.
* **Redis**: `dbName` is the database index (`"0"`); username/password optional (ACL / `requirepass`).

`schedulerType`: `HOURLY`, `EVERY_6_HOURS`, `EVERY_12_HOURS`, `DAILY`, `EVERY_3_DAYS`,
`EVERY_WEEK`, `EVERY_FORTNIGHT`, `MONTHLY`, `ANNUALLY` (cron, server time zone).

## Restoring

Every database archive contains a `MANIFEST.txt` with row counts, failures and the exact restore
commands. Verify the archive first:

```shell
sha256sum -c db-2026-10-31-06-00-00.zip.sha256
unzip db-2026-10-31-06-00-00.zip -d restore && cat restore/MANIFEST.txt
```

## Build and test

```shell
./mvnw verify                       # unit + Testcontainers tests (Docker needed, nothing else)
docker build -f docker/Dockerfile.native-build --target export --output type=local,dest=target/native .
cp target/native/AppBackup . && docker compose up -d --build app-backup
```

Database tests use Testcontainers and are skipped automatically on machines without Docker.

## Design

Hexagonal layout: `adapter/input` (REST, scheduler) → `ports/input` → `service` → `ports/output`
← `adapter/output` (database dump strategies, cloud storage). Relational engines extend
`SqlDumpStrategy`, which owns the worker pool, file layout and error handling; a new SQL engine
only implements a `DumpSession`. Non-relational engines implement `DatabaseDumpStrategy` directly.
