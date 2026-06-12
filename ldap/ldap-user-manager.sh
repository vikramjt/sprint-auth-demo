#!/usr/bin/env bash
# ==============================================================
# ldap-user-manager.sh
# Shell script to manage OpenLDAP users for the authdemo project.
# ==============================================================

set -euo pipefail

# ── Configuration ──────────────────────────────────────────────
LDAP_HOST="${LDAP_HOST:-localhost}"
LDAP_PORT="${LDAP_PORT:-389}"
LDAP_URL="ldap://${LDAP_HOST}:${LDAP_PORT}"
LDAP_ADMIN_DN="${LDAP_ADMIN_DN:-cn=admin,dc=example,dc=org}"
LDAP_ADMIN_PASSWORD="${LDAP_ADMIN_PASSWORD:-adminpassword}"
BASE_DN="${BASE_DN:-dc=example,dc=org}"
PEOPLE_DN="ou=people,${BASE_DN}"

# ── Colours ────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

info()    { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ── Helper: check ldap tools are installed ─────────────────────
check_deps() {
  if ! command -v ldapadd &>/dev/null; then
    warn "ldap-utils not found. Installing..."
    if [[ "$OSTYPE" == "darwin"* ]]; then
      brew install openldap
    else
      sudo apt-get install -y ldap-utils 2>/dev/null || sudo yum install -y openldap-clients 2>/dev/null
    fi
  fi
}

# ── Helper: run ldapadd via Docker (no local tools needed) ─────
ldap_exec() {
  local cmd="$1"
  if command -v ldapadd &>/dev/null; then
    eval "$cmd"
  else
    # Run inside the docker container
    docker exec -i openldap-test bash -c "$cmd"
  fi
}

# ── Command: setup ─────────────────────────────────────────────
cmd_setup() {
  info "Creating base OU structure (ou=people, ou=groups)..."
  ldapadd -H "$LDAP_URL" \
    -D "$LDAP_ADMIN_DN" \
    -w "$LDAP_ADMIN_PASSWORD" \
    -f "$(dirname "$0")/base-structure.ldif" 2>&1 | grep -v "Already exists" || true
  info "Base structure ready."
}

# ── Command: add-user ──────────────────────────────────────────
cmd_add_user() {
  local uid="${1:?Usage: $0 add-user <uid> <cn> <sn> <email> <password>}"
  local cn="${2:?Missing cn}"
  local sn="${3:?Missing sn}"
  local email="${4:?Missing email}"
  local password="${5:?Missing password}"

  info "Adding user: uid=$uid, cn=$cn, email=$email ..."

  ldapadd -H "$LDAP_URL" \
    -D "$LDAP_ADMIN_DN" \
    -w "$LDAP_ADMIN_PASSWORD" <<EOF
dn: uid=${uid},${PEOPLE_DN}
objectClass: inetOrgPerson
objectClass: organizationalPerson
objectClass: person
objectClass: top
uid: ${uid}
cn: ${cn}
sn: ${sn}
mail: ${email}
userPassword: ${password}
EOF

  info "User '${uid}' added successfully."
}

# ── Command: change-password ───────────────────────────────────
cmd_change_password() {
  local uid="${1:?Usage: $0 change-password <uid> <new_password>}"
  local new_password="${2:?Missing new_password}"

  info "Changing password for user: uid=$uid ..."

  ldapmodify -H "$LDAP_URL" \
    -D "$LDAP_ADMIN_DN" \
    -w "$LDAP_ADMIN_PASSWORD" <<EOF
dn: uid=${uid},${PEOPLE_DN}
changetype: modify
replace: userPassword
userPassword: ${new_password}
EOF

  info "Password for '${uid}' updated successfully."
}

# ── Command: delete-user ───────────────────────────────────────
cmd_delete_user() {
  local uid="${1:?Usage: $0 delete-user <uid>}"

  warn "Deleting user: uid=$uid ..."
  ldapdelete -H "$LDAP_URL" \
    -D "$LDAP_ADMIN_DN" \
    -w "$LDAP_ADMIN_PASSWORD" \
    "uid=${uid},${PEOPLE_DN}"

  info "User '${uid}' deleted."
}

# ── Command: list-users ────────────────────────────────────────
cmd_list_users() {
  info "Listing all users in ${PEOPLE_DN}:"
  ldapsearch -H "$LDAP_URL" \
    -D "$LDAP_ADMIN_DN" \
    -w "$LDAP_ADMIN_PASSWORD" \
    -b "${PEOPLE_DN}" \
    "(objectClass=inetOrgPerson)" \
    uid cn mail 2>/dev/null | grep -E "^(uid|cn|mail|dn):" || true
}

# ── Command: search-user ───────────────────────────────────────
cmd_search_user() {
  local uid="${1:?Usage: $0 search-user <uid>}"

  info "Searching for user: uid=$uid ..."
  ldapsearch -H "$LDAP_URL" \
    -D "$LDAP_ADMIN_DN" \
    -w "$LDAP_ADMIN_PASSWORD" \
    -b "${PEOPLE_DN}" \
    "(uid=${uid})" 2>/dev/null || error "User not found: ${uid}"
}

# ── Command: test-auth ─────────────────────────────────────────
cmd_test_auth() {
  local uid="${1:?Usage: $0 test-auth <uid> <password>}"
  local password="${2:?Missing password}"
  local user_dn="uid=${uid},${PEOPLE_DN}"

  info "Testing authentication for: uid=$uid ..."
  if ldapwhoami -H "$LDAP_URL" -D "${user_dn}" -w "${password}" &>/dev/null; then
    info "✅ Authentication SUCCESSFUL for '${uid}'"
  else
    error "❌ Authentication FAILED for '${uid}'"
  fi
}

# ── Command: load-samples ──────────────────────────────────────
cmd_load_samples() {
  info "Loading sample users (alice, bob, charlie)..."
  ldapadd -H "$LDAP_URL" \
    -D "$LDAP_ADMIN_DN" \
    -w "$LDAP_ADMIN_PASSWORD" \
    -f "$(dirname "$0")/sample-users.ldif" 2>&1 | grep -v "Already exists" || true

  info "Loading sample groups (developers, admins, api-users)..."
  ldapadd -H "$LDAP_URL" \
    -D "$LDAP_ADMIN_DN" \
    -w "$LDAP_ADMIN_PASSWORD" \
    -f "$(dirname "$0")/sample-groups.ldif" 2>&1 | grep -v "Already exists" || true

  info "Sample data loaded. Test with:"
  info "  $0 test-auth alice alice123"
  info "  $0 test-auth bob   bob123"
}

# ── Command: add-to-group ──────────────────────────────────────
cmd_add_to_group() {
  local uid="${1:?Usage: $0 add-to-group <uid> <groupcn>}"
  local group_cn="${2:?Missing group cn}"

  info "Adding uid=$uid to group cn=$group_cn ..."
  ldapmodify -H "$LDAP_URL" \
    -D "$LDAP_ADMIN_DN" \
    -w "$LDAP_ADMIN_PASSWORD" <<EOF
dn: cn=${group_cn},ou=groups,${BASE_DN}
changetype: modify
add: member
member: uid=${uid},${PEOPLE_DN}
EOF
  info "User '${uid}' added to group '${group_cn}'."
}

# ── Docker shortcut: run inside container (no ldap tools needed) ──
cmd_docker_add() {
  local uid="${1:?Usage: $0 docker-add <uid> <cn> <sn> <email> <password>}"
  local cn="${2:?Missing cn}"
  local sn="${3:?Missing sn}"
  local email="${4:?Missing email}"
  local password="${5:?Missing password}"

  info "Adding user via Docker (no ldap-utils needed)..."
  docker exec openldap-test ldapadd \
    -D "cn=admin,dc=example,dc=org" \
    -w admin \
    -H "ldap://localhost:1389" <<EOF
dn: uid=${uid},ou=people,dc=example,dc=org
objectClass: inetOrgPerson
objectClass: organizationalPerson
objectClass: person
objectClass: top
uid: ${uid}
cn: ${cn}
sn: ${sn}
mail: ${email}
userPassword: ${password}
EOF
  info "User '${uid}' added via Docker."
}

# ── Usage ──────────────────────────────────────────────────────
usage() {
  cat <<EOF

Usage: $0 <command> [args]

Commands:
  setup                              Create ou=people and ou=groups
  add-user    <uid> <cn> <sn> <email> <password>  Add a user
  delete-user <uid>                  Remove a user
  change-password <uid> <password>   Update a user's password
  list-users                         List all users
  search-user <uid>                  Show a user's full LDAP entry
  test-auth   <uid> <password>       Test authentication
  add-to-group <uid> <groupcn>       Add user to a group
  load-samples                       Load alice, bob, charlie + groups
  docker-add  <uid> <cn> <sn> <email> <password>  Add via Docker (no ldap-utils)

Environment:
  LDAP_HOST          (default: localhost)
  LDAP_PORT          (default: 389)
  LDAP_ADMIN_DN      (default: cn=admin,dc=example,dc=org)
  LDAP_ADMIN_PASSWORD (default: admin)
  BASE_DN            (default: dc=example,dc=org)

Examples:
  $0 setup
  $0 add-user john "John Doe" Doe john@example.org secret123
  $0 test-auth john secret123
  $0 list-users
  $0 add-to-group john developers
  $0 change-password john newsecret
  $0 delete-user john
  $0 load-samples                    # quick demo data
  $0 docker-add dave "Dave Lee" Lee dave@example.org dave123

EOF
  exit 1
}

# ── Entry point ────────────────────────────────────────────────
COMMAND="${1:-help}"
shift || true

case "$COMMAND" in
  setup)           cmd_setup ;;
  add-user)        cmd_add_user "$@" ;;
  delete-user)     cmd_delete_user "$@" ;;
  change-password) cmd_change_password "$@" ;;
  list-users)      cmd_list_users ;;
  search-user)     cmd_search_user "$@" ;;
  test-auth)       cmd_test_auth "$@" ;;
  add-to-group)    cmd_add_to_group "$@" ;;
  load-samples)    cmd_load_samples ;;
  docker-add)      cmd_docker_add "$@" ;;
  *)               usage ;;
esac

