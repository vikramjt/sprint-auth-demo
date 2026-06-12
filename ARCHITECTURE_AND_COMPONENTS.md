# Architecture & Component Documentation

Complete technical documentation of all main classes and components used in the Auth Demo system, including their purpose, configuration, and design decisions.

## Table of Contents

1. [Overview](#overview)
2. [Auth Server](#auth-server)
3. [Resource Server](#resource-server)
4. [React UI](#react-ui)
5. [Configuration Files](#configuration-files)
6. [Integration Points](#integration-points)

---

## Overview

The system implements the **Spring Authorization Server + OAuth2 Resource Server + PKCE Flow** architecture:

```
┌──────────────────────┐
│    React UI          │
│   (Port 3000)        │
└──────────┬───────────┘
           │ 1. Login Request (PKCE)
           ▼
┌──────────────────────┐     Validates with
│  Auth Server         │────────────────────┐
│  (Port 9000)         │                    │
│  + OAuth2 Endpoints  │     LDAP User     │
│  + OIDC Discovery    │     Directory     │
└──────────┬───────────┘     (Port 389)    │
           │ 2. JWT Token                  │
           │                               │
           ▼                               │
┌──────────────────────┐                   │
│  Resource Server     │                   │
│  (Port 8081)         │                   │
│  + Protected APIs    │                   │
│  + JWT Validation    │                   │
└──────────────────────┘                   │
           ▲                               │
           └───────────────────────────────┘
           Fetches public JWKS keys
```

---

## Auth Server

The Auth Server handles authentication and OAuth2/OIDC token generation.

### 1. **AuthServerApplication.java**

**File**: `auth-server/src/main/java/org/example/authserver/AuthServerApplication.java`

**Purpose**: Entry point and bootstrap for the Spring Boot application.

**Key Configuration**:
```java
@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthServerApplication { ... }
```

**Why It's Configured This Way**:
- `@SpringBootApplication`: Enables auto-configuration and component scanning
- `@ConfigurationPropertiesScan`: Scans for `@ConfigurationProperties` beans (LdapAuthProperties)
- Main method uses `SpringApplication.run()` to bootstrap the entire Spring container

**What it does**:
- Initializes the Spring container
- Loads all @Configuration beans
- Sets up Tomcat servlet container on port 9000
- Enables dependency injection for all components

---

### 2. **AuthorizationServerConfig.java**

**File**: `auth-server/src/main/java/org/example/authserver/config/AuthorizationServerConfig.java`

**Purpose**: Configures Spring Authorization Server for OAuth2/OIDC functionality.

**Key Components**:

#### A. Authorization Server Security Filter Chain
```java
@Bean
@Order(Ordered.HIGHEST_PRECEDENCE)
SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http)
```

**Purpose**: 
- Secures OAuth2 endpoints (`/oauth2/**`, `/.well-known/**`)
- Enforces authentication where needed
- Configures CORS for cross-origin requests

**Configuration Choices**:
- **Order = HIGHEST_PRECEDENCE**: Ensures this filter chain is evaluated first
- **Security Matcher**: Applies only to OAuth2 paths, not other endpoints
- **Public Endpoints**: Made public for Resource Server to discover endpoints:
  ```java
  .requestMatchers("/.well-known/**").permitAll()
  .requestMatchers("/oauth2/token", "/oauth2/jwks", "/oauth2/revoke", "/oauth2/introspect").permitAll()
  ```
- **Why Public?**: Resource Servers need public access to JWKS for JWT validation; clients need public access to token endpoint

#### B. Default Security Filter Chain
```java
@Bean
@Order(2)
SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http)
```

**Purpose**: 
- Handles login/form-login flow
- Protects all other endpoints
- Allows public access to Swagger UI and LDAP test endpoints

**Configuration Details**:
```java
.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
.requestMatchers("/ldap/**").permitAll()  // For debugging
.anyRequest().authenticated()             // Everything else requires auth
```

#### C. Registered Client Configuration
```java
@Bean
RegisteredClientRepository registeredClientRepository()
```

**Purpose**: Registers OAuth2 clients that can request tokens.

**Client Configuration**:
```java
RegisteredClient reactClient = RegisteredClient.withId(UUID.randomUUID().toString())
    .clientId("react-client")
    .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
    .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
    .redirectUri("http://localhost:3000/callback")
    .postLogoutRedirectUri("http://localhost:3000")
    .scope(OidcScopes.OPENID)
    .scope(OidcScopes.PROFILE)
    .scope("api.read")  // Custom scope for Resource Server
```

**Configuration Rationale**:
- **ClientAuthenticationMethod.NONE**: Browser apps can't authenticate (no client secrets in JS)
- **AUTHORIZATION_CODE**: Standard OAuth2 flow for web apps
- **REFRESH_TOKEN**: Allows token refresh without re-login
- **Scopes**: 
  - `openid profile`: Standard OIDC scopes
  - `api.read`: Custom scope for accessing protected resource server API
- **Require Proof Key (PKCE)**: `requireProofKey=true` prevents authorization code interception
- **No Consent Required**: `requireAuthorizationConsent=false` for demo (production would require)
- **Token TTL**: 30 minutes for access tokens

#### D. Authorization Server Settings
```java
@Bean
AuthorizationServerSettings authorizationServerSettings()
```

**Configuration**:
```java
AuthorizationServerSettings.builder()
    .issuer("http://localhost:9000")
    .build()
```

**Why Important**:
- **Issuer**: Must match what Resource Server expects (`spring.security.oauth2.resourceserver.jwt.issuer-uri`)
- Resource Server uses this to validate JWT `iss` claim
- If mismatch, JWT validation fails with 401 errors

#### E. JWT Key Management
```java
@Bean
JWKSource<SecurityContext> jwkSource()
```

**Configuration**:
```java
return new ImmutableJWKSet<>(new JWKSet(KeyUtils.generateRsa()));
```

**Purpose**:
- Provides RSA key pair for signing JWTs
- Exposes public key via `/oauth2/jwks` endpoint
- Resource Server downloads this to validate token signatures

**Why RSA?**:
- **Asymmetric**: Resource Server only needs public key, can't forge tokens
- **Widely Supported**: Standard for OAuth2/OIDC
- **2048-bit**: Current industry standard for security

#### F. CORS Configuration
```java
@Bean
CorsConfigurationSource corsConfigurationSource()
```

**Configuration**:
```java
cfg.setAllowedOrigins(List.of("http://localhost:3000"));
cfg.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
cfg.setExposedHeaders(List.of("Authorization"));
cfg.setAllowCredentials(true);
```

**Why This Configuration**:
- **Allowed Origins**: Only React UI can make cross-origin requests
- **GET, POST, OPTIONS**: GET for discovery/JWKS, POST for token exchange, OPTIONS for preflight
- **Authorization Header**: React UI sends tokens in Authorization header
- **Exposed Headers**: React UI can read Authorization header from responses
- **Allow Credentials**: Required for session-based login form

#### G. LDAP Authentication
```java
@Bean
AuthenticationProvider ldapAuthenticationProvider(BaseLdapPathContextSource contextSource, LdapAuthProperties props)
```

**Purpose**: Authenticates users against LDAP directory.

**Configuration**:
```java
BindAuthenticator authenticator = new BindAuthenticator(contextSource);
authenticator.setUserDnPatterns(new String[]{"uid={0},ou=people"});

DefaultLdapAuthoritiesPopulator authoritiesPopulator = 
    new DefaultLdapAuthoritiesPopulator(contextSource, "ou=groups");
authoritiesPopulator.setGroupRoleAttribute("cn");
```

**How It Works**:
1. User enters uid + password on login form
2. BindAuthenticator tries to bind as `uid=username,ou=people,dc=example,dc=org`
3. If successful, loads group memberships from `ou=groups`
4. Authorities are extracted from group `cn` attributes

**Why This Configuration**:
- **Bind Authentication**: More secure than proxy bind for authentication
- **Group Population**: Maps LDAP groups to Spring Security authorities
- **ignorePartialResultException**: LDAP servers may return partial results; safe to ignore

---

### 3. **LdapAuthProperties.java**

**File**: `auth-server/src/main/java/org/example/authserver/config/LdapAuthProperties.java`

**Purpose**: Configuration properties holder for LDAP connection details.

**Properties**:
```java
@ConfigurationProperties(prefix = "app.ldap")
public class LdapAuthProperties {
    private String url;              // ldap://localhost:389
    private String baseDn;           // dc=example,dc=org
    private String userDnPattern;    // uid={0},ou=people
    private String managerDn;        // cn=admin,dc=example,dc=org
    private String managerPassword;  // admin
}
```

**Why This Pattern**:
- **@ConfigurationProperties**: Binds YAML properties to Java class
- **Type-safe**: IDE autocomplete and compile-time checking
- **Validation**: Spring can validate properties
- **Environment-specific**: Load from `application-prod.yml` in production

**Example Usage** (from application.yml):
```yaml
app:
  ldap:
    url: ldap://localhost:389
    base-dn: dc=example,dc=org
    user-dn-pattern: uid={0},ou=people
    manager-dn: cn=admin,dc=example,dc=org
    manager-password: admin
```

---

### 4. **KeyUtils.java**

**File**: `auth-server/src/main/java/org/example/authserver/config/KeyUtils.java`

**Purpose**: Generates RSA key pairs for JWT signing.

**Key Method**:
```java
static RSAKey generateRsa() {
    KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
    keyPairGenerator.initialize(2048);  // Key size
    KeyPair keyPair = keyPairGenerator.generateKeyPair();
    // ... wrap in NimbusDS RSAKey with UUID
}
```

**Configuration Decisions**:
- **2048-bit RSA**: Standard security level (won't be cracked for decades)
- **UUID keyID**: Unique identifier for key rotation
- **Static method**: Called once during boot, reused for all JWT signing

**Security Implications**:
- **In-Memory Keys**: Keys only exist in RAM, lost on restart (fine for demo)
- **No Persistence**: Production would store in HSM or vault
- **Public Key Published**: Via `/oauth2/jwks` endpoint - safe because only public key

---

### 5. **LdapTestController.java**

**File**: `auth-server/src/main/java/org/example/authserver/config/LdapTestController.java`

**Purpose**: Development/debugging endpoints for LDAP connectivity testing.

**⚠️ Security Note**: Should be disabled in production!

**Endpoints**:

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/ldap/test` | GET | Test basic LDAP connection |
| `/ldap/test-auth` | POST | Test user authentication |
| `/ldap/search` | GET | Search for users by UID |
| `/ldap/config` | GET | Show LDAP configuration |

**Example Usage**:
```bash
# Test connection
curl http://localhost:9000/ldap/test

# Test authentication
curl -X POST "http://localhost:9000/ldap/test-auth?username=user1&password=password1"

# Search users
curl "http://localhost:9000/ldap/search?uid=user1"

# Show config
curl http://localhost:9000/ldap/config
```

**Why Useful**:
- **Fast Troubleshooting**: No need to go through full OAuth2 flow
- **Connection Debugging**: Verify LDAP server is reachable
- **User Verification**: Check if user exists and password is correct
- **Configuration Validation**: Confirm DN patterns are correct

---

### 6. **LdapUserManagementController.java**

**File**: `auth-server/src/main/java/org/example/authserver/config/LdapUserManagementController.java`

**Purpose**: REST API for managing LDAP users during development.

**⚠️ Production Note**: Disable these endpoints in production!

**Endpoints**:

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/ldap/users` | GET | List all users |
| `/ldap/users` | POST | Create new user |
| `/ldap/users/{uid}` | GET | Get specific user |
| `/ldap/users/{uid}/password` | PUT | Change user password |
| `/ldap/users/{uid}` | DELETE | Delete user |

**Example Usage**:
```bash
# List users
curl http://localhost:9000/ldap/users

# Create user
curl -X POST http://localhost:9000/ldap/users \
  -H "Content-Type: application/json" \
  -d '{"uid":"john","cn":"John Doe","sn":"Doe","mail":"john@example.org","password":"secret123"}'

# Change password
curl -X PUT http://localhost:9000/ldap/users/john/password \
  -H "Content-Type: application/json" \
  -d '{"newPassword":"newsecret123"}'

# Delete user
curl -X DELETE http://localhost:9000/ldap/users/john
```

**Implementation Details**:
- **LDAP DN Building**: Uses `LdapNameBuilder` for proper DN construction
- **Object Classes**: Sets required LDAP object classes (top, person, organizationalPerson, inetOrgPerson)
- **ModificationItem**: Uses LDAP API directly for modifications

---

### 7. **OpenApiConfig.java** (Auth Server)

**File**: `auth-server/src/main/java/org/example/authserver/config/OpenApiConfig.java`

**Purpose**: Configures Swagger/OpenAPI documentation.

**Configuration**:
```java
@Bean
OpenAPI authServerOpenApi() {
    return new OpenAPI()
        .info(new Info()
            .title("Auth Server API")
            .description("Authorization Server endpoints for OAuth2/OIDC and login flow")
            .version("v1")
            .contact(new Contact().name("Auth Demo Team"))
            .license(new License().name("Apache 2.0")));
}
```

**Why This Configuration**:
- **Human-Readable Docs**: Swagger UI at `/swagger-ui.html`
- **API Discovery**: Developers can explore all endpoints
- **LDAP Test Endpoints**: Documented for debugging

**Accessible at**: `http://localhost:9000/swagger-ui.html`

---

## Resource Server

The Resource Server protects APIs with JWT token validation.

### 1. **ResourceServerApplication.java**

**File**: `resource-server/src/main/java/org/example/resourceserver/ResourceServerApplication.java`

**Purpose**: Entry point for the Resource Server.

**Configuration**:
```java
@SpringBootApplication
public class ResourceServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ResourceServerApplication.class, args);
    }
}
```

**What It Does**:
- Boots Spring container on port 8081
- Enables auto-configuration
- Initializes OAuth2 Resource Server bean from dependencies
- Loads `ResourceSecurityConfig` configuration

---

### 2. **ResourceSecurityConfig.java**

**File**: `resource-server/src/main/java/org/example/resourceserver/config/ResourceSecurityConfig.java`

**Purpose**: Configures JWT validation and protection of APIs.

**Key Configuration**:

#### A. Security Filter Chain
```java
@Bean
SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/health").permitAll()
            .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
            .requestMatchers("/api/messages").hasAuthority("SCOPE_api.read")
            .anyRequest().authenticated())
        .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
        .cors(Customizer.withDefaults())
        .build();
}
```

**Configuration Decisions**:

| Component | Configuration | Reason |
|-----------|---|---|
| `/actuator/health` | permitAll | Health checks don't need auth |
| Swagger UI | permitAll | Documentation should be public |
| `/api/messages` | SCOPE_api.read | Only allows tokens with `api.read` scope |
| JWT Validation | defaults | Uses issuer-uri from application.yml to validate |
| CORS | defaults | Allows React UI to call this API |

**Why SCOPE_api.read?**:
- OAuth2 token includes scopes (permissions)
- Resource Server checks: Does token have `api.read` scope?
- Prevents tokens from other systems from accessing this API
- Provides fine-grained authorization

#### B. CORS Configuration
```java
@Bean
CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration corsConfiguration = new CorsConfiguration();
    corsConfiguration.setAllowedOrigins(List.of("http://localhost:3000"));
    corsConfiguration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
    corsConfiguration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    // ...
}
```

**Why Needed**:
- React UI runs on `localhost:3000`
- Resource Server runs on `localhost:8081`
- Browser's Same-Origin Policy blocks cross-origin requests
- CORS headers tell browser to allow requests

---

### 3. **MessageController.java**

**File**: `resource-server/src/main/java/org/example/resourceserver/api/MessageController.java`

**Purpose**: Protected API endpoint serving authenticated user data.

**Endpoint**:
```java
@GetMapping
public Map<String, Object> getMessage(Authentication authentication) {
    return Map.of(
        "message", "JWT accepted. Protected resource returned successfully.",
        "subject", authentication.getName(),
        "authorities", authentication.getAuthorities(),
        "issuedAt", Instant.now().toString());
}
```

**Configuration Details**:
- **Path**: `/api/messages` (protected by SCOPE_api.read)
- **Authentication Parameter**: Spring automatically injects authenticated user
- **Response**: Returns user details from JWT claims

**How It Works**:
1. React UI sends request with `Authorization: Bearer <JWT_TOKEN>`
2. `oauth2ResourceServer` filter validates JWT signature using JWKS from Auth Server
3. JWT claims decoded into `Authentication` object
4. `getMessage()` receives pre-authenticated user
5. Returns user information (this proves token was valid)

**Security Flow**:
```
Browser Request
    ↓
JWT Validation Filter
    ↓
Fetch JWKS from http://localhost:9000/oauth2/jwks
    ↓
Verify JWT signature with public key
    ↓
Extract claims (subject, scopes, etc.)
    ↓
Create Authentication object
    ↓
Check SCOPE_api.read authority
    ↓
✓ Allowed → Call getMessage()
✗ Denied → Return 403 Forbidden
```

---

### 4. **OpenApiConfig.java** (Resource Server)

**File**: `resource-server/src/main/java/org/example/resourceserver/config/OpenApiConfig.java`

**Purpose**: Swagger documentation with JWT security scheme.

**Configuration**:
```java
@Bean
OpenAPI resourceServerOpenApi() {
    String bearerScheme = "bearerAuth";

    return new OpenAPI()
        .info(new Info()
            .title("Resource Server API")
            .description("JWT-protected service endpoints")
            .version("v1")
            // ...)
        .addSecurityItem(new SecurityRequirement().addList(bearerScheme))
        .schemaRequirement(bearerScheme, new SecurityScheme()
            .name("Authorization")
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT"));
}
```

**Why This Configuration**:
- **Security Requirement**: Tells Swagger that all endpoints need bearer token
- **Bearer Format**: Documents that tokens are JWT format
- **Swagger UI**: Provides "Authorize" button for testing with real tokens

**Result**: Swagger UI at `http://localhost:8081/swagger-ui.html` shows lock icons and allows token testing

---

## React UI

Frontend application for OAuth2 login and API interaction.

### 1. **package.json**

**File**: `ui-react/package.json`

**Purpose**: Defines dependencies and build scripts.

**Key Dependencies**:
```json
{
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1"
  },
  "devDependencies": {
    "@vitejs/plugin-react": "^4.3.1",
    "vite": "^5.4.0"
  }
}
```

**Configuration Decisions**:

| Package | Version | Reason |
|---------|---------|--------|
| React | 18.3.1 | Latest stable with hooks and functional components |
| React-DOM | 18.3.1 | Required for React to work in browser |
| Vite | 5.4.0 | Modern bundler, faster than Webpack, better DX |
| @vitejs/plugin-react | 4.3.1 | React support in Vite |

**Why Minimal Dependencies**:
- No Redux, no routing library
- Simple demo application
- No build complexities
- Educational clarity

**Scripts**:
```json
{
  "dev": "vite",              // Run dev server on port 3000
  "build": "vite build",      // Build for production
  "preview": "vite preview"   // Preview production build
}
```

---

### 2. **vite.config.js**

**File**: `ui-react/vite.config.js`

**Purpose**: Vite build configuration.

**Configuration**:
```javascript
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
  },
});
```

**Why This Configuration**:
- **React Plugin**: Enables JSX transformation and fast refresh
- **Port 3000**: Standard web app port, matches OAuth2 redirect URI
- **Dev Server**: Hot module reloading during development

---

### 3. **main.jsx**

**File**: `ui-react/src/main.jsx`

**Purpose**: Entry point that mounts React app.

**Code**:
```javascript
import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import "./styles.css";

ReactDOM.createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
```

**Why This Configuration**:
- **StrictMode**: Helps detect unsafe practices in development
- **createRoot API**: React 18 concurrent rendering API
- **Mounts on "root"**: HTML file has `<div id="root"></div>`

---

### 4. **auth.js**

**File**: `ui-react/src/auth.js`

**Purpose**: OAuth2/PKCE flow implementation and API utilities.

**Key Functions**:

#### A. Configuration
```javascript
const AUTH_SERVER_URL = "http://localhost:9000";
const CLIENT_ID = "react-client";
const REDIRECT_URI = "http://localhost:3000/callback";
```

**Configuration Rationale**:
- **AUTH_SERVER_URL**: Must match Auth Server issuer in JWT claims
- **CLIENT_ID**: Must match registered client in Auth Server
- **REDIRECT_URI**: Must match registered redirect URI exactly

**Why These Values**:
- PKCE: Binding authorization code to specific client
- Issuer validation: JWTs will have `iss: http://localhost:9000`
- Redirect binding: Prevents authorization code theft

#### B. PKCE Helper Functions
```javascript
function randomString(length = 64) { ... }
async function sha256(value) { ... }
function base64UrlEncode(bytes) { ... }
```

**Purpose**: Implement PKCE (Proof Key for Public Clients)

**Why PKCE**:
- **Without PKCE**: Authorization code can be stolen from URL bar
- **With PKCE**: Code is bound to code_verifier, code alone is useless
- **State Parameter**: Prevents CSRF (cross-site request forgery)

**PKCE Flow**:
```
1. Generate random code_verifier (64 chars)
2. Hash it: code_challenge = SHA256(code_verifier)
3. Redirect to /oauth2/authorize with code_challenge
4. Auth Server returns authorization code (bound to challenge)
5. Exchange code + code_verifier for token
6. If verifier doesn't match challenge hash, authorization code is rejected
```

#### C. Login Flow
```javascript
export async function redirectToLogin() {
  const codeVerifier = randomString(64);
  const codeChallenge = base64UrlEncode(await sha256(codeVerifier));
  const state = randomString(16);

  sessionStorage.setItem("pkce_verifier", codeVerifier);
  sessionStorage.setItem("oauth_state", state);

  const params = new URLSearchParams({
    response_type: "code",
    client_id: CLIENT_ID,
    redirect_uri: REDIRECT_URI,
    scope: "openid profile api.read",
    code_challenge: codeChallenge,
    code_challenge_method: "S256",
    state,
  });

  window.location.assign(`${AUTH_SERVER_URL}/oauth2/authorize?${params.toString()}`);
}
```

**Scopes Requested**:

| Scope | Grants | Used For |
|-------|--------|----------|
| `openid` | ID Token | Identifies user |
| `profile` | User info (name, picture, etc.) | Display user profile |
| `api.read` | Access to `/api/messages` | Authorization at Resource Server |

**Why sessionStorage**:
- State maintained across redirect
- Cleared when browser closed (temporary)
- Can't be accessed by malicious scripts (only same origin)
- Not persisted to disk

#### D. Token Exchange
```javascript
export async function completeAuthCallback(search) {
  const params = new URLSearchParams(search);
  
  // Validate state parameter
  const returnedState = params.get("state");
  const expectedState = sessionStorage.getItem("oauth_state");
  // ... CSRF check
  
  const codeVerifier = sessionStorage.getItem("pkce_verifier");
  
  const response = await fetch(`${AUTH_SERVER_URL}/oauth2/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      client_id: CLIENT_ID,
      code: params.get("code"),
      redirect_uri: REDIRECT_URI,
      code_verifier: codeVerifier,  // PKCE verification
    }),
  });
  
  // Parse response and store tokens
  const tokenResult = await response.json();
  sessionStorage.setItem("access_token", tokenResult.access_token);
  sessionStorage.setItem("id_token", tokenResult.id_token || "");
  
  return tokenResult;
}
```

**Security Features**:
1. **PKCE Verification**: Server checks code_verifier matches original challenge
2. **State Validation**: Prevents CSRF by checking state parameter
3. **No Client Secret**: Public client, can't securely store secrets
4. **HTTPS Only**: Production must use HTTPS (cookie secure flag)

**Token Storage**:
```javascript
export function getAccessToken() {
  return sessionStorage.getItem("access_token");
}

export function getConfig() {
  return { authServerUrl: AUTH_SERVER_URL };
}
```

---

### 5. **App.jsx**

**File**: `ui-react/src/App.jsx`

**Purpose**: Main React component, orchestrates authentication and API calls.

**State Management**:
```javascript
const [token, setToken] = useState(getAccessToken());
const [apiResponse, setApiResponse] = useState(null);
const [status, setStatus] = useState("Ready");
```

**Key State Variables**:

| Variable | Purpose | Lifetime |
|----------|---------|----------|
| `token` | JWT token for API calls | Session duration |
| `apiResponse` | Response from `/api/messages` | Until next API call |
| `status` | User-facing status messages | Dynamic |

**Callback Detection**:
```javascript
const inCallback = useMemo(() => window.location.pathname === "/callback", []);

useEffect(() => {
  if (!inCallback) return;
  
  completeAuthCallback(window.location.search)
    .then((result) => {
      setToken(result.access_token);
      window.history.replaceState({}, "", "/");
    })
    .catch((err) => setStatus(err.message));
}, [inCallback]);
```

**Explanation**:
1. After login, Auth Server redirects to `http://localhost:3000/callback?code=...&state=...`
2. `inCallback` detects we're on callback page
3. `useEffect` runs `completeAuthCallback()` to exchange code for token
4. Token is stored in state and sessionStorage
5. History is replaced to remove code from URL (better for security)

**Protected API Call**:
```javascript
async function callProtectedApi() {
  if (!token) {
    setStatus("Login first to call the API.");
    return;
  }

  try {
    const response = await fetch(API_URL, {
      headers: {
        Authorization: `Bearer ${token}`,
      },
    });

    if (!response.ok) {
      let errorDetails = `Status ${response.status}`;
      // Parse and display error details
      setStatus(`API call failed - ${errorDetails}`);
      return;
    }

    const payload = await response.json();
    setApiResponse(payload);
    setStatus("API call succeeded.");
  } catch (error) {
    if (error instanceof TypeError && error.message.includes("Failed to fetch")) {
      setStatus(`CORS or network error: ${error.message}. Check browser console.`);
    } else {
      setStatus(`Error: ${error.message}`);
    }
  }
}
```

**Error Handling Strategy**:

| Error Type | Detection | User Message |
|-----------|-----------|--------------|
| Invalid token | 401 Unauthorized | "API call failed - Status 401" |
| Missing scope | 403 Forbidden | "API call failed - Status 403" |
| CORS error | TypeError "Failed to fetch" | "CORS or network error..." |
| Network error | TypeError | Shows specific error |

**Why Try-Catch**:
- Network errors don't return response objects
- CORS errors throw before reaching server
- Detailed error info helps debugging

**Logout**:
```javascript
function logout() {
  clearTokens();  // Clears sessionStorage
  setToken(null);
  setApiResponse(null);
  setStatus("Logged out locally.");
}
```

**Note**: Only clears local state. In production, would call Auth Server `/connect/logout` endpoint.

---

## Configuration Files

### 1. Auth Server - application.yml

**File**: `auth-server/src/main/resources/application.yml`

**Configuration**:
```yaml
server:
  port: 9000

spring:
  application:
    name: auth-server

app:
  ldap:
    url: ldap://localhost:389
    base-dn: dc=example,dc=org
    user-dn-pattern: uid={0},ou=people
    manager-dn: cn=admin,dc=example,dc=org
    manager-password: admin
```

**Parameter Explanations**:

| Parameter | Example | Explanation |
|-----------|---------|-------------|
| `server.port` | 9000 | OAuth2 endpoints accessible at port 9000 |
| `app.ldap.url` | ldap://localhost:389 | LDAP server address |
| `app.ldap.base-dn` | dc=example,dc=org | LDAP tree root for users |
| `app.ldap.user-dn-pattern` | uid={0},ou=people | DN constructed as uid=username,ou=people,dc=example,dc=org |
| `app.ldap.manager-dn` | cn=admin,dc=example,dc=org | Admin user for query operations |
| `app.ldap.manager-password` | admin | Admin password |

**How user-dn-pattern Works**:
```
Pattern: uid={0},ou=people,{1}...
User enters: john
Resolves to: uid=john,ou=people,dc=example,dc=org (base-dn appended)
```

---

### 2. Resource Server - application.yml

**File**: `resource-server/src/main/resources/application.yml`

**Configuration**:
```yaml
server:
  port: 8081

spring:
  application:
    name: resource-server
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:9000
```

**Critical Configuration**:

| Parameter | Value | Why Important |
|-----------|-------|--------------|
| `server.port` | 8081 | Distinct from Auth Server |
| `issuer-uri` | http://localhost:9000 | **Must match JWT `iss` claim** |

**issuer-uri Importance**:
1. On startup, Resource Server fetches: `http://localhost:9000/.well-known/openid-configuration`
2. Discovers: `http://localhost:9000/oauth2/jwks` (JWKS URI)
3. Downloads public keys for signing verification
4. When validating JWT:
   - Extracts `iss` claim from token
   - Must equal `http://localhost:9000`
   - If mismatch → **401 Unauthorized**

**What This Prevents**:
- Tokens from other Auth Servers
- Tokens signed with keys from other sources
- Impersonation attacks

---

## Integration Points

### OAuth2 Flow - End to End

```
1. User clicks "Login" in React UI (port 3000)
   ↓
2. redirectToLogin() generates PKCE challenge, stores verifier
   ↓
3. Browser redirected to http://localhost:9000/oauth2/authorize?...&code_challenge=...
   ↓
4. Auth Server displays login form (managed by Spring Security)
   ↓
5. User enters username + password
   ↓
6. LdapAuthenticationProvider validates against LDAP (port 389)
   ↓
7. If valid, Auth Server generates authorization code (bound to code_challenge)
   ↓
8. Redirects to http://localhost:3000/callback?code=...&state=...
   ↓
9. App.jsx detects callback, calls completeAuthCallback()
   ↓
10. completeAuthCallback() sends code + code_verifier to http://localhost:9000/oauth2/token
    ↓
11. Auth Server verifies:
    - code_challenge matches SHA256(code_verifier)
    - state parameter is valid
    - client_id and redirect_uri match
    ↓
12. If all checks pass, Auth Server signs JWT with private key and returns tokens
    ↓
13. React UI stores access_token in sessionStorage
    ↓
14. User clicks "Call Protected API"
    ↓
15. fetch() sends request to http://localhost:8081/api/messages with Authorization header
    ↓
16. Resource Server:
    - Receives request with Bearer token
    - Extracts JWT from Authorization header
    - Downloads JWKS from http://localhost:9000/oauth2/jwks (cached)
    - Verifies JWT signature with public key
    - Extracts claims (subject, scopes, etc.)
    - Checks SCOPE_api.read authority
    ↓
17. If valid:
    - MessageController.getMessage() executes
    - Returns user details
    ↓
18. If invalid:
    - Returns 401 Unauthorized
    - Browser shows error in status
```

### Port Usage

| Port | Service | Purpose |
|------|---------|---------|
| 389 | LDAP Server | User directory (OpenLDAP) |
| 3000 | React UI | Web application interface |
| 8081 | Resource Server | Protected APIs |
| 9000 | Auth Server | OAuth2 endpoints, OIDC discovery |

### Environment Variables

**React UI** (`ui-react/src/auth.js`):
```javascript
const AUTH_SERVER_URL = import.meta.env.VITE_AUTH_SERVER_URL || "http://localhost:9000";
const CLIENT_ID = import.meta.env.VITE_CLIENT_ID || "react-client";
const REDIRECT_URI = import.meta.env.VITE_REDIRECT_URI || "http://localhost:3000/callback";
```

**Usage in .env file**:
```env
VITE_AUTH_SERVER_URL=https://auth.example.com
VITE_CLIENT_ID=prod-react-client
VITE_REDIRECT_URI=https://app.example.com/callback
```

**Why This Pattern**:
- Same build works for dev, staging, and production
- No secrets in code
- Vite automatically picks up `VITE_*` variables

---

## Summary Table

### All Main Classes Overview

| Class | Module | Purpose | Configuration |
|-------|--------|---------|---|
| **AuthServerApplication** | Auth | Entry point | @SpringBootApplication, @ConfigurationPropertiesScan |
| **AuthorizationServerConfig** | Auth | OAuth2/OIDC setup, JWT signing | PKCE, LDAP auth, CORS |
| **LdapAuthProperties** | Auth | LDAP configuration | @ConfigurationProperties, app.ldap.* |
| **KeyUtils** | Auth | RSA key generation | 2048-bit, UUID keyID |
| **LdapTestController** | Auth | LDAP debugging endpoints | GET /ldap/test, /ldap/search, etc. |
| **LdapUserManagementController** | Auth | User CRUD via LDAP | POST/PUT/DELETE endpoints |
| **OpenApiConfig (Auth)** | Auth | Swagger documentation | SpringDoc configuration |
| **ResourceServerApplication** | Resource | Entry point | @SpringBootApplication |
| **ResourceSecurityConfig** | Resource | JWT validation, API protection | OAuth2 resource server, SCOPE_api.read |
| **MessageController** | Resource | Protected API endpoint | GET /api/messages, requires SCOPE_api.read |
| **OpenApiConfig (Resource)** | Resource | Swagger + JWT scheme | bearerAuth security requirement |
| **main.jsx** | React | React app mount | React.StrictMode, createRoot |
| **auth.js** | React | OAuth2/PKCE implementation | PKCE flow, state/verifier storage |
| **App.jsx** | React | Main UI component | Login, API calls, error handling |
| **package.json** | React | Dependencies | React 18, Vite 5 |
| **vite.config.js** | React | Build configuration | React plugin, port 3000 |

---

## Key Design Patterns

1. **PKCE (Proof Key for Public Clients)**: Secures browser-based apps
2. **OAuth2 Authorization Code Flow**: Standard web app authentication
3. **JWT Asymmetric Signing**: Resource Server validates without secret
4. **LDAP Integration**: Enterprise user directory
5. **CORS Configuration**: Controlled cross-origin access
6. **Scope-Based Authorization**: Fine-grained API access control
7. **Configuration Properties**: Environment-specific settings
8. **Error Handling in React**: User-friendly error messages

---

## Security Considerations

| Aspect | Implemented | Notes |
|--------|-------------|-------|
| **HTTPS** | ❌ Dev only | Production must use HTTPS |
| **PKCE** | ✅ | Protects against authorization code interception |
| **State Parameter** | ✅ | CSRF protection |
| **CORS** | ✅ | Whitelisted origins only |
| **JWT Validation** | ✅ | Signature verification with public key |
| **Scope Checking** | ✅ | API requires SCOPE_api.read |
| **LDAP Binding** | ✅ | Passwords validated against LDAP |
| **Client Authentication** | ❌ | Public client (no client secret) - acceptable for SPA |
| **Credential Storage** | ⚠️ | SessionStorage only (lost on browser close) |

---

For more detailed information, see:
- `JWT_VALIDATION_FIX.md` - JWT validation troubleshooting
- `SOLUTION_SUMMARY.md` - Complete system solution
- `QUICK_REFERENCE.md` - Quick debugging guide

