import { useEffect, useMemo, useState } from "react";
import {
  clearTokens,
  completeAuthCallback,
  getAccessToken,
  redirectToLogin,
} from "./auth";

const API_URL = import.meta.env.VITE_RESOURCE_API_URL || "http://localhost:8081/api/messages";

export default function App() {
  const [token, setToken] = useState(getAccessToken());
  const [apiResponse, setApiResponse] = useState(null);
  const [status, setStatus] = useState("Ready");

  const inCallback = useMemo(() => window.location.pathname === "/callback", []);

  useEffect(() => {
    if (!inCallback) {
      return;
    }

    completeAuthCallback(window.location.search)
      .then((result) => {
        setToken(result.access_token);
        setStatus("Authentication successful.");
        window.history.replaceState({}, "", "/");
      })
      .catch((err) => {
        setStatus(err.message);
        window.history.replaceState({}, "", "/");
      });
  }, [inCallback]);

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
         try {
           const errorBody = await response.json();
           if (errorBody.error) {
             errorDetails += `: ${errorBody.error}`;
           }
           if (errorBody.error_description) {
             errorDetails += ` - ${errorBody.error_description}`;
           }
         } catch {
           const errorText = await response.text();
           if (errorText) {
             errorDetails += `: ${errorText}`;
           }
         }
         setStatus(`API call failed - ${errorDetails}`);
         return;
       }

       const payload = await response.json();
       setApiResponse(payload);
       setStatus("API call succeeded.");
     } catch (error) {
       if (error instanceof TypeError && error.message.includes("Failed to fetch")) {
         setStatus(`CORS or network error: ${error.message}. Check browser console for details.`);
       } else {
         setStatus(`Error: ${error.message}`);
       }
     }
   }

  function logout() {
    clearTokens();
    setToken(null);
    setApiResponse(null);
    setStatus("Logged out locally.");
  }

  return (
    <main className="app-shell">
      <h1>Spring OAuth2 Demo</h1>
      <p>This React app uses Authorization Code + PKCE to get a JWT and call a protected Spring API.</p>

      <div className="button-row">
        <button onClick={redirectToLogin}>Login with LDAP via Auth Server</button>
        <button onClick={callProtectedApi} disabled={!token}>
          Call Protected API
        </button>
        <button onClick={logout} disabled={!token}>
          Logout
        </button>
      </div>

      <section className="card">
        <h2>Status</h2>
        <p>{status}</p>
      </section>

      <section className="card">
        <h2>Access Token</h2>
        <pre>{token ? `${token.slice(0, 80)}...` : "No token yet"}</pre>
      </section>

      <section className="card">
        <h2>API Response</h2>
        <pre>{apiResponse ? JSON.stringify(apiResponse, null, 2) : "No API call made"}</pre>
      </section>
    </main>
  );
}

