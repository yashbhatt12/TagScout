import { lazy, Suspense } from "react";
import { Navigate, Route, Routes } from "react-router-dom";
import { useAuth } from "./auth/AuthProvider";
import { Layout } from "./components/Layout";
import { Wordmark } from "./components/Logo";
import { Button, ErrorNote, Loading } from "./components/ui";
import { CompanyProvider, useCompany } from "./lib/CompanyProvider";
import { LoginPage, VerifyEmailPage } from "./pages/LoginPage";

// Pages load on demand so the sign-in screen stays light.
const OverviewPage = lazy(() => import("./pages/OverviewPage").then((m) => ({ default: m.OverviewPage })));
const StockPage = lazy(() => import("./pages/StockPage").then((m) => ({ default: m.StockPage })));
const MovementsPage = lazy(() => import("./pages/MovementsPage").then((m) => ({ default: m.MovementsPage })));
const CycleCountsPage = lazy(() => import("./pages/CycleCountsPage").then((m) => ({ default: m.CycleCountsPage })));
const AlertsPage = lazy(() => import("./pages/AlertsPage").then((m) => ({ default: m.AlertsPage })));

function Splash({ text }: { text: string }) {
  return (
    <div className="splash">
      <Wordmark size={48} />
      <Loading text={text} />
    </div>
  );
}

function CompanyRoutes() {
  const { feature } = useCompany();
  return (
    <Layout>
      <Suspense fallback={<Loading />}>
        <Routes>
          <Route path="/" element={<OverviewPage />} />
          <Route path="/stock" element={<StockPage />} />
          <Route path="/movements" element={<MovementsPage />} />
          {feature("wms") ? <Route path="/cycle-counts" element={<CycleCountsPage />} /> : null}
          {feature("managerAlerts") ? <Route path="/alerts" element={<AlertsPage />} /> : null}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
    </Layout>
  );
}

export default function App() {
  const { user, loading, signOut } = useAuth();

  if (loading) return <Splash text="Starting TagScout" />;
  if (!user) return <LoginPage />;
  if (!user.emailVerified) return <VerifyEmailPage />;

  return (
    <CompanyProvider
      // companyId == uid until multi-user lands
      companyId={user.uid}
      loadingView={<Splash text="Loading your company" />}
      errorView={(error, retry) => (
        <div className="splash">
          <Wordmark size={48} />
          <ErrorNote error={error} onRetry={retry} />
          <Button icon="logout" onClick={() => void signOut()}>
            Sign out
          </Button>
        </div>
      )}
    >
      <CompanyRoutes />
    </CompanyProvider>
  );
}
