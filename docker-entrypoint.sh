#!/bin/sh
set -eu

umask 077

# Harden existing evidence in the supported container deployment and ensure
# newly created telemetry and quarantine artifacts inherit restrictive modes.
find /app/data -type d -exec chmod 700 {} +
find /app/data -type f -exec chmod 600 {} +

exec java -jar /app/trap21.jar "$@"
