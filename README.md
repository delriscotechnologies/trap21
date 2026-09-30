<h1 align="center">TRAP21</h1>

<p align="center">
  A small FTP honeypot for capturing credentials, activity, and uploaded files.
</p>

---

TRAP21 exposes an intentionally weak plaintext FTP service backed by a small decoy filesystem. The built-in weak credential is deliberate. Visitors can browse decoy files, download them, and upload files to `/incoming`; uploads are quarantined and recorded with SHA-256 hashes in JSONL telemetry.

> [!CAUTION]
> Run TRAP21 only on systems and networks you own or are explicitly authorized to monitor. Captured credentials and uploads may be sensitive or hostile.

## Install

You need Git and Docker with Compose.

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
docker compose --env-file docker/.env -f docker/compose.yml exec trap21 tail -f /app/data/events.jsonl
```

The active event log rotates at 16 MiB and keeps one previous archive. Individual uploads are limited to 10 MiB. Quarantine is limited to 256 MiB and 4096 files. FTP sessions have a five-minute maximum lifetime in addition to the idle timeout.

For an authorized remote deployment, change these values in `docker/.env`:

```dotenv
TRAP21_LISTEN_HOST=0.0.0.0
TRAP21_PUBLIC_HOST=<IPv4 reachable by FTP clients>
```

`TRAP21_PUBLIC_HOST` is the IPv4 address advertised by `PASV`. `EPSV` advertises only the passive port.

## Scope and limits

TRAP21 is a deliberately limited honeypot, not a production FTP server. The supplied container runs as a non-root user with a read-only root filesystem, dropped Linux capabilities, and `no-new-privileges`. Persistent writes are limited to the dedicated evidence volume; `/tmp` is an ephemeral 16 MiB tmpfs.

The FTP service does not execute uploads, provide a shell, proxy traffic, or expose host files. Passive data connections are accepted only from the same source address as the FTP control connection.

Docker deployment files are kept under `docker/`. 

See [SECURITY.md](SECURITY.md) for the intended security boundary.

## License

TRAP21 is available under the [MIT License](LICENSE).
