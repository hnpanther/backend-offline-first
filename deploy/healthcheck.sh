#!/bin/bash
# The container's health probe, without curl.
#
# curl was in this image for exactly one line — the HEALTHCHECK — and it brought 23 packages
# with it (libcurl, GnuTLS, libldap, Cyrus SASL, MIT Kerberos, libssh2, …), each of which an
# image scan then reports against this image. Bash can make the same request by itself through
# /dev/tcp, so the probe costs no package at all.
#
# Readiness, not liveness and not /api/health: only this endpoint includes the database, so a
# container cannot report healthy while PostgreSQL is unreachable (docs/deployment.md).
#
# Exit 0 only for "HTTP/1.x 200". Anything else — connection refused while the JVM is still
# starting, a 503 while the database is down, no answer within the timeout — is unhealthy.

port="${SERVER_PORT:-8081}"

exec 3<>"/dev/tcp/127.0.0.1/${port}" || exit 1
printf 'GET /actuator/health/readiness HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n' >&3 || exit 1
IFS= read -r -t 4 status <&3 || exit 1
exec 3<&- 3>&-

case "$status" in
    HTTP/1.?" 200"*) exit 0 ;;
    *) exit 1 ;;
esac
