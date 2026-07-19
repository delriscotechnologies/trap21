# Security policy

TRAP21 is intentionally vulnerable at the FTP protocol and decoy authorization layers. A successful login, weak credential, anonymous access, visible decoy file, permitted quarantined upload, or absence of authentication throttling is expected behavior and should not be reported as a vulnerability.

The design principle is: vulnerable by design at the decoy interface, bounded by design at the containment and host boundary.

## Security boundary

Security issues include behavior that crosses the documented containment boundary, such as:

- Reading or modifying files outside `TRAP21_DATA_DIR`.
- Executing an uploaded file or attacker-controlled command.
- Escaping the virtual filesystem through paths, links, or races.
- Using TRAP21 as an outbound proxy or relay.
- Bypassing upload, quarantine, VFS file/directory, passive-listener, timeout, event-log, or concurrent-session limits in a way that compromises the host.
- Exposing operator-only branding or internal telemetry through FTP.
- Making captured credentials or uploaded artifacts readable by unintended local users in the supported container deployment.

An independent watchdog closes the control socket, passive listener, and active transfer when `TRAP21_MAX_SESSION_SECONDS` expires. `TRAP21_IDLE_TIMEOUT` bounds inactivity and `TRAP21_DATA_TIMEOUT` bounds establishment and use of passive data connections. A bypass of those limits is a security issue.

TRAP21 does not send the FTP banner unless the `CONNECT` event has been written and flushed successfully. New sessions are closed while telemetry persistence is unavailable.

## Reporting

Report security issues privately through the repository's GitHub Security Advisory interface. Include a minimal reproduction, affected version or commit, expected boundary, and observed impact. Do not include real credentials, malicious binaries, or sensitive third-party data in a public issue.

## Deployment guidance

- Run the supplied container as the non-root `trap21` user.
- Retain `no-new-privileges`, `cap_drop: ALL`, and the read-only root filesystem.
- Retain the supplied entrypoint so the evidence tree uses owner-only permissions and a restrictive umask.
- When running the JAR directly, configure equivalent operating-system permissions or ACLs for `TRAP21_DATA_DIR`.
- Mount only the dedicated `data` directory.
- Apply network egress controls outside the container.
- Never expose Docker Engine, the host filesystem, or production credentials to the container.
- Assume `events.jsonl` and quarantine artifacts contain sensitive or hostile content.
- Keep quarantine, upload, VFS file/directory, event-log, timeout, global-session, and per-source-session limits enabled.
- Treat `TRAP21_LISTEN_HOST=0.0.0.0` as an explicit exposure decision; the default Compose binding is localhost only.
