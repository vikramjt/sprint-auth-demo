# LDAP Connectivity Testing Guide

This guide explains how to verify LDAP connectivity for the Auth Server.

## Quick Verification

### 1. Using Built-in Spring Boot Endpoints

Once the auth-server is running, you can test LDAP connectivity via HTTP endpoints:

#### Test basic connection
```bash
curl http://localhost:9000/ldap/test
```

Expected response:
```json
{
  "status": "SUCCESS",
  "message": "LDAP connection successful",
  "ldapUrl": "ldap://localhost:389",
  "baseDn": "dc=example,dc=org"
}
```

#### Test user authentication
```bash
curl -X POST "http://localhost:9000/ldap/test-auth?username=user1&password=password1"
```

Expected response (on success):
```json
{
  "status": "SUCCESS",
  "message": "User authentication successful",
  "username": "user1",
  "userDn": "uid=user1,ou=people,dc=example,dc=org"
}
```

#### Search for a user
```bash
curl "http://localhost:9000/ldap/search?uid=user1"
```

Expected response:
```json
{
  "status": "SUCCESS",
  "searchedFor": "user1",
  "results": [
    {
      "uid": "user1",
      "cn": "User One",
      "mail": "user1@example.org"
    }
  ],
  "count": 1
}
```

#### View current LDAP configuration
```bash
curl http://localhost:9000/ldap/config
```

---

## Full Setup with Docker OpenLDAP

### Step 1: Start OpenLDAP and Admin UI

From the project root:

```bash
docker-compose up -d
```

This starts:
- **OpenLDAP**: `ldap://localhost:389`
- **phpLDAPadmin**: `https://localhost:6443` (admin UI)

### Step 2: Wait for LDAP to be ready

```bash
sleep 5  # Wait for LDAP to initialize
```

### Step 3: Verify with `ldapsearch` (optional)

If you have OpenLDAP client tools:

```bash
# Install (macOS)
brew install openldap

# Test basic connection
ldapsearch -H ldap://localhost:389 \
  -D "cn=admin,dc=example,dc=org" \
  -w admin \
  -b "dc=example,dc=org" \
  "uid=user1"
```

### Step 4: Configure auth-server

Update `auth-server/src/main/resources/application.yml`:

```yaml
app:
  ldap:
    url: ldap://localhost:389
    base-dn: dc=example,dc=org
    user-dn-pattern: uid={0},ou=people
    manager-dn: cn=admin,dc=example,dc=org
    manager-password: admin
```

### Step 5: Run auth-server

```bash
./mvnw -pl auth-server spring-boot:run
```

### Step 6: Test connectivity

```bash
# Connection test
curl http://localhost:9000/ldap/test

# Auth test (default users: user1, user2 with passwords: password1, password2)
curl -X POST "http://localhost:9000/ldap/test-auth?username=user1&password=password1"

# Search test
curl "http://localhost:9000/ldap/search?uid=user1"
```

---

## Using phpLDAPadmin UI

1. Navigate to `https://localhost:6443`
2. Accept the self-signed certificate warning
3. Login:
   - **Login DN**: `cn=admin,dc=example,dc=org`
   - **Password**: `admin`
4. Browse users under `dc=example,dc=org` → `ou=people`

---

## Custom LDAP Setup

If you have your own LDAP server:

1. Update `auth-server/src/main/resources/application.yml` with your LDAP details:
   ```yaml
   app:
     ldap:
       url: ldap://your-ldap-host:389
       base-dn: dc=yourorg,dc=com
       user-dn-pattern: uid={0},ou=people  # Adjust per your schema
       manager-dn: cn=admin,dc=yourorg,dc=com  # Optional
       manager-password: your-password  # Optional
   ```

2. Test connectivity:
   ```bash
   ./mvnw -pl auth-server spring-boot:run
   curl http://localhost:9000/ldap/test
   ```

---

## Troubleshooting

### Error: "LDAP connection timed out"
- Check LDAP server is running: `telnet localhost 389`
- Verify URL in `application.yml`
- Check firewall rules

### Error: "Invalid DN"
- Verify `user-dn-pattern` matches your LDAP schema
- Use phpLDAPadmin to browse users and see their actual DN format
- Common patterns:
  - `uid={0},ou=people,dc=example,dc=org`
  - `cn={0},ou=users,dc=example,dc=org`
  - `mail={0},o=company,c=us`

### Error: "Authentication failed"
- Ensure password is correct
- Verify user exists in LDAP: use `/ldap/search?uid=username`
- Check user has proper objectClass (should include `inetOrgPerson`)

### Error: "Base DN not found"
- Verify base DN exists in LDAP
- Use phpLDAPadmin to check the directory tree
- Common base DNs: `dc=example,dc=org`, `o=myorg`, `o=company,c=us`

---

## Integration Test with React UI

Once LDAP is confirmed working:

1. **Start all services**:
   ```bash
   ./mvnw -pl auth-server spring-boot:run        # Terminal 1
   ./mvnw -pl resource-server spring-boot:run    # Terminal 2
   cd ui-react && npm run dev                    # Terminal 3
   ```

2. **Open React UI**: `http://localhost:3000`

3. **Test login**:
   - Click "Login with LDAP via Auth Server"
   - Enter username: `user1`
   - Enter password: `password1` (or `password2` for user2)
   - You should be redirected back with an access token

4. **Test resource API**:
   - After successful login, click "Call Protected API"
   - Should return data from `http://localhost:8081/api/messages`

---

## Clean Up

```bash
# Stop Docker containers
docker-compose down

# Remove volumes (permanent deletion)
docker-compose down -v
```

