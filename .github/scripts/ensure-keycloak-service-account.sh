#!/usr/bin/env bash
#
# Ensure a confidential service-account client exists in the target Keycloak
# realm for FAM's callouts to nr-user-lookup-api, with the user-lookup client
# scopes assigned so its client_credentials token carries the SCOPE_*
# authorities that API enforces.
#
# Ported from nr-fsp-new/.github/scripts/ensure-keycloak-service-account.sh.
#
# Idempotent:
#   - creates the client only if it's missing (an existing client is left alone);
#   - assigns each required scope as a DEFAULT client scope (the PUT is a no-op
#     when already assigned) so the service-account token always includes them.
#
# The scopes themselves are NOT created here - nr-user-lookup-api owns them. This
# only wires our client to scopes that already exist in the shared realm, and
# errors if one is absent.
#
# Authenticates with a confidential admin service-account client
# (client_credentials) holding the realm-management `manage-clients` role. The
# realm and endpoints are derived from USER_LOOKUP_ISSUER_URI, so it works with
# or without an `/auth` base path.
#
# NOT the realm users sign in to. FAM's browser login lives in `standard`, but
# nr-user-lookup-api validates its callers against its own realm (`forests`) and
# only that realm defines the user-lookup client scopes below. The service
# account has to be created where the token will be checked, so this takes a
# separate issuer - and the admin client must live in that same realm, since
# `manage-clients` is realm-scoped. Deriving this from KEYCLOAK_ISSUER_URI
# instead would create the client in the login realm, where the scopes do not
# exist and the resulting token would be rejected as a wrong-issuer token.
#
# Required environment:
#   USER_LOOKUP_ISSUER_URI  realm nr-user-lookup-api trusts,
#                           e.g. https://test.loginproxy.gov.bc.ca/auth/realms/forests
#   KC_SA_CLIENT_ID         admin service-account client id (manage-clients)
#   KC_SA_CLIENT_SECRET     admin service-account client secret
# Optional:
#   FAM_CLIENT_ID         clientId of the service account to ensure
#                         (default: nr-fam-backend)
#
# Emits the resulting client id and secret as masked GitHub Actions step outputs
# (`client_id`, `client_secret`) for a later step to feed to the backend.
#
# Keep SCOPES in sync with what the FAM backend calls. UserLookupClient uses all
# three endpoints - IDIR search, IDIR detail, and Business BCeID - so all three
# scopes are required.
set -euo pipefail

: "${USER_LOOKUP_ISSUER_URI:?USER_LOOKUP_ISSUER_URI is required - the realm nr-user-lookup-api trusts, which is not the login realm}"
: "${KC_SA_CLIENT_ID:?KC_SA_CLIENT_ID is required}"
: "${KC_SA_CLIENT_SECRET:?KC_SA_CLIENT_SECRET is required}"

FAM_CLIENT_ID="${FAM_CLIENT_ID:-nr-fam-backend}"

SCOPES=(
  "user-lookup:idir:search"
  "user-lookup:idir:read"
  "user-lookup:business-bceid:read"
)

# --- network diagnostics ----------------------------------------------------
#
# These calls fail intermittently in CI and the failures were indistinguishable
# from a configuration fault: a dropped connection produced an empty body, which
# read as "no access_token", which was reported as "check the client id/secret".
# The retry is what stops a blip failing a deploy; the trace is what tells you
# which of the two happened when it fails anyway.
#
# A trace is written for EVERY call and printed only for one that finally fails.
# It is redacted first - see redact(). A raw trace of the token request contains
# the admin client_secret in the POST body, and every later request carries the
# admin bearer token in a header. Neither may reach a build log.
MAX_ATTEMPTS="${KC_MAX_ATTEMPTS:-4}"
CONNECT_TIMEOUT="${KC_CONNECT_TIMEOUT:-10}"
MAX_TIME="${KC_MAX_TIME:-60}"

trace_dir="$(mktemp -d)"
trap 'rm -rf "${trace_dir}"' EXIT

# Set by kc_curl for callers that check the status rather than the body.
http_code=""

# Masks anything that must not appear in a log, whatever curl put in the trace.
#
# Belt and braces with GitHub's own masking: it only masks values it was given
# as secrets, and the admin token below is minted at runtime.
redact() {
  sed -E \
    -e 's/(client_secret=)[^&[:space:]]*/\1***/g' \
    -e 's/("value"[[:space:]]*:[[:space:]]*")[^"]*/\1***/g' \
    -e 's/(Authorization:[[:space:]]*Bearer[[:space:]]+)[^[:space:]]*/\1***/gI' \
    -e 's/("access_token"[[:space:]]*:[[:space:]]*")[^"]*/\1***/g'
}

# Whether a response is worth trying again. 429 and 5xx are the shapes a shared
# gateway produces under load; a 4xx is our request being wrong and will be
# wrong next time too.
retryable_status() {
  case "$1" in
    429|500|502|503|504) return 0 ;;
    *) return 1 ;;
  esac
}

# curl with retries, timeouts and a redacted trace on final failure.
#
#   kc_curl <label> <curl args...>
#
# Writes the response body to stdout and the status to $http_code. The label
# names the trace file, so a failure says which call it was.
kc_curl() {
  local label="$1"; shift
  local trace="${trace_dir}/${label}.trace"
  local body="${trace_dir}/${label}.body"
  local attempt=1 rc delay

  while :; do
    set +e
    http_code="$(curl -sS \
      --connect-timeout "${CONNECT_TIMEOUT}" --max-time "${MAX_TIME}" \
      --trace-ascii "${trace}" --trace-time \
      -o "${body}" -w '%{http_code}' "$@")"
    rc=$?
    set -e

    if [ "${rc}" -eq 0 ] && ! retryable_status "${http_code}"; then
      cat "${body}"
      return 0
    fi

    if [ "${attempt}" -ge "${MAX_ATTEMPTS}" ]; then
      # All of this goes to stderr. Only the response body belongs on stdout:
      # callers either capture it with $( ) or discard it with >/dev/null, and
      # diagnostics written there are swallowed by the second and parsed as
      # JSON by the first.
      {
        if [ "${rc}" -ne 0 ]; then
          # A curl exit code, not an HTTP status: the request never completed.
          # Naming it separately is the point - this is the case that used to
          # be reported as bad credentials.
          echo "::error::${label}: the connection failed after ${attempt} attempt(s) (curl exit ${rc}). This is a network failure, not a credential problem."
        else
          echo "::error::${label}: HTTP ${http_code} after ${attempt} attempt(s)."
        fi

        echo "::group::curl trace for ${label} (redacted)"
        if [ -s "${trace}" ]; then
          redact < "${trace}"
        else
          echo "(curl wrote no trace - it failed before opening a connection)"
        fi
        echo "::endgroup::"

        if [ -s "${body}" ]; then
          echo "::group::response body for ${label} (redacted)"
          redact < "${body}"
          echo "::endgroup::"
        fi
      } >&2
      return 1
    fi

    # Backs off rather than hammering: these failures cluster, and four
    # immediate retries would land inside the same blip.
    delay=$((attempt * attempt * 2))
    if [ "${rc}" -ne 0 ]; then
      echo "  ${label}: connection failed (curl exit ${rc}), retrying in ${delay}s (attempt ${attempt}/${MAX_ATTEMPTS})" >&2
    else
      echo "  ${label}: HTTP ${http_code}, retrying in ${delay}s (attempt ${attempt}/${MAX_ATTEMPTS})" >&2
    fi
    sleep "${delay}"
    attempt=$((attempt + 1))
  done
}

issuer="${USER_LOOKUP_ISSUER_URI%/}"
realm="${issuer##*/realms/}"
base="${issuer%/realms/*}"
token_url="${issuer}/protocol/openid-connect/token"
clients_url="${base}/admin/realms/${realm}/clients"
scopes_url="${base}/admin/realms/${realm}/client-scopes"

echo "Keycloak realm: ${realm}"
echo "Ensuring service-account client: ${FAM_CLIENT_ID}"

# --- obtain an admin token via client_credentials ---------------------------
token="$(kc_curl admin-token -X POST "${token_url}" \
  -d grant_type=client_credentials \
  -d client_id="${KC_SA_CLIENT_ID}" \
  --data-urlencode "client_secret=${KC_SA_CLIENT_SECRET}" \
  | jq -r '.access_token // empty')" || exit 1

if [ -z "${token}" ]; then
  # Reached only when the call itself succeeded, so this really is the
  # credentials. A network failure has already reported itself as one, with a
  # trace.
  #
  # No status quoted here on purpose: the call ran inside $( ), so kc_curl set
  # $http_code in a subshell and it is empty by the time we read it. Printing
  # "HTTP  with no access_token" would be worse than not printing one.
  echo "::error::Keycloak returned no access_token. Check the admin service-account client id/secret and that it holds the realm-management 'manage-clients' role."
  exit 1
fi

# Minted at runtime, so GitHub does not know to mask it. Every trace below
# carries it in a header, and redact() strips it - this is the second layer.
echo "::add-mask::${token}"

auth=(-H "Authorization: Bearer ${token}")

# --- ensure the client exists ----------------------------------------------
uuid="$(kc_curl find-client "${auth[@]}" "${clients_url}?clientId=${FAM_CLIENT_ID}" \
  | jq -r '.[0].id // empty')" || exit 1

if [ -n "${uuid}" ]; then
  echo "✓ client exists: ${FAM_CLIENT_ID} (${uuid})"
else
  echo "+ creating client: ${FAM_CLIENT_ID}"
  body="$(jq -n --arg id "${FAM_CLIENT_ID}" '{
    clientId: $id,
    name: "NR FAM backend (user-lookup callouts)",
    description: "Service account for the FAM backend → nr-user-lookup-api. Managed by nr-fam CI.",
    protocol: "openid-connect",
    publicClient: false,
    serviceAccountsEnabled: true,
    standardFlowEnabled: false,
    directAccessGrantsEnabled: false,
    implicitFlowEnabled: false,
    authorizationServicesEnabled: false,
    frontchannelLogout: false
  }')"

  kc_curl create-client -X POST "${clients_url}" \
    "${auth[@]}" -H 'Content-Type: application/json' -d "${body}" >/dev/null || exit 1
  if [ "${http_code}" != "201" ]; then
    echo "::error::Failed to create client '${FAM_CLIENT_ID}' (HTTP ${http_code})."
    exit 1
  fi

  uuid="$(kc_curl find-created-client "${auth[@]}" \
    "${clients_url}?clientId=${FAM_CLIENT_ID}" \
    | jq -r '.[0].id // empty')" || exit 1
  if [ -z "${uuid}" ]; then
    echo "::error::Created client '${FAM_CLIENT_ID}' but could not resolve its id."
    exit 1
  fi
  echo "  created (${uuid})"
fi

# --- assign the required scopes as DEFAULT client scopes --------------------
# Default rather than optional, so the client_credentials token always carries
# them: the service account never sends an explicit `scope` request.
all_scopes="$(kc_curl list-scopes "${auth[@]}" "${scopes_url}")" || exit 1

for scope in "${SCOPES[@]}"; do
  scope_id="$(jq -r --arg n "${scope}" '.[] | select(.name == $n) | .id' <<< "${all_scopes}")"
  if [ -z "${scope_id}" ]; then
    echo "::error::Client scope '${scope}' does not exist in realm '${realm}'. It must be created first (nr-user-lookup-api owns scope creation)."
    exit 1
  fi

  kc_curl "assign-scope-${scope//:/-}" -X PUT \
    "${clients_url}/${uuid}/default-client-scopes/${scope_id}" "${auth[@]}" >/dev/null || exit 1
  if [ "${http_code}" != "204" ]; then
    echo "::error::Failed to assign scope '${scope}' to '${FAM_CLIENT_ID}' (HTTP ${http_code})."
    exit 1
  fi
  echo "✓ scope assigned: ${scope}"
done

# --- read the client secret and emit masked outputs ------------------------
secret="$(kc_curl read-client-secret "${auth[@]}" \
  "${clients_url}/${uuid}/client-secret" \
  | jq -r '.value // empty')" || exit 1
if [ -z "${secret}" ]; then
  echo "::error::Could not read the client secret for '${FAM_CLIENT_ID}'."
  exit 1
fi

# Mask so it cannot leak into logs even if a later step echoes an output.
echo "::add-mask::${secret}"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  {
    echo "client_id=${FAM_CLIENT_ID}"
    echo "client_secret=${secret}"
  } >> "${GITHUB_OUTPUT}"
fi

echo "Done. '${FAM_CLIENT_ID}' ready with ${#SCOPES[@]} scope(s); client_id/client_secret exposed as step outputs."
