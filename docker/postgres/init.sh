#!/bin/bash
# One Postgres container, one database + role per service (isolated data boundaries).
set -euo pipefail

create_db() {
  local db="$1" user="$2" pass="$3"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<SQL
CREATE ROLE "${user}" LOGIN PASSWORD '${pass}';
CREATE DATABASE "${db}" OWNER "${user}";
SQL
}

create_db "tenant_db"       "tenant"       "${TENANT_DB_PASSWORD:-tenant}"
create_db "notification_db" "notification" "${NOTIFICATION_DB_PASSWORD:-notification}"
create_db "analytics_db"    "analytics"    "${ANALYTICS_DB_PASSWORD:-analytics}"
create_db "delivery_db"     "delivery"     "${DELIVERY_DB_PASSWORD:-delivery}"
