<h1 align="center">TRAP21</h1>

<p align="center">
  <strong>Port 21. Believable access. Actionable evidence.</strong>
</p>

<p align="center">
  A medium-interaction FTP honeypot that presents a decoy filesystem and records structured evidence.
</p>

---

TRAP21 exposes a deliberately weak FTP service for authorized observation. Selected credentials open role-specific views of a decoy filesystem, downloads serve seeded content, uploads are preserved in quarantine, and session activity is written as JSON Lines.

> [!CAUTION]
> TRAP21 is intentionally vulnerable at the decoy interface. Deploy it only on systems and networks you own or are explicitly authorized to monitor. Never mount host directories that contain real data, credentials, or executables.

## Install

You need Git and Docker with Compose. The default configuration publishes FTP only on the local machine.

```powershell
git clone https://github.com/delriscotechnologies/trap21.git
cd trap21
Copy-Item .env.example .env
docker compose up --build
```

On macOS or Linux, replace `Copy-Item` with `cp`.

In a second terminal, connect with a built-in decoy account:

```powershell
curl.exe --user "ftpuser:87654321" "ftp://127.0.0.1/"
```

The default deployment publishes:

- FTP control on `127.0.0.1:21`
- Passive data ports on `127.0.0.1:30000-30009`

## What it does

1. Accepts real FTP control connections over TCP.
2. Maps selected weak or anonymous credentials to a decoy access profile.
3. Presents a bounded virtual filesystem without exposing the host filesystem.
4. Serves seeded files and preserves uploaded bytes in quarantine.
5. Records connections, authentication attempts, commands, transfers, hashes, and session outcomes as JSONL events.

Transfers are passive-only. Active mode (`PORT` and `EPRT`) and FTP over TLS are intentionally unavailable.

## Output

Compose stores evidence in the named volume `trap21-data`. Inside the container, events are appended to `/app/data/events.jsonl` and uploads are stored under `/app/data/quarantine/<session-id>/`.

View events while the service is running:

```bash
docker compose exec trap21 tail -f /app/data/events.jsonl
```

| Signal | Recorded evidence |
| --- | --- |
| Connection | Source address and session lifecycle |
| Authentication | Presented username and password, acceptance, and profile |
| FTP activity | Commands, paths, downloads, and transfer status |
| Upload | Virtual path, byte count, SHA-256 hash, and quarantine location |

Authentication attempts may contain real passwords entered by visitors. Protect the evidence volume and restrict operator access.

## Configuration

See [docs/CONFIGURATION.md](docs/CONFIGURATION.md) for environment variables, built-in accounts, passive FTP settings, data limits, retention, and authorized remote deployment.

Changing `TRAP21_LISTEN_HOST` from `127.0.0.1` exposes the service beyond the local machine. Before doing so, set `TRAP21_PUBLIC_HOST` correctly and enforce network isolation, inbound filtering, and outbound denial.

## Scope and limits

| Boundary | Enforcement |
| --- | --- |
| Filesystem | Dedicated virtual root, rejected symbolic links, and bounded file and directory counts |
| Uploads | Size, file-count, retention, and total-quarantine limits |
| Sessions | Idle, command, data, absolute-lifetime, global, and per-source limits |
| Telemetry | Log rotation and per-session rate summaries for high-volume activity |
| Container | Non-root user, read-only root filesystem, no added Linux capabilities, and `no-new-privileges` |
| Execution | No shell, command execution, proxying, archive extraction, or malware execution |

CI uses synthetic integration tests and a local Docker smoke test. These checks do not validate internet-facing deployments or sustained hostile traffic.

Before remote deployment:

1. Obtain written authorization for the address and network.
2. Isolate the honeypot from production systems.
3. Deny unnecessary outbound traffic.
4. Never reuse captured credentials against another system.
5. Define evidence retention and incident-handling procedures.

Use `docker compose down` to stop the service without deleting the evidence volume. The command `docker compose down -v` permanently deletes that volume and should be used only when the evidence is no longer required.

See [SECURITY.md](SECURITY.md) for the threat boundary and reporting process.

## License

TRAP21 is available under the [MIT License](LICENSE).
