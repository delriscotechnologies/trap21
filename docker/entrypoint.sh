#!/bin/sh
set -eu
umask 077
find /app/data -type d -exec chmod 700 {} +
find /app/data -type f -exec chmod 600 {} +
exec java -jar /app/trap21.jar "$@"
