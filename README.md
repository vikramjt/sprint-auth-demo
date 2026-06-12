# Auth Demo (LDAP + OAuth2 + React)

This workspace now contains three apps:

- `auth-server`: Spring Authorization Server using LDAP authentication.
- `resource-server`: Spring Boot REST API protected by JWT scope (`api.read`).
- `ui-react`: Basic React UI using OAuth2 Authorization Code + PKCE.

## Architecture

1. User opens React app (`http://localhost:3000`).
2. React redirects to `auth-server` (`http://localhost:9000/oauth2/authorize`).
3. Auth server validates credentials against OpenLDAP.
4. React exchanges authorization code for JWT access token.
5. React calls resource API (`http://localhost:8081/api/messages`) with Bearer token.
6. Resource server validates JWT using issuer metadata from auth server.

## Configure OpenLDAP

Update `auth-server/src/main/resources/application.yml` with your LDAP values:

- `app.ldap.url`
- `app.ldap.base-dn`
- `app.ldap.user-dn-pattern`
- `app.ldap.manager-dn` (optional)
- `app.ldap.manager-password` (optional)

Example:

```yaml
app:
  ldap:
    url: ldap://your-ldap-host:389
    base-dn: dc=example,dc=org
    user-dn-pattern: uid={0},ou=people
    manager-dn: cn=admin,dc=example,dc=org
    manager-password: admin
```

## Run backend services

```bash
cd /Users/A108706402/workspaces/authdemo
./mvnw -pl auth-server spring-boot:run
./mvnw -pl resource-server spring-boot:run
```

## Run React UI

```bash
cd /Users/A108706402/workspaces/authdemo/ui-react
cp .env.example .env
npm install
npm run dev
```

## Try the flow

1. Open `http://localhost:3000`
2. Click **Login with LDAP via Auth Server**
3. Authenticate with LDAP user
4. Click **Call Protected API**

## OpenAPI / Swagger

- Auth server Swagger UI: `http://localhost:9000/swagger-ui.html`
- Auth server OpenAPI JSON: `http://localhost:9000/v3/api-docs`
- Resource server Swagger UI: `http://localhost:8081/swagger-ui.html`
- Resource server OpenAPI JSON: `http://localhost:8081/v3/api-docs`

## Test backend modules

```bash
cd /Users/A108706402/workspaces/authdemo
./mvnw test
```

## LDAP Connectivity Testing

See `LDAP_TESTING.md` for complete guide on verifying LDAP connectivity with:
- Built-in Spring Boot test endpoints (`/ldap/test`, `/ldap/test-auth`, `/ldap/search`)
- Docker-based OpenLDAP setup
- Command-line `ldapsearch` tools
- Troubleshooting guide

