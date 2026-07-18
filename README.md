<h1 align="center">TRAP21</h1>

<p align="center">
  A medium-interaction FTP deception honeypot for believable sessions, captured uploads, and structured telemetry.
</p>

<p align="center">
  <a href="#quick-start">Quick Start</a> ·
  <a href="#what-the-client-sees">Client View</a> ·
  <a href="#what-you-capture">Evidence</a> ·
  <a href="docs/CONFIGURATION.md">Configuration</a> ·
  <a href="SECURITY.md">Security</a>
</p>

---

TRAP21 turns an FTP endpoint into a believable managed-file-transfer gateway. It accepts selected weak credentials, presents each account with a role-aware decoy filesystem, captures uploaded files in quarantine, and records every session as JSON Lines telemetry.

Everything happens through a real FTP client. There is no web dashboard, operator branding, or honeypot disclosure on the wire.

> [!CAUTION]
> TRAP21 is intentionally vulnerable. Deploy it only on systems and networks you own or are explicitly authorized to monitor. Never mount host directories containing real data, credentials, or executables.

## Quick Start

TRAP21 requires **Git** and **Docker with Compose**. Clone the repository and enter the project directory:

```powershell
git clone https://github.com/delriscotechnologies/trap21.git
cd trap21
```

Create the local environment file and start the service:

```powershell
Copy-Item .env.example .env
docker compose up --build
```

On macOS or Linux, use `cp .env.example .env` instead of `Copy-Item`.

The default deployment is available only from the local machine:

- FTP control on `127.0.0.1:21`
- Passive data on `127.0.0.1:30000-30009`

Keep Docker Compose running and open a second terminal to test the built-in transfer account:

```powershell
curl.exe --user "ftpuser:87654321" "ftp://127.0.0.1/"
curl.exe --user "ftpuser:87654321" "ftp://127.0.0.1/pub/README.txt"
```

When finished, stop the running process with **Ctrl+C**, then remove the Compose resources:

```powershell
docker compose down
```

See [Configuration](docs/CONFIGURATION.md) for remote deployment, data persistence, environment variables, and built-in accounts.

## What the Client Sees

```text
220 Authorized business use only
C: USER ftpuser
S: 331 Password required for ftpuser.
C: PASS 87654321
S: 230 User logged in, proceed.
C: PWD
S: 257 "/" is current directory.
```

TRAP21 does not expose its product name, honeypot purpose, Java implementation, or operator branding to the FTP client.

## What You Capture

### Session telemetry

Connections, credentials, commands, downloads, and uploads are appended to `data/events.jsonl`:

```json
{"timestamp":"2026-07-17T16:42:18Z","eventType":"AUTH_ATTEMPT","sessionId":"...","sourceIp":"192.0.2.45","username":"ftpuser","presentedPassword":"87654321","accepted":true,"passwordRank":30,"profile":"TRANSFER"}
```

```json
{"timestamp":"2026-07-17T16:43:01Z","eventType":"UPLOAD","sessionId":"...","sourceIp":"192.0.2.45","username":"ftpuser","command":"STOR","path":"/incoming/probe.txt","bytes":2941,"sha256":"...","quarantineFile":"...","status":"CAPTURED"}
```

Attempted passwords are intentionally captured in plaintext because they are honeypot telemetry. Protect the data directory, restrict operator access, and treat accidental use of real credentials as sensitive data. The active log rotates at the configured size and retains a bounded number of archives.

### Uploaded files

Uploaded bytes are stored beneath `data/quarantine/<session-id>/`. The virtual tree receives a placeholder and metadata mapping so the FTP client can list and retrieve the captured upload while the process is running.

TRAP21 never executes, parses, unpacks, or forwards uploaded content. Deleting a file through FTP removes its virtual presence but preserves the quarantine artifact. Total bytes, file count, and artifact age are bounded by configurable retention limits.

## How It Works

A session moves through five stages:

1. A client opens a real FTP control connection over TCP.
2. Selected weak or anonymous credentials map the client to an account profile.
3. The profile receives its own view of seeded enterprise-style directories and decoy documents.
4. Passive transfers use `PASV` or `EPSV`; uploads are preserved in quarantine.
5. Session activity and captured-file hashes are written as JSONL evidence.

### FTP command coverage

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

## Development

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

## Scope and Safeguards

TRAP21 is intentionally bounded:

- Virtual paths are normalized inside a dedicated data root, with symbolic links rejected.
- Passive data connections must originate from the control-session source address.
- Command length, command deadlines, idle timeouts, data timeouts, uploads, and concurrent sessions are limited.
- Quarantine growth, retention, and JSONL rotation are bounded.
- The supplied container runs without root privileges or Linux capabilities.
- There is no shell, command execution, proxying, archive extraction, or malware execution.

Before deployment:

1. Obtain written authorization for the network and address space.
2. Isolate the honeypot from production assets.
3. Deny unnecessary outbound traffic.
4. Do not reuse captured credentials against third-party systems.
5. Establish retention and incident-handling procedures for logs and uploads.

See [SECURITY.md](SECURITY.md) for the threat boundary and reporting process.

## License

TRAP21 is available under the [MIT License](LICENSE).
