# JWT Validation and CORS Fix

## Problem
When the React UI called the Resource Server API with a JWT token, it received a 401 error with the following error in the auth-server logs:

```
Caused by: java.lang.IllegalArgumentException: Status code '-1' should be a three-digit positive integer
	at org.springframework.util.Assert.isTrue(Assert.java:136)
	at org.springframework.http.HttpStatusCode.valueOf(HttpStatusCode.java:99)
	...
	at org.springframework.security.oauth2.jwt.JwtDecoderProviderConfigurationUtils.getConfiguration(JwtDecoderProviderConfigurationUtils.java:165)
```

## Root Cause
The Resource Server was trying to fetch the JWT configuration from the Authorization Server at `http://localhost:9000/.well-known/openid-configuration`, but the Authorization Server was:
1. Returning a **302 redirect to `/login`** instead of the OAuth2 configuration
2. This caused the HTTP connection to fail with status code `-1` (network-level failure)
3. The Resource Server couldn't initialize its JWT decoder and couldn't validate any JWT tokens

The issue was that the `AuthorizationServerConfig.java` was requiring authentication for **all** requests to the `/oauth2/**` paths, including:
- `/.well-known/openid-configuration` (OIDC discovery endpoint)
- `/oauth2/token` (token exchange endpoint)
- `/oauth2/jwks` (JWT signing keys endpoint)

## Solution
Updated the `AuthorizationServerConfig.java` to allow **public access** to these critical OAuth2 endpoints:

```java
.authorizeHttpRequests(authorize -> authorize
    .requestMatchers("/.well-known/**").permitAll()
    .requestMatchers("/oauth2/token", "/oauth2/jwks", "/oauth2/revoke", "/oauth2/introspect").permitAll()
    .anyRequest().authenticated())
```

### Changes Made

#### 1. Auth-Server (`auth-server/src/main/java/org/example/authserver/config/AuthorizationServerConfig.java`)
- Added `.permitAll()` for `/.well-known/**` paths to allow public access to OIDC discovery
- Added `.permitAll()` for `/oauth2/token`, `/oauth2/jwks`, `/oauth2/revoke`, and `/oauth2/introspect` endpoints

#### 2. React UI Error Handling
- Improved error messages in `ui-react/src/App.jsx` to show detailed HTTP status codes and error bodies
- Added CORS error detection and reporting
- Improved error messages in `ui-react/src/auth.js` for token exchange failures

## Verification

### Test 1: OIDC Discovery Endpoint
```bash
curl http://localhost:9000/.well-known/openid-configuration
```
Expected: Returns JSON with OAuth2 configuration including `jwks_uri`

### Test 2: JWKS Endpoint
```bash
curl http://localhost:9000/oauth2/jwks
```
Expected: Returns JSON with RSA public keys

### Test 3: Token Exchange (public client)
```bash
curl -X POST http://localhost:9000/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=authorization_code&client_id=react-client&code=INVALID&redirect_uri=http://localhost:3000/callback&code_verifier=invalid"
```
Expected: Returns OAuth2 error (not 302 redirect to login)

### Test 4: Resource Server JWT Validation
```bash
# First, manually create a token or get one from the auth server
# Then test the resource server
curl -H "Authorization: Bearer <token>" http://localhost:8081/api/messages
```
Expected: Either returns the protected resource (if token is valid) or 401 Unauthorized (if token is invalid/missing scope)

## Why These Endpoints Must Be Public

1. **OIDC Discovery (`/.well-known/openid-configuration`)**: 
   - Used by Resource Servers and clients to discover the Authorization Server's endpoints
   - Must be publicly accessible for service-to-service communication

2. **JWKS (`/oauth2/jwks`)**:
   - Contains the public keys used to validate JWT signatures
   - Referenced by Resource Servers to validate JWTs issued by the Authorization Server
   - Must be publicly accessible for service-to-service communication

3. **Token Exchange (`/oauth2/token`)**:
   - Used by public clients (like browser-based JavaScript apps) to exchange authorization codes for tokens
   - Public clients can't authenticate, so this endpoint must accept unauthenticated requests

4. **Revocation and Introspection**:
   - Revocation: Used by clients to revoke tokens
   - Introspection: Used by Resource Servers to check token validity
   - Both should be accessible by authorized parties

## Authorization Flow
- **Before Fix**: Browser → Auth Server returns 302 → Browser redirects to login → Can't get token
- **After Fix**: Browser → Auth Server returns token → Browser sends token to Resource Server → Resource Server validates token using public JWKS

## See Also
- [OIDC Discovery Specification](https://openid.net/specs/openid-connect-discovery-1_0.html)
- [OAuth2 RFC 7231 - HTTP Semantics and Content](https://tools.ietf.org/html/rfc7231)
- [Spring Authorization Server Documentation](https://spring.io/projects/spring-authorization-server)

