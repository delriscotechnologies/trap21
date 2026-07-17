# TRAP21

**FTP deception honeypot**

TRAP21 is a medium-interaction FTP honeypot written in Java. It presents a believable managed-file-transfer gateway, accepts selected weak credentials, exposes a role-aware decoy filesystem, captures uploads in quarantine, and records every session as JSON Lines telemetry.

The FTP client is the interface. There is no web dashboard.

> [!CAUTION]
> TRAP21 is intentionally vulnerable. Deploy it only on systems and networks you own or are explicitly authorized to monitor. Never mount host directories containing real data, credentials, or executables.

## What an FTP client sees

```text
220 Authorized business use only
```

```text
C: USER ftpuser
S: 331 Password required for ftpuser.
C: PASS 87654321
S: 230 User logged in, proceed.
C: PWD
S: 257 "/" is current directory.
```

TRAP21 does not expose its product name, honeypot purpose, Java implementation, or operator branding to the FTP client.

## Features

- Real FTP control sessions over TCP.
- Passive transfers with `PASV` and `EPSV`.
- Default, weak, and anonymous credentials.
- Different filesystem visibility for each account profile.
- Seeded enterprise-style directories and decoy documents.
- Upload capture with size limits and SHA-256 hashes.
- Dedicated virtual filesystem with normalized paths and symbolic-link rejection.
- Source-address validation for passive data connections.
- Bounded command length, idle timeouts, and session limits.
- JSONL events for connections, credentials, commands, downloads, and uploads.
- Non-root, capability-free Docker runtime.
- No shell, command execution, proxying, archive extraction, or malware execution.

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

## Quick start

### Docker Compose

```powershell
Copy-Item .env.example .env
docker compose up --build
```

The Compose configuration publishes only on `127.0.0.1` by default:

- FTP control: `21/tcp`
- Passive data: `30000-30009/tcp`

Test the local service:

```powershell
curl.exe --user "ftpuser:87654321" "ftp://127.0.0.1/"
curl.exe --user "ftpuser:87654321" "ftp://127.0.0.1/pub/README.txt"
```

For an authorized remote deployment, explicitly set both `TRAP21_LISTEN_HOST=0.0.0.0` and `TRAP21_PUBLIC_HOST=<public IPv4>` in `.env`. The first value controls the host interfaces Docker publishes; the second is the address returned by `PASV`. Passive FTP will fail if the public host points to an internal container address.

Compose stores telemetry and quarantine data in the named volume `trap21-data`, preserving the image's non-root ownership. Use `docker compose down -v` only when you intentionally want to delete that evidence volume.

Stop the service:

```powershell
docker compose down
```

### VS Code

The repository recommends:

- Extension Pack for Java
- GitHub Pull Requests and Issues
- YAML

Run **Terminal → Run Task → TRAP21: Test with Docker**. A local JDK is optional when using the Docker task.

### Local JDK

With JDK 21 configured through `JAVA_HOME` or `PATH`:

```powershell
./scripts/test.ps1
java -jar ./build/trap21.jar
```

The direct Java process listens on port `2121` by default, avoiding the privileged/public FTP port during development.

## Filesystem profiles

TRAP21 seeds a virtual tree beneath `data/vfs`:

```text
/
├── archive/completed/
├── backups/{daily,monthly,incoming}/
├── config/
├── incoming/
├── logs/
├── outgoing/
├── pub/
├── reports/
└── users/{admin,backup,ftpuser}/
```

Transfer accounts can see `/incoming`, `/outgoing`, `/pub`, and an intentionally leaked `/archive/completed`. Backup, operations, service, guest, and anonymous accounts each receive different views. Administrator accounts can explore the entire virtual tree.

Paths such as `../../../../Windows/System32` normalize inside the virtual root. TRAP21 also rejects symbolic links anywhere beneath the virtual filesystem or quarantine tree. The containment boundary still depends on mounting only the dedicated data directory and preventing untrusted local processes from modifying it while the server is running.

## FTP commands

TRAP21 implements the common commands needed by standard clients and scanners:

```text
USER PASS QUIT NOOP SYST FEAT HELP CLNT
PWD XPWD CWD XCWD CDUP XCUP
TYPE MODE STRU OPTS
PASV EPSV LIST NLST RETR STOR APPE
SIZE MDTM MKD XMKD RMD XRMD DELE
RNFR RNTO STAT ABOR
```

Active data mode (`PORT` and `EPRT`) and FTP over TLS are deliberately unavailable.

`APPE` appends to the current virtual file while retaining each captured artifact in quarantine. `TYPE A` converts between local line endings and FTP NVT ASCII, and `ABOR` can interrupt a pending or active passive transfer without closing the control session.

## Telemetry

Events are appended to `data/events.jsonl`:

```json
{"timestamp":"2026-07-17T16:42:18Z","eventType":"AUTH_ATTEMPT","sessionId":"...","sourceIp":"192.0.2.45","username":"ftpuser","presentedPassword":"87654321","accepted":true,"passwordRank":30,"profile":"TRANSFER"}
```

```json
{"timestamp":"2026-07-17T16:43:01Z","eventType":"UPLOAD","sessionId":"...","sourceIp":"192.0.2.45","username":"ftpuser","command":"STOR","path":"/incoming/probe.txt","bytes":2941,"sha256":"...","quarantineFile":"...","status":"CAPTURED"}
```

Attempted passwords are intentionally captured in plaintext because they are honeypot telemetry. Protect the data directory, restrict operator access, and treat accidental use of real credentials as sensitive data. The active log rotates at the configured size and retains a bounded number of archives.

## Upload quarantine

Uploaded bytes are written beneath `data/quarantine/<session-id>/`. The virtual tree receives only a placeholder and metadata mapping, allowing the FTP client to list and retrieve the captured upload during the running process.

TRAP21 never executes, parses, unpacks, or forwards uploaded content. Deleting a file through FTP removes its virtual presence but preserves the captured quarantine artifact. Quarantine growth is bounded by total bytes and file count; expired historical artifacts are pruned according to the retention period.

## Configuration

| Environment variable | Default | Purpose |
|---|---|---|
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

## Validation

```powershell
docker build --target test -t trap21:test .
docker build --target runtime -t trap21:latest .
```

The integration test starts the Java server on ephemeral ports and verifies:

- The exact FTP banner.
- Valid and invalid credentials.
- Account-specific directory visibility.
- Anonymous read-only access.
- `EPSV`, `LIST`, `RETR`, `STOR`, and append-correct `APPE` transfers.
- `TYPE A` line-ending conversion and an in-progress `ABOR`.
- Upload quarantine and exact SHA-256 telemetry values.
- Quarantine quotas, retention, and JSONL rotation.
- Absolute command deadlines and per-source session limits.
- JSONL credential and upload events.
- Virtual path normalization, symbolic-link rejection, and captured-file directory renames.

## Legal and operational safety

TRAP21 is a defensive monitoring tool. Before deployment:

1. Obtain written authorization for the network and address space.
2. Isolate the honeypot from production assets.
3. Deny unnecessary outbound traffic.
4. Do not reuse captured credentials against third-party systems.
5. Establish retention and incident-handling procedures for logs and uploads.

See [SECURITY.md](SECURITY.md) for the threat boundary and reporting process.

## License

MIT License. Copyright (c) 2026 Del Risco Technologies.
