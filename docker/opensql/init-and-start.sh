#!/bin/bash
set -e

PGDATA="${PGDATA:-/var/lib/pgsql/14/data}"

# Temporarily start postgres (local socket only) for initialization
pg_ctl start -D "$PGDATA" -l /tmp/pg_init.log -o "-h ''" -w

# Create docgrid database if not exists
psql -d postgres -tc "SELECT 1 FROM pg_database WHERE datname = 'docgrid'" | grep -q 1 \
    || psql -d postgres -c "CREATE DATABASE docgrid OWNER docgrid;"

# Enable pgvector in docgrid database
psql -d docgrid -c "CREATE EXTENSION IF NOT EXISTS vector;"

# Allow TCP connections from any host (needed for host-machine Spring Boot)
grep -qxF "host all all 0.0.0.0/0 trust" "$PGDATA/pg_hba.conf" \
    || echo "host all all 0.0.0.0/0 trust" >> "$PGDATA/pg_hba.conf"

# Stop temp postgres cleanly before handing off
pg_ctl stop -D "$PGDATA" -m fast -w

# Start postgres in foreground (replaces this process)
exec postgres
