#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
OpenSQL Single 장애 복구 관통 검증

이 Script는 OpenSQL을 직접 중단하거나 재기동하지 않는다. 정확한 Test 인스턴스를 확인한 뒤
별도 Terminal에서 공급사 절차로 중단·재기동하고, 이 Script는 장애 감지와 복구 결과만 검증한다.

필수 환경 변수:
  APP_URL                              실행 중인 DocGrid Base URL
  USER_TOKEN                           문서 업로드·검색 사용자 Bearer Token
  ADMIN_TOKEN                          인덱싱 관리자 Bearer Token
  RECOVERY_TEST_FILE                   고유 검색 문구가 포함된 PDF/DOCX/TXT/MD 경로
  RECOVERY_SEARCH_MARKER               문서에 포함된 고유 검색 문구
  OPENSQL_DB_HOST                      Test OpenSQL Host
  OPENSQL_DB_PORT                      Test OpenSQL Port
  OPENSQL_DB_NAME                      Test Database
  OPENSQL_DB_SCHEMA                    Test Schema
  OPENSQL_DB_USER                      Test Database User
  OPENSQL_DB_PASSWORD                  Test Database Password
  OPENSQL_DB_SSLMODE                   libpq SSL Mode
  OPENSQL_INSTALL_MODE                 반드시 single
  OPENSQL_RECOVERY_EXPECTED_DB_NAME    잘못된 DB 중단 방지를 위한 예상 Database 이름
  OPENSQL_RECOVERY_CONFIRM             INTERRUPT_ISOLATED_OPENSQL_SINGLE

선택 환경 변수:
  RECOVERY_PROCESSING_TIMEOUT_SECONDS  PROCESSING 대기 제한, 기본 120
  RECOVERY_DB_DOWN_TIMEOUT_SECONDS     DB 중단 감지 제한, 기본 60
  RECOVERY_OUTAGE_HOLD_SECONDS         장애 유지 시간, 기본 40
  RECOVERY_DB_UP_TIMEOUT_SECONDS       DB 재기동 감지 제한, 기본 120
  RECOVERY_INDEXED_TIMEOUT_SECONDS     INDEXED 대기 제한, 기본 600
  RECOVERY_POLL_INTERVAL_SECONDS       Polling 간격, 기본 2
  OPENSQL_CONNECT_TIMEOUT_SECONDS      DB 연결 확인 제한, 기본 5
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi
if (( $# > 0 )); then
  usage >&2
  exit 1
fi

fail() {
  echo "검증 실패: $1" >&2
  exit 1
}

require_command() {
  local command_name="$1"
  command -v "${command_name}" >/dev/null 2>&1 \
    || fail "필수 Command를 찾을 수 없습니다: ${command_name}"
}

require_variable() {
  local variable_name="$1"
  [[ -n "${!variable_name:-}" ]] \
    || fail "필수 환경 변수가 비어 있습니다: ${variable_name}"
}

validate_seconds() {
  local variable_name="$1"
  local value="$2"
  if [[ ! "${value}" =~ ^[1-9][0-9]*$ ]] || (( value > 3600 )); then
    fail "${variable_name}는 1~3600초 정수여야 합니다."
  fi
}

now_utc() {
  date -u '+%Y-%m-%dT%H:%M:%SZ'
}

script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
consistency_sql="${script_directory}/verify-single-recovery.sql"

required_commands=(curl jq psql pg_isready)
for command_name in "${required_commands[@]}"; do
  require_command "${command_name}"
done

required_variables=(
  APP_URL
  USER_TOKEN
  ADMIN_TOKEN
  RECOVERY_TEST_FILE
  RECOVERY_SEARCH_MARKER
  OPENSQL_DB_HOST
  OPENSQL_DB_PORT
  OPENSQL_DB_NAME
  OPENSQL_DB_SCHEMA
  OPENSQL_DB_USER
  OPENSQL_DB_PASSWORD
  OPENSQL_DB_SSLMODE
  OPENSQL_INSTALL_MODE
  OPENSQL_RECOVERY_EXPECTED_DB_NAME
  OPENSQL_RECOVERY_CONFIRM
)
for variable_name in "${required_variables[@]}"; do
  require_variable "${variable_name}"
done

[[ -f "${RECOVERY_TEST_FILE}" ]] || fail "RECOVERY_TEST_FILE이 일반 File이 아니거나 존재하지 않습니다."
[[ -r "${RECOVERY_TEST_FILE}" ]] || fail "RECOVERY_TEST_FILE을 읽을 수 없습니다."
[[ -f "${consistency_sql}" ]] || fail "정합성 검증 SQL을 찾을 수 없습니다."
[[ "${OPENSQL_INSTALL_MODE}" == "single" ]] \
  || fail "이 검증은 공식 OpenSQL Single 구성에서만 실행할 수 있습니다."
[[ "${OPENSQL_RECOVERY_EXPECTED_DB_NAME}" == "${OPENSQL_DB_NAME}" ]] \
  || fail "예상 Database 이름과 실제 대상이 다릅니다. 중단 대상을 다시 확인하세요."
[[ "${OPENSQL_RECOVERY_CONFIRM}" == "INTERRUPT_ISOLATED_OPENSQL_SINGLE" ]] \
  || fail "격리 Test OpenSQL 중단 확인 문구가 일치하지 않습니다."
[[ "${APP_URL}" =~ ^https?://[^[:space:]]+$ ]] \
  || fail "APP_URL은 http 또는 https URL이어야 합니다."
[[ "${OPENSQL_DB_PORT}" =~ ^[1-9][0-9]{0,4}$ ]] && (( OPENSQL_DB_PORT <= 65535 )) \
  || fail "OPENSQL_DB_PORT는 유효한 TCP Port여야 합니다."
[[ "${OPENSQL_DB_SCHEMA}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] \
  || fail "OPENSQL_DB_SCHEMA는 안전한 PostgreSQL 식별자 형식이어야 합니다."

processing_timeout="${RECOVERY_PROCESSING_TIMEOUT_SECONDS:-120}"
db_down_timeout="${RECOVERY_DB_DOWN_TIMEOUT_SECONDS:-60}"
outage_hold_seconds="${RECOVERY_OUTAGE_HOLD_SECONDS:-40}"
db_up_timeout="${RECOVERY_DB_UP_TIMEOUT_SECONDS:-120}"
indexed_timeout="${RECOVERY_INDEXED_TIMEOUT_SECONDS:-600}"
poll_interval="${RECOVERY_POLL_INTERVAL_SECONDS:-2}"
connect_timeout="${OPENSQL_CONNECT_TIMEOUT_SECONDS:-5}"

validate_seconds RECOVERY_PROCESSING_TIMEOUT_SECONDS "${processing_timeout}"
validate_seconds RECOVERY_DB_DOWN_TIMEOUT_SECONDS "${db_down_timeout}"
validate_seconds RECOVERY_OUTAGE_HOLD_SECONDS "${outage_hold_seconds}"
validate_seconds RECOVERY_DB_UP_TIMEOUT_SECONDS "${db_up_timeout}"
validate_seconds RECOVERY_INDEXED_TIMEOUT_SECONDS "${indexed_timeout}"
validate_seconds RECOVERY_POLL_INTERVAL_SECONDS "${poll_interval}"
validate_seconds OPENSQL_CONNECT_TIMEOUT_SECONDS "${connect_timeout}"

APP_URL="${APP_URL%/}"
export PGHOST="${OPENSQL_DB_HOST}"
export PGPORT="${OPENSQL_DB_PORT}"
export PGDATABASE="${OPENSQL_DB_NAME}"
export PGUSER="${OPENSQL_DB_USER}"
export PGPASSWORD="${OPENSQL_DB_PASSWORD}"
export PGSSLMODE="${OPENSQL_DB_SSLMODE}"
export PGCONNECT_TIMEOUT="${connect_timeout}"

work_directory="$(mktemp -d)"
upload_response="${work_directory}/upload.json"
job_response="${work_directory}/job.json"
search_response="${work_directory}/search.json"
consistency_result="${work_directory}/consistency.txt"

cleanup() {
  rm -f "${upload_response}" "${job_response}" "${search_response}" "${consistency_result}"
  rmdir "${work_directory}" 2>/dev/null || true
}
trap cleanup EXIT

curl_common=(--silent --show-error --fail --connect-timeout 5 --max-time 30)

db_is_ready() {
  pg_isready \
    --host="${OPENSQL_DB_HOST}" \
    --port="${OPENSQL_DB_PORT}" \
    --dbname="${OPENSQL_DB_NAME}" \
    --username="${OPENSQL_DB_USER}" \
    --timeout="${connect_timeout}" >/dev/null 2>&1
}

fetch_job() {
  curl "${curl_common[@]}" \
    --header "Authorization: Bearer ${ADMIN_TOKEN}" \
    "${APP_URL}/admin/indexing-jobs/${job_id}" \
    >"${job_response}"
  jq -e '.success == true and .data.jobId != null' "${job_response}" >/dev/null
}

job_status() {
  jq -r '.data.status // empty' "${job_response}"
}

wait_for_processing() {
  local deadline=$(( $(date +%s) + processing_timeout ))
  local status
  while (( $(date +%s) <= deadline )); do
    fetch_job || fail "인덱싱 Job 상태를 조회하지 못했습니다."
    status="$(job_status)"
    case "${status}" in
      PROCESSING)
        return 0
        ;;
      INDEXED)
        fail "DB 장애 주입 전에 Job이 INDEXED가 됐습니다. 더 큰 Test 문서를 사용하세요."
        ;;
      FAILED)
        fail "DB 장애 주입 전에 Job이 FAILED가 됐습니다. Attempt와 Event를 확인하세요."
        ;;
    esac
    sleep "${poll_interval}"
  done
  fail "제한 시간 안에 Job이 PROCESSING이 되지 않았습니다."
}

wait_for_database_outage() {
  local deadline=$(( $(date +%s) + db_down_timeout ))
  local status
  while (( $(date +%s) <= deadline )); do
    if ! db_is_ready; then
      return 0
    fi
    if fetch_job; then
      status="$(job_status)"
      if [[ "${status}" == "INDEXED" ]]; then
        fail "OpenSQL 중단을 감지하기 전에 Job이 INDEXED가 됐습니다. 장애를 더 빨리 주입하세요."
      fi
    fi
    sleep 1
  done
  fail "제한 시간 안에 OpenSQL 중단을 감지하지 못했습니다. 중단 대상과 Network 경로를 확인하세요."
}

wait_for_database_recovery() {
  local deadline=$(( $(date +%s) + db_up_timeout ))
  while (( $(date +%s) <= deadline )); do
    if db_is_ready; then
      return 0
    fi
    sleep 1
  done
  fail "제한 시간 안에 OpenSQL 재기동을 감지하지 못했습니다."
}

wait_for_application_recovery() {
  local deadline=$(( $(date +%s) + db_up_timeout ))
  while (( $(date +%s) <= deadline )); do
    if fetch_job 2>/dev/null; then
      return 0
    fi
    sleep "${poll_interval}"
  done
  fail "DB는 응답하지만 같은 DocGrid Application에서 Job API가 회복되지 않았습니다."
}

wait_for_indexed() {
  local deadline=$(( $(date +%s) + indexed_timeout ))
  local status
  while (( $(date +%s) <= deadline )); do
    if fetch_job 2>/dev/null; then
      status="$(job_status)"
      echo "Job 상태: ${status}"
      case "${status}" in
        INDEXED)
          return 0
          ;;
        FAILED)
          fail "복구 후 Job이 최종 FAILED가 됐습니다. Attempt와 Event를 확인하세요."
          ;;
      esac
    fi
    sleep "${poll_interval}"
  done
  fail "제한 시간 안에 복구 Job이 INDEXED가 되지 않았습니다."
}

case "${RECOVERY_TEST_FILE##*.}" in
  pdf|PDF) file_mime_type="application/pdf" ;;
  docx|DOCX) file_mime_type="application/vnd.openxmlformats-officedocument.wordprocessingml.document" ;;
  txt|TXT) file_mime_type="text/plain" ;;
  md|MD) file_mime_type="text/markdown" ;;
  *) fail "지원하지 않는 RECOVERY_TEST_FILE 확장자입니다. PDF, DOCX, TXT, MD만 허용합니다." ;;
esac

# 1. 정확한 격리 DB와 두 번째 Terminal 준비를 실행자가 마지막으로 확인한다.
echo "OpenSQL Single 장애 복구 검증을 시작합니다."
echo "이 Script는 OpenSQL 중단·재기동 명령을 실행하지 않습니다."
echo "별도 Terminal에 공급사 절차의 정확한 중단·재기동 명령을 준비하세요."
read -r -p "격리 Test DB와 Application이 준비됐으면 Enter를 누르세요. "

db_is_ready || fail "검증 시작 전 OpenSQL에 연결할 수 없습니다."
started_at="$(now_utc)"

# 2. 고유 Marker가 포함된 문서를 접수하고 이후 검증에 사용할 식별자를 보관한다.
curl "${curl_common[@]}" \
  --request POST \
  --header "Authorization: Bearer ${USER_TOKEN}" \
  --form "file=@${RECOVERY_TEST_FILE};type=${file_mime_type}" \
  --form "title=OpenSQL Single 장애 복구 검증 ${started_at}" \
  --form "description=격리 환경 장애 주입 E2E" \
  --form "visibility=PRIVATE" \
  "${APP_URL}/api/documents" \
  >"${upload_response}"

jq -e '.success == true and .data.embeddingJobId != null' "${upload_response}" >/dev/null \
  || fail "문서 업로드 응답에 필요한 식별자가 없습니다."
document_id="$(jq -r '.data.documentId' "${upload_response}")"
document_version_id="$(jq -r '.data.documentVersionId' "${upload_response}")"
job_id="$(jq -r '.data.embeddingJobId' "${upload_response}")"

echo "업로드 완료: documentId=${document_id}, versionId=${document_version_id}, jobId=${job_id}"

# 3. Worker가 실제 Claim한 뒤 DB를 중단하도록 PROCESSING 전이를 기다린다.
wait_for_processing
lock_expires_at="$(jq -r '.data.lockExpiresAt // "unknown"' "${job_response}")"
initial_retry_count="$(jq -r '.data.retryCount // 0' "${job_response}")"
processing_at="$(now_utc)"
printf '\a'
echo "Job PROCESSING 확인: retryCount=${initial_retry_count}, lockExpiresAt=${lock_expires_at}"
echo "지금 별도 Terminal에서 정확한 Test OpenSQL을 중단하세요."

# 4. DB가 실제로 응답하지 않는지 확인하고 Lease 만료가 확실하도록 장애를 유지한다.
wait_for_database_outage
database_down_at="$(now_utc)"
echo "OpenSQL 중단 감지: ${database_down_at}"
echo "Lease 만료 증명을 위해 ${outage_hold_seconds}초 동안 장애 상태를 유지합니다."
sleep "${outage_hold_seconds}"
read -r -p "이제 별도 Terminal에서 같은 OpenSQL을 재기동한 뒤 Enter를 누르세요. "

# 5. DB와 기존 Application 연결이 순서대로 회복되는지 확인한다.
wait_for_database_recovery
database_up_at="$(now_utc)"
echo "OpenSQL 재기동 감지: ${database_up_at}"
wait_for_application_recovery
application_recovered_at="$(now_utc)"
echo "동일 Application의 Job API 회복 확인: ${application_recovered_at}"

# 6. Lease 복구가 실제 Retry와 최종 INDEXED로 수렴하는지 검증한다.
wait_for_indexed
indexed_at="$(now_utc)"
final_retry_count="$(jq -r '.data.retryCount // 0' "${job_response}")"
if (( final_retry_count <= initial_retry_count )); then
  fail "Job은 INDEXED지만 retryCount가 증가하지 않아 Lease 재처리를 증명하지 못했습니다."
fi

# 7. 장애 후 권한 경계를 거친 실제 Vector 검색이 같은 문서를 반환하는지 확인한다.
search_payload="$(jq -cn --arg marker "${RECOVERY_SEARCH_MARKER}" '{queryText: $marker, topK: 5}')"
curl "${curl_common[@]}" \
  --request POST \
  --header "Authorization: Bearer ${USER_TOKEN}" \
  --header "Content-Type: application/json" \
  --data "${search_payload}" \
  "${APP_URL}/search" \
  >"${search_response}"

jq -e --argjson document_id "${document_id}" \
  '.success == true and any(.data.results[]?; .documentId == $document_id)' \
  "${search_response}" >/dev/null \
  || fail "Vector 검색 결과에 복구한 문서가 없습니다. Marker와 유사도 기준을 확인하세요."

# 8. 최종 상태·Retry·Chunk·Embedding·Outbox 중복 불변식을 실제 OpenSQL에서 검사한다.
psql \
  -X \
  --pset=pager=off \
  --set=ON_ERROR_STOP=on \
  --set="db_schema=${OPENSQL_DB_SCHEMA}" \
  --set="document_id=${document_id}" \
  --set="document_version_id=${document_version_id}" \
  --set="job_id=${job_id}" \
  --file="${consistency_sql}" \
  >"${consistency_result}"

cat "${consistency_result}"
echo "OpenSQL Single 장애 복구 검증 PASS"
echo "startedAt=${started_at}"
echo "processingAt=${processing_at}"
echo "databaseDownAt=${database_down_at}"
echo "databaseUpAt=${database_up_at}"
echo "applicationRecoveredAt=${application_recovered_at}"
echo "indexedAt=${indexed_at}"
echo "jobId=${job_id}, initialRetryCount=${initial_retry_count}, finalRetryCount=${final_retry_count}"
