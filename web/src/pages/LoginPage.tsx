import { useState, type FormEvent, type ReactNode } from "react";
import { authErrorMessage, useAuth } from "../auth/AuthProvider";
import { SignalRings, Wordmark } from "../components/Logo";
import { SiteFooter } from "../components/SiteFooter";
import { Button } from "../components/ui";

function AuthFrame({ children }: { children: ReactNode }) {
  return (
    <div className="auth">
      <div className="auth__grid">
        <section className="auth__brand">
          <SignalRings className="auth__rings" />
          <Wordmark size={46} onDark />
          <h1 className="auth__headline">Every tagged item, counted and accounted for.</h1>
          <p className="auth__lede">
            Live stock, movements and alerts from your TagScout readers, on any browser.
          </p>
          <ul className="auth__points">
            <li>Stock by product and by location</li>
            <li>Every inward, outward and status change</li>
            <li>Cycle count results and door alerts</li>
          </ul>
        </section>
        <section className="auth__panel">{children}</section>
      </div>
      <SiteFooter />
    </div>
  );
}

export function LoginPage() {
  const { signIn, resetPassword } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await signIn(email, password);
    } catch (err) {
      setError(authErrorMessage(err));
      setBusy(false);
    }
  };

  const forgot = async () => {
    setError("");
    setNotice("");
    if (!email.trim()) {
      setError("Enter your email address first, then choose Forgot password.");
      return;
    }
    try {
      await resetPassword(email);
      setNotice(`Password reset link sent to ${email.trim()}.`);
    } catch (err) {
      setError(authErrorMessage(err));
    }
  };

  return (
    <AuthFrame>
      <form className="auth__form" onSubmit={submit} noValidate>
        <h2>Sign in</h2>
        <p className="auth__hint">Use the same login as the TagScout app.</p>

        <label className="field">
          <span>Email</span>
          <input
            type="email"
            autoComplete="username"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
        </label>

        <label className="field">
          <span>Password</span>
          <input
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </label>

        {error ? (
          <p className="form-message form-message--error" role="alert">
            {error}
          </p>
        ) : null}
        {notice ? (
          <p className="form-message form-message--ok" role="status">
            {notice}
          </p>
        ) : null}

        <Button type="submit" variant="primary" busy={busy} disabled={!email || !password}>
          {busy ? "Signing in" : "Sign in"}
        </Button>
        <button type="button" className="link-button" onClick={() => void forgot()}>
          Forgot password?
        </button>
      </form>
    </AuthFrame>
  );
}

export function VerifyEmailPage() {
  const { user, resendVerification, refreshUser, signOut } = useAuth();
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  const resend = async () => {
    setError("");
    try {
      await resendVerification();
      setMessage(`Verification email sent to ${user?.email}.`);
    } catch (err) {
      setError(authErrorMessage(err));
    }
  };

  const check = async () => {
    setError("");
    await refreshUser();
    setMessage("Not verified yet. Open the link in the email, then check again.");
  };

  return (
    <AuthFrame>
      <div className="auth__form">
        <h2>Verify your email</h2>
        <p className="auth__hint">
          {user?.email} isn't verified yet. Open the link we emailed you, then check again.
        </p>
        {error ? <p className="form-message form-message--error">{error}</p> : null}
        {message ? <p className="form-message form-message--ok">{message}</p> : null}
        <Button variant="primary" onClick={() => void check()}>
          I've verified, check again
        </Button>
        <Button onClick={() => void resend()}>Resend verification email</Button>
        <button type="button" className="link-button" onClick={() => void signOut()}>
          Sign in with a different login
        </button>
      </div>
    </AuthFrame>
  );
}
