#!/usr/bin/env bash

set -euo pipefail

# 공식 판정은 공급사 지원 Matrix와 Single 설치 계약을 모두 만족한 Host에서만 진행한다.
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

# 2. 대회 안내의 Single 설치만 허용하고 HA 관련 구성을 공식 검증 범위에서 제외한다.
if [[ "${OPENSQL_INSTALL_MODE}" != "single" ]]; then
  echo "공식 검증은 OpenSQL Single 설치에서만 실행할 수 있습니다." >&2
  exit 1
fi

# 3. Password는 표준 libpq 환경으로만 전달하고 Command·성공 출력에는 포함하지 않는다.
export PGHOST="${OPENSQL_DB_HOST}"
export PGPORT="${OPENSQL_DB_PORT}"
export PGDATABASE="${OPENSQL_DB_NAME}"
export PGUSER="${OPENSQL_DB_USER}"
export PGPASSWORD="${OPENSQL_DB_PASSWORD}"
export PGSSLMODE="${OPENSQL_DB_SSLMODE}"

server_version="$(psql --tuples-only --no-align --command "SHOW server_version")"
vector_version="$(psql --tuples-only --no-align --command \
  "SELECT extversion FROM pg_extension WHERE extname = 'vector'")"

# 4. 지원 기준 Version을 정확히 확인해 로컬 PostgreSQL 결과와 공식 OpenSQL 결과를 구분한다.
if [[ "${server_version}" != 17.8* ]]; then
  echo "지원되지 않는 OpenSQL PostgreSQL Version입니다: ${server_version}" >&2
  exit 1
fi
if [[ "${vector_version}" != "0.8.1" ]]; then
  echo "지원되지 않는 pgvector Version입니다: ${vector_version}" >&2
  exit 1
fi

echo "OpenSQL 공식 Host preflight 통과"
echo "OS=Rocky Linux ${VERSION_ID}, architecture=$(uname -m), mode=${OPENSQL_INSTALL_MODE}"
echo "server_version=${server_version}, pgvector=${vector_version}"
