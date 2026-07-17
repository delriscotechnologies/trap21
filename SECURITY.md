# Security policy

TRAP21 is intentionally vulnerable at the FTP protocol and decoy authorization layers. A successful login, weak credential, anonymous access, visible decoy file, or permitted quarantined upload is expected behavior and should not be reported as a vulnerability.

## Security boundary

Security issues include behavior that crosses the documented containment boundary, such as:

- Reading or modifying files outside `TRAP21_DATA_DIR`.
- Executing an uploaded file or attacker-controlled command.
- Escaping the virtual filesystem through paths, links, or races.
- Using TRAP21 as an outbound proxy or relay.
- Bypassing upload, timeout, or concurrent-session limits in a way that compromises the host.
- Exposing operator-only branding or internal telemetry through FTP.

## Reporting

Report security issues privately through the repository's GitHub Security Advisory interface. Include a minimal reproduction, affected version or commit, expected boundary, and observed impact. Do not include real credentials, malicious binaries, or sensitive third-party data in a public issue.

## Deployment guidance

- Run the supplied container as the non-root `trap21` user.
- Retain `no-new-privileges`, `cap_drop: ALL`, and the read-only root filesystem.
- Mount only the dedicated `data` directory.
- Apply network egress controls outside the container.
- Never expose Docker Engine, the host filesystem, or production credentials to the container.
- Assume `events.jsonl` and quarantine artifacts contain sensitive or hostile content.
