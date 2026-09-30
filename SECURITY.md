# Security policy

TRAP21 is intentionally weak at the decoy FTP interface. The built-in weak credential, plaintext FTP, captured passwords, downloadable decoy files, and quarantined uploads are expected behavior.

A security issue is behavior that crosses the containment boundary, including:

- reading or modifying host files outside the dedicated TRAP21 data directory;
- executing an uploaded file or attacker-controlled command;
- escaping the quarantine path;
- using TRAP21 as an outbound proxy or relay;
- bypassing the upload or quarantine limits in a way that compromises the host;
- exposing captured evidence to unintended local users in the supplied container deployment.

The supported container runs as a non-root user with a read-only root filesystem, dropped Linux capabilities, `no-new-privileges`, a dedicated writable evidence volume, and an ephemeral `/tmp` tmpfs. Keep those controls intact and apply network egress restrictions outside the container for remote deployments.

TRAP21 stores presented FTP passwords and uploaded files as evidence. Treat the evidence volume as sensitive and potentially hostile. Never execute captured files and never reuse captured credentials against another system.

Report genuine boundary failures privately through the repository's GitHub Security Advisory interface. Do not place real credentials, malicious binaries, or sensitive third-party data in public issues.
