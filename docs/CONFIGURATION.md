# TRAP21 configuration

## Environment variables

TRAP21 uses the following environment variables:

| Environment variable | Default | Purpose |
|---|---|---|
| `TRAP21_LISTEN_HOST` | `127.0.0.1` | Host interface published by Docker Compose |
| `TRAP21_BIND` | `0.0.0.0` | Control and passive bind address |
| `TRAP21_PORT` | `2121` | Internal control port |
| `TRAP21_PASSIVE_START` | `30000` | First passive port |
| `TRAP21_PASSIVE_END` | `30009` | Last passive port |
| `TRAP21_PUBLIC_HOST` | control address | IPv4 advertised by `PASV` |
| `TRAP21_DATA_DIR` | `data` | VFS, telemetry, and quarantine root |
| `TRAP21_IDLE_TIMEOUT` | `120` | Control inactivity timeout in seconds |
| `TRAP21_MAX_SESSION_SECONDS` | `120` | Absolute lifetime of a control session and any passive listener it owns |
| `TRAP21_COMMAND_TIMEOUT` | `15` | Absolute time to finish a command after its first byte |
| `TRAP21_DATA_TIMEOUT` | `15` | Timeout for establishing or using a passive data connection |
| `TRAP21_MAX_UPLOAD_BYTES` | `10485760` | Maximum upload size |
| `TRAP21_MAX_QUARANTINE_BYTES` | `268435456` | Total retained quarantine-byte limit |
| `TRAP21_MAX_QUARANTINE_FILES` | `4096` | Total retained quarantine-file limit |
| `TRAP21_RETENTION_DAYS` | `30` | Age threshold used during quarantine pruning |
| `TRAP21_MAX_VFS_DIRECTORIES` | `4096` | Maximum directories beneath the virtual root, including seeded directories |
| `TRAP21_MAX_EVENT_LOG_BYTES` | `33554432` | Rotate the active JSONL log at this size |
| `TRAP21_MAX_EVENT_ARCHIVES` | `5` | Rotated JSONL archives to retain |
| `TRAP21_MAX_SESSIONS` | `64` | Concurrent session limit |
| `TRAP21_MAX_SESSIONS_PER_IP` | `8` | Concurrent sessions allowed per source address |

Telemetry rate limits do not throttle or alter FTP responses. For each session, TRAP21 retains up to 100 `COMMAND` events and 25 failed `AUTH_ATTEMPT` events per second. Additional events are represented by `COMMANDS_SUPPRESSED` and `AUTH_ATTEMPTS_SUPPRESSED` summaries. Successful authentication attempts and upload, transfer, error, and lifecycle events bypass rate suppression. If the event writer cannot persist evidence, it becomes unhealthy and new FTP sessions are refused until persistence recovers. Authentication summaries record counts of distinct usernames and passwords without retaining each additional plaintext password.

The directory limit is initialized from the persisted virtual tree at startup. Each accepted `MKD`/`XMKD` reserves one slot; once the limit is reached, additional directory creation is rejected. Upload placeholders remain bounded separately by `TRAP21_MAX_QUARANTINE_FILES`.

## Remote deployment

The Compose configuration publishes only on `127.0.0.1` by default. For an authorized remote deployment, set both values explicitly in `.env`:

```dotenv
TRAP21_LISTEN_HOST=0.0.0.0
TRAP21_PUBLIC_HOST=<public IPv4>
```

`TRAP21_LISTEN_HOST` controls the host interfaces Docker publishes. `TRAP21_PUBLIC_HOST` is the address returned by `PASV`. Passive FTP will fail if the public host points to an internal container address.

## Data persistence

Compose stores telemetry and quarantine data in the named volume `trap21-data`, preserving the image's non-root ownership. On startup, the supplied container applies owner-only permissions to the evidence tree: `0700` for directories and `0600` for files. New artifacts inherit a restrictive `077` umask. Use `docker compose down -v` only when you intentionally want to delete that evidence volume.

Age-based quarantine pruning runs at startup and is checked before new captures. Artifacts still mapped into the live virtual filesystem remain available for the life of that server process, so `TRAP21_RETENTION_DAYS` is a pruning threshold rather than a guaranteed maximum age for every live artifact.

## Default credentials

The built-in passwords are intentionally weak and were selected using the [NordPass 2025 global password list](https://nordpass.com/most-common-passwords-list/) as a reference. NordPass was used to guide the selection of believable, commonly used passwords for the decoy accounts; TRAP21 does not claim that its accounts reproduce particular ranking positions.

The decoy usernames, passwords, and access profiles are defined in `CredentialStore.java`. They are honeypot credentials only and must never be reused for real systems. The `anonymous` account accepts a password containing an email-style `@` separator and receives read-only access to `/pub`.
