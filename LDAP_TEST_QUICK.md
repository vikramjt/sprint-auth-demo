# LDAP Quick Test Script

Copy-paste these commands to quickly test LDAP connectivity.

## Prerequisites
```bash
# Auth server should be running
./mvnw -pl auth-server spring-boot:run
```

## Quick Tests

### 1. Test LDAP Connection
```bash
curl http://localhost:9000/ldap/test | jq .
```

### 2. Test User Authentication (user1/password1)
```bash
curl -X POST "http://localhost:9000/ldap/test-auth?username=user1&password=password1" | jq .
```

### 3. Search for User
```bash
curl "http://localhost:9000/ldap/search?uid=user1" | jq .
```

### 4. View LDAP Config
```bash
curl http://localhost:9000/ldap/config | jq .
```

### 5. Test Failed Auth (wrong password)
```bash
curl -X POST "http://localhost:9000/ldap/test-auth?username=user1&password=wrongpassword" | jq .
```

---

## One-Liner Setup + Test

```bash
# Start OpenLDAP + auth-server + test all
docker-compose up -d && \
sleep 5 && \
./mvnw -pl auth-server spring-boot:run &
sleep 10 && \
curl http://localhost:9000/ldap/test && \
curl -X POST "http://localhost:9000/ldap/test-auth?username=user1&password=password1" && \
curl "http://localhost:9000/ldap/search?uid=user1"
```

---

## Docker LDAP Default Credentials

- **LDAP URL**: `ldap://localhost:389`
- **Admin DN**: `cn=admin,dc=example,dc=org`
- **Admin Password**: `admin`
- **User 1**: `user1` / `password1`
- **User 2**: `user2` / `password2`
- **Base DN**: `dc=example,dc=org`
- **User OU**: `ou=people`

---

## Endpoint Reference

| Method | Endpoint | Description | Example |
|--------|----------|-------------|---------|
| GET | `/ldap/test` | Test basic LDAP connection | `curl http://localhost:9000/ldap/test` |
| POST | `/ldap/test-auth` | Authenticate a user | `curl -X POST "http://localhost:9000/ldap/test-auth?username=user1&password=password1"` |
| GET | `/ldap/search` | Search for users | `curl "http://localhost:9000/ldap/search?uid=user1"` |
| GET | `/ldap/config` | Show LDAP config | `curl http://localhost:9000/ldap/config` |

---

## Troubleshooting Quick Fixes

```bash
# Test if LDAP port is open
telnet localhost 389

# Check if auth-server is responding
curl http://localhost:9000/actuator/health

# View recent auth-server logs
./mvnw -pl auth-server spring-boot:run 2>&1 | tail -f

# Check docker LDAP logs
docker-compose logs openldap

# Restart LDAP
docker-compose restart openldap

# Full reset (delete all data)
docker-compose down -v && docker-compose up -d
```

