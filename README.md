<h1 align="center">TRAP21</h1>

<p align="center">
  A Java 21 FTP honeypot for capturing credentials, activity, and uploads.
</p>

---

TRAP21 simulates a plaintext FTP service with deliberately weak credentials and decoy files. It records client activity as JSONL and quarantines uploads with SHA-256 hashes.

> Use TRAP21 only on systems and networks you own or have explicit permission to monitor. Captured credentials and uploads may be sensitive or hostile.

## Install

You need Git and Docker with Compose. Java 21 runs inside the container.

```bash
git clone https://github.com/delriscotechnologies/trap21.git
cd trap21
cp docker/.env.example docker/.env
docker compose --env-file docker/.env -f docker/compose.yml up --build
```

The default configuration binds only to localhost. Connect with the built-in decoy account:

```bash
curl --user "ftpuser:87654321" "ftp://127.0.0.1/pub/README.txt"
```

## What it does

- Accepts FTP connections on host port `21`.
- Supports passive transfers with `PASV` and `EPSV`.
- Exposes a small read-only decoy filesystem.
- Accepts uploads only under `/incoming`.
- Quarantines uploaded bytes without executing them.
- Records connections, USER/PASS login attempts, commands, downloads, uploads, and SHA-256 hashes as JSONL.

Active FTP and FTP over TLS are intentionally unavailable. Commands outside TRAP21's supported subset are rejected rather than emulated.

## Output

Compose stores persistent evidence in the `trap21-data` volume:

```text
/app/data/events.jsonl
/app/data/events.jsonl.1
/app/data/quarantine/<session-id>/...
```

View events while TRAP21 is running:

```bash
docker compose --env-file docker/.env -f docker/compose.yml exec trap21 tail -f /app/data/events.jsonl
```

The event log rotates at 16 MiB and keeps one archive. Uploads are limited to 10 MiB each; quarantine is limited to 256 MiB and 4096 files. Sessions expire after five minutes. Idle timeouts are 120 seconds for control connections and 15 seconds for data connections.

For an authorized remote deployment, change these values in `docker/.env`:

```dotenv
TRAP21_LISTEN_HOST=0.0.0.0
TRAP21_PUBLIC_HOST=<IPv4 reachable by FTP clients>
```

`TRAP21_PUBLIC_HOST` is the IPv4 address advertised by `PASV`. `EPSV` advertises only the passive port.

## Scope and limits

TRAP21 is a deliberately limited honeypot, not a production FTP server. The supplied container runs as a non-root user with a read-only root filesystem, dropped Linux capabilities, and `no-new-privileges`. Persistent writes are limited to the dedicated evidence volume; `/tmp` is an ephemeral 16 MiB tmpfs.

The FTP service does not execute uploads, provide a shell, proxy traffic, or expose host files. Passive data connections are accepted only from the same source address as the FTP control connection.

Docker deployment files are kept under `docker/`. Java source files are under `src/trap21/`.

Dependency versions are pinned and maintained manually.

See [SECURITY.md](SECURITY.md) for the intended security boundary.

## License

TRAP21 is available under the [MIT License](LICENSE).
