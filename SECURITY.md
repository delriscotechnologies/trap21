# Security policy

TRAP21 is intentionally vulnerable at the FTP protocol and decoy authorization layers. A successful login, weak credential, anonymous access, visible decoy file, permitted quarantined upload, or absence of authentication throttling is expected behavior and should not be reported as a vulnerability.

The design principle is: vulnerable by design at the decoy interface, bounded by design at the containment and host boundary.

## Security boundary

Security issues include behavior that crosses the documented containment boundary, such as:

- Reading or modifying files outside `TRAP21_DATA_DIR`.
- Executing an uploaded file or attacker-controlled command.
- Escaping the virtual filesystem through paths, links, or races.
- Using TRAP21 as an outbound proxy or relay.
- Bypassing upload, timeout, event-log, or concurrent-session limits in a way that compromises the host.
- Exposing operator-only branding or internal telemetry through FTP.
- Making captured credentials or uploaded artifacts readable by unintended local users in the supported container deployment.

A passive data connection that waits until `TRAP21_DATA_TIMEOUT` expires is expected protocol behavior. It becomes a security issue only if the configured timeout or session limits can be bypassed to create unbounded host impact.

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
- Keep quarantine, upload, event-log, timeout, global-session, and per-source-session limits enabled.
- Treat `TRAP21_LISTEN_HOST=0.0.0.0` as an explicit exposure decision; the default Compose binding is localhost only.
