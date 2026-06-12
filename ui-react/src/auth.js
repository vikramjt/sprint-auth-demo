const AUTH_SERVER_URL = import.meta.env.VITE_AUTH_SERVER_URL || "http://localhost:9000";
const CLIENT_ID = import.meta.env.VITE_CLIENT_ID || "react-client";
const REDIRECT_URI = import.meta.env.VITE_REDIRECT_URI || "http://localhost:3000/callback";

function randomString(length = 64) {
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function sha256(value) {
  const encoder = new TextEncoder();
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(value));
  return new Uint8Array(digest);
}

function base64UrlEncode(bytes) {
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

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

export async function completeAuthCallback(search) {
  const params = new URLSearchParams(search);
  const oauthError = params.get("error");
  const oauthErrorDescription = params.get("error_description");

  if (oauthError) {
    throw new Error(
      oauthErrorDescription
        ? `Authorization failed: ${oauthErrorDescription}`
        : `Authorization failed: ${oauthError}`
    );
  }

  const code = params.get("code");
  const returnedState = params.get("state");
  const expectedState = sessionStorage.getItem("oauth_state");
  const codeVerifier = sessionStorage.getItem("pkce_verifier");

  if (!code || !returnedState || !expectedState || returnedState !== expectedState || !codeVerifier) {
    throw new Error("Invalid OAuth callback state.");
  }

  const body = new URLSearchParams({
    grant_type: "authorization_code",
    client_id: CLIENT_ID,
    code,
    redirect_uri: REDIRECT_URI,
    code_verifier: codeVerifier,
  });

   const response = await fetch(`${AUTH_SERVER_URL}/oauth2/token`, {
     method: "POST",
     headers: {
       "Content-Type": "application/x-www-form-urlencoded",
     },
     body,
   });

   if (!response.ok) {
     let errorDetails = `${response.status}`;
     try {
       const errorBody = await response.json();
       if (errorBody.error_description) {
         errorDetails = `${response.status}: ${errorBody.error_description}`;
       }
     } catch {
       const errorText = await response.text();
       if (errorText) {
         errorDetails = `${response.status}: ${errorText}`;
       }
     }
     throw new Error(`Token exchange failed: ${errorDetails}`);
   }

  const tokenResult = await response.json();
  sessionStorage.removeItem("pkce_verifier");
  sessionStorage.removeItem("oauth_state");
  sessionStorage.setItem("access_token", tokenResult.access_token);
  sessionStorage.setItem("id_token", tokenResult.id_token || "");

  return tokenResult;
}

export function getAccessToken() {
  return sessionStorage.getItem("access_token");
}

export function clearTokens() {
  sessionStorage.removeItem("access_token");
  sessionStorage.removeItem("id_token");
}

export function getConfig() {
  return {
    authServerUrl: AUTH_SERVER_URL,
  };
}

