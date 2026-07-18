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
| `TRAP21_IDLE_TIMEOUT` | `120` | Control idle timeout in seconds |
| `TRAP21_COMMAND_TIMEOUT` | `15` | Absolute time to finish a command after its first byte |
| `TRAP21_DATA_TIMEOUT` | `15` | Data connection timeout in seconds |
| `TRAP21_MAX_UPLOAD_BYTES` | `10485760` | Maximum upload size |
| `TRAP21_MAX_QUARANTINE_BYTES` | `268435456` | Total retained quarantine-byte limit |
| `TRAP21_MAX_QUARANTINE_FILES` | `4096` | Total retained quarantine-file limit |
| `TRAP21_RETENTION_DAYS` | `30` | Age limit for historical quarantine artifacts |
| `TRAP21_MAX_EVENT_LOG_BYTES` | `33554432` | Rotate the active JSONL log at this size |
| `TRAP21_MAX_EVENT_ARCHIVES` | `5` | Rotated JSONL archives to retain |
| `TRAP21_MAX_SESSIONS` | `64` | Concurrent session limit |
| `TRAP21_MAX_SESSIONS_PER_IP` | `8` | Concurrent sessions allowed per source address |

## Remote deployment

The Compose configuration publishes only on `127.0.0.1` by default. For an authorized remote deployment, set both values explicitly in `.env`:

```dotenv
TRAP21_LISTEN_HOST=0.0.0.0
TRAP21_PUBLIC_HOST=<public IPv4>
```

`TRAP21_LISTEN_HOST` controls the host interfaces Docker publishes. `TRAP21_PUBLIC_HOST` is the address returned by `PASV`. Passive FTP will fail if the public host points to an internal container address.

## Data persistence

Compose stores telemetry and quarantine data in the named volume `trap21-data`, preserving the image's non-root ownership. Use `docker compose down -v` only when you intentionally want to delete that evidence volume.

## Default credentials

The passwords are intentionally weak. They are pinned to positions 10, 15, 20, and every fifth position after that in the [NordPass 2025 global password ranking](https://nordpass.com/most-common-passwords-list/).

| Username | Password | Rank | Profile |
|---|---|---:|---|
| `admin` | `admin123` | 10 | Administrator |
| `administrator` | `P@ssw0rd` | 15 | Administrator |
| `ftp` | `112233` | 20 | Transfer |
| `ftpadmin` | `qwerty123` | 25 | Administrator |
| `ftpuser` | `87654321` | 30 | Transfer |
| `backup` | `Aa112233` | 35 | Backup |
| `operator` | `Password@123` | 40 | Operations |
| `service` | `Admin123` | 45 | Transfer service |
| `guest` | `121212` | 50 | Guest |

The `anonymous` account accepts a password containing an email-style `@` separator and receives read-only access to `/pub`.
