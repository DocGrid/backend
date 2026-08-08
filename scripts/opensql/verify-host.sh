#!/usr/bin/env bash

set -euo pipefail

# 이 Script는 공개 가능한 지원 환경·호환 Version만 확인하며 제품 식별·License·Topology 판정을 대신하지 않는다.
required_variables=(
  OPENSQL_DB_HOST
  OPENSQL_DB_PORT
  OPENSQL_DB_NAME
  OPENSQL_DB_USER
  OPENSQL_DB_PASSWORD
  OPENSQL_DB_SSLMODE
  OPENSQL_INSTALL_MODE
)

for variable_name in "${required_variables[@]}"; do
  if [[ -z "${!variable_name:-}" ]]; then
    echo "필수 공식 OpenSQL 환경 변수가 비어 있습니다: ${variable_name}" >&2
    exit 1
  fi
done

# 1. 공식 지원 OS와 Architecture가 아니면 PostgreSQL 호환 DB여도 공식 결과로 인정하지 않는다.
source /etc/os-release
if [[ "${ID:-}" != "rocky" || "${VERSION_ID:-}" != "9.7" ]]; then
  echo "지원되지 않는 OS입니다. Rocky Linux 9.7이 필요합니다." >&2
  exit 1
fi
if [[ "$(uname -m)" != "x86_64" ]]; then
  echo "지원되지 않는 Architecture입니다. x86_64가 필요합니다." >&2
  exit 1
fi

# 2. 실행자가 공급사 설치 기록으로 확인한 Single Mode 표식을 요구하고 HA 검증과 구분한다.
if [[ "${OPENSQL_INSTALL_MODE}" != "single" ]]; then
  echo "호환성 검증은 OpenSQL Single 설치 표식에서만 실행할 수 있습니다." >&2
  exit 1
fi

# 3. Password는 표준 libpq 환경으로만 전달하고 Command·성공 출력에는 포함하지 않는다.
export PGHOST="${OPENSQL_DB_HOST}"
export PGPORT="${OPENSQL_DB_PORT}"
export PGDATABASE="${OPENSQL_DB_NAME}"
export PGUSER="${OPENSQL_DB_USER}"
export PGPASSWORD="${OPENSQL_DB_PASSWORD}"
export PGSSLMODE="${OPENSQL_DB_SSLMODE}"
connect_timeout_seconds="${OPENSQL_CONNECT_TIMEOUT_SECONDS:-10}"
if [[ ! "${connect_timeout_seconds}" =~ ^[1-9][0-9]*$ ]] || (( connect_timeout_seconds > 30 )); then
  echo "OPENSQL_CONNECT_TIMEOUT_SECONDS는 1~30초 정수여야 합니다." >&2
  exit 1
fi
export PGCONNECT_TIMEOUT="${connect_timeout_seconds}"

# 4. 사용자 psqlrc와 Pager를 배제하고 실패 상세가 접속 식별자를 Log에 노출하지 않게 격리한다.
psql_error_file="$(mktemp)"
trap 'rm -f "${psql_error_file}"' EXIT
psql_options=(-X --pset=pager=off --tuples-only --no-align)

if ! server_version="$(psql "${psql_options[@]}" --command "SHOW server_version" 2>"${psql_error_file}")"; then
  echo "OpenSQL 연결 또는 server_version 조회에 실패했습니다." >&2
  exit 1
fi
if ! vector_version="$(psql "${psql_options[@]}" --command \
  "SELECT extversion FROM pg_extension WHERE extname = 'vector'" 2>"${psql_error_file}")"; then
  echo "OpenSQL 연결 또는 pgvector Version 조회에 실패했습니다." >&2
  exit 1
fi

# 5. 지원 기준 Version을 정확히 확인하되 OpenSQL 제품 판정은 외부 설치 증거로 별도 수행한다.
if [[ "${server_version}" != 17.8* ]]; then
  echo "지원되지 않는 OpenSQL PostgreSQL Version입니다: ${server_version}" >&2
  exit 1
fi
if [[ "${vector_version}" != "0.8.1" ]]; then
  echo "지원되지 않는 pgvector Version입니다: ${vector_version}" >&2
  exit 1
fi

echo "OpenSQL 지원 환경 호환성 preflight 통과"
echo "OS=Rocky Linux ${VERSION_ID}, architecture=$(uname -m), declared_mode=${OPENSQL_INSTALL_MODE}"
echo "server_version=${server_version}, pgvector=${vector_version}"
