<h1 align="center">TRAP21</h1>

<p align="center">
  <strong>Port 21. Believable access. Actionable evidence.</strong>
</p>

<p align="center">
  A medium-interaction FTP honeypot built to look real and leave useful telemetry.
</p>

<p align="center">
  <a href="#quick-start">Quick Start</a> ·
  <a href="#inside-the-trap">Client View</a> ·
  <a href="#evidence-collected">Evidence</a> ·
  <a href="docs/CONFIGURATION.md">Configuration</a> ·
  <a href="SECURITY.md">Security</a>
</p>

---

TRAP21 makes an FTP endpoint look worth exploring. Selected weak credentials open role-aware views of a decoy filesystem, uploads are preserved in quarantine, and each session becomes structured JSON Lines evidence.

To the visitor, it behaves like an FTP server. To the operator, the FTP client is the entire interface—there is no web dashboard.

> [!CAUTION]
> TRAP21 is intentionally vulnerable. Deploy it only on systems and networks you own or are explicitly authorized to monitor. Never mount host directories containing real data, credentials, or executables.

## Quick Start

TRAP21 requires **Git** and **Docker with Compose**. Clone it, create the local environment file, and start the service:

```powershell
git clone https://github.com/delriscotechnologies/trap21.git
cd trap21
Copy-Item .env.example .env
docker compose up --build
```

On macOS or Linux, replace `Copy-Item` with `cp`.

The default deployment stays on the local machine:

- FTP control: `127.0.0.1:21`
- Passive data: `127.0.0.1:30000-30009`

Keep Docker Compose running and connect from a second terminal:

```powershell
curl.exe --user "ftpuser:87654321" "ftp://127.0.0.1/"
```

See [Configuration](docs/CONFIGURATION.md) for remote deployment, data persistence, environment variables, and built-in accounts.

## Inside the Trap

A successful login looks like an ordinary managed FTP service:

```text
220 Authorized business use only
C: USER ftpuser
S: 331 Password required for ftpuser.
C: PASS 87654321
S: 230 User logged in, proceed.
C: PWD
S: 257 "/" is current directory.
```

Different account profiles receive different views of seeded enterprise-style directories and decoy documents. TRAP21 never reveals its product name, honeypot purpose, Java implementation, or operator branding to the FTP client.

## Evidence Collected

Events are appended to `data/events.jsonl` as the session unfolds:

| Signal | Captured detail |
| --- | --- |
| Connection | Source address and session lifecycle |
| Authentication | Presented username and password, acceptance, rank, and profile |
| FTP activity | Commands, paths, downloads, and transfer status |
| Upload | Path, byte count, SHA-256 hash, and quarantine location |

<details>
<summary><strong>View example JSONL events</strong></summary>

```json
{"timestamp":"2026-07-17T16:42:18Z","eventType":"AUTH_ATTEMPT","sessionId":"...","sourceIp":"192.0.2.45","username":"ftpuser","presentedPassword":"87654321","accepted":true,"passwordRank":30,"profile":"TRANSFER"}
```

```json
{"timestamp":"2026-07-17T16:43:01Z","eventType":"UPLOAD","sessionId":"...","sourceIp":"192.0.2.45","username":"ftpuser","command":"STOR","path":"/incoming/probe.txt","bytes":2941,"sha256":"...","quarantineFile":"...","status":"CAPTURED"}
```

</details>

Attempted passwords are intentionally stored in plaintext as honeypot telemetry. Protect the data directory, limit operator access, and treat accidental use of real credentials as sensitive data.

### Quarantined uploads

Uploaded bytes are stored beneath `data/quarantine/<session-id>/`. The virtual tree receives a placeholder and metadata mapping, allowing the client to list and retrieve the captured upload while TRAP21 is running.

TRAP21 never executes, parses, unpacks, or forwards uploaded content. Deleting a file through FTP removes its virtual presence but preserves the quarantine artifact. File count, total bytes, artifact age, and event-log growth are bounded by configurable retention limits.

## How TRAP21 Works

A session moves through five stages:

1. A client opens a real FTP control connection over TCP.
2. Selected weak or anonymous credentials map the client to an account profile.
3. The profile receives its own view of the decoy filesystem.
4. Passive transfers serve decoy content or preserve uploaded bytes in quarantine.
5. Commands, credentials, transfers, and hashes become JSONL evidence.

TRAP21 supports the common command set expected by standard FTP clients and scanners. Transfers are passive-only; active mode (`PORT` and `EPRT`) and FTP over TLS are deliberately unavailable.

<details>
<summary><strong>View supported FTP commands</strong></summary>

```text
USER PASS QUIT NOOP SYST FEAT HELP CLNT
PWD XPWD CWD XCWD CDUP XCUP
TYPE MODE STRU OPTS
PASV EPSV LIST NLST RETR STOR APPE
SIZE MDTM MKD XMKD RMD XRMD DELE
RNFR RNTO STAT ABOR
```

`APPE` appends to the current virtual file while preserving each captured artifact in quarantine. `TYPE A` converts between local line endings and FTP NVT ASCII. `ABOR` interrupts a pending or active passive transfer without closing the control session.

</details>

## Scope and Safeguards

TRAP21 is intentionally bounded:

| Boundary | Enforcement |
| --- | --- |
| Filesystem | Paths stay inside a dedicated virtual root; symbolic links are rejected |
| Passive data | Connections must originate from the control-session source address |
| Resources | Commands, deadlines, timeouts, uploads, retention, logs, and sessions are limited |
| Container | The supplied image runs without root privileges or Linux capabilities |
| Execution | No shell, command execution, proxying, archive extraction, or malware execution |

Before deployment:

1. Obtain written authorization for the network and address space.
2. Isolate the honeypot from production assets.
3. Deny unnecessary outbound traffic.
4. Do not reuse captured credentials against third-party systems.
5. Establish retention and incident-handling procedures for logs and uploads.

See [SECURITY.md](SECURITY.md) for the threat boundary and reporting process.

## License

TRAP21 is available under the [MIT License](LICENSE).
