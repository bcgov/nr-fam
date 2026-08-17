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

issuer="${USER_LOOKUP_ISSUER_URI%/}"
realm="${issuer##*/realms/}"
base="${issuer%/realms/*}"
token_url="${issuer}/protocol/openid-connect/token"
clients_url="${base}/admin/realms/${realm}/clients"
scopes_url="${base}/admin/realms/${realm}/client-scopes"

echo "Keycloak realm: ${realm}"
echo "Ensuring service-account client: ${FAM_CLIENT_ID}"

# --- obtain an admin token via client_credentials ---------------------------
token="$(curl -sS -X POST "${token_url}" \
  -d grant_type=client_credentials \
  -d client_id="${KC_SA_CLIENT_ID}" \
  --data-urlencode "client_secret=${KC_SA_CLIENT_SECRET}" \
  | jq -r '.access_token // empty')"

if [ -z "${token}" ]; then
  echo "::error::Could not obtain a Keycloak admin token. Check the admin service-account client id/secret and that it holds the realm-management 'manage-clients' role."
  exit 1
fi

auth=(-H "Authorization: Bearer ${token}")

# --- ensure the client exists ----------------------------------------------
uuid="$(curl -sS "${auth[@]}" "${clients_url}?clientId=${FAM_CLIENT_ID}" \
  | jq -r '.[0].id // empty')"

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

  code="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "${clients_url}" \
    "${auth[@]}" -H 'Content-Type: application/json' -d "${body}")"
  if [ "${code}" != "201" ]; then
    echo "::error::Failed to create client '${FAM_CLIENT_ID}' (HTTP ${code})."
    exit 1
  fi

  uuid="$(curl -sS "${auth[@]}" "${clients_url}?clientId=${FAM_CLIENT_ID}" \
    | jq -r '.[0].id // empty')"
  if [ -z "${uuid}" ]; then
    echo "::error::Created client '${FAM_CLIENT_ID}' but could not resolve its id."
    exit 1
  fi
  echo "  created (${uuid})"
fi

# --- assign the required scopes as DEFAULT client scopes --------------------
# Default rather than optional, so the client_credentials token always carries
# them: the service account never sends an explicit `scope` request.
all_scopes="$(curl -sS "${auth[@]}" "${scopes_url}")"

for scope in "${SCOPES[@]}"; do
  scope_id="$(jq -r --arg n "${scope}" '.[] | select(.name == $n) | .id' <<< "${all_scopes}")"
  if [ -z "${scope_id}" ]; then
    echo "::error::Client scope '${scope}' does not exist in realm '${realm}'. It must be created first (nr-user-lookup-api owns scope creation)."
    exit 1
  fi

  code="$(curl -sS -o /dev/null -w '%{http_code}' -X PUT \
    "${clients_url}/${uuid}/default-client-scopes/${scope_id}" "${auth[@]}")"
  if [ "${code}" != "204" ]; then
    echo "::error::Failed to assign scope '${scope}' to '${FAM_CLIENT_ID}' (HTTP ${code})."
    exit 1
  fi
  echo "✓ scope assigned: ${scope}"
done

# --- read the client secret and emit masked outputs ------------------------
secret="$(curl -sS "${auth[@]}" "${clients_url}/${uuid}/client-secret" \
  | jq -r '.value // empty')"
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
