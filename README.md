<h1 align="center">TRAP21</h1>

<p align="center">
  A small FTP honeypot for capturing credentials, activity, and uploaded files.
</p>

---

TRAP21 exposes an intentionally weak FTP service backed by a small decoy filesystem. Visitors can browse seeded files, download decoys, and upload files to `/incoming`. Uploads are preserved in quarantine and recorded with SHA-256 hashes in JSONL telemetry.

> [!CAUTION]
> TRAP21 is intentionally vulnerable at the FTP interface. Run it only on systems and networks you own or are explicitly authorized to monitor. Captured credentials and uploads may be sensitive or hostile.

## Install

You need Git and Docker with Compose.

```bash
git clone https://github.com/delriscotechnologies/trap21.git
cd trap21
cp .env.example .env
docker compose up --build
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
- Records connections, presented credentials, commands, downloads, uploads, and SHA-256 hashes as JSONL.

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
docker compose exec trap21 tail -f /app/data/events.jsonl
```

The active event log rotates at 16 MiB and keeps one previous archive. Individual uploads are limited to 10 MiB. At startup, TRAP21 counts existing quarantine data and rejects a new capture if its tracked total would exceed 256 MiB.

For an authorized remote deployment, change both values in `.env`:

```dotenv
TRAP21_LISTEN_HOST=0.0.0.0
TRAP21_PUBLIC_HOST=<IPv4 reachable by FTP clients>
```

`TRAP21_PUBLIC_HOST` is the IPv4 address advertised by `PASV`. `EPSV` advertises only the passive port.

## Scope and limits

TRAP21 is a deliberately limited honeypot, not a production FTP server. It runs as a non-root container user with a read-only root filesystem, dropped Linux capabilities, and `no-new-privileges`. Persistent writes are limited to the dedicated evidence volume; `/tmp` is an ephemeral 16 MiB tmpfs.

The FTP service does not execute uploads, provide a shell, proxy traffic, or expose host files. Passive data connections are accepted only from the same source address as the FTP control connection.

See [SECURITY.md](SECURITY.md) for the intended security boundary.

## License

TRAP21 is available under the [MIT License](LICENSE).
