import type { ReactNode } from "react";
import { NavLink } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useCompany } from "../lib/CompanyProvider";
import { Icon, type IconName } from "./Icon";
import { Wordmark } from "./Logo";
import { SiteFooter } from "./SiteFooter";

type NavItem = { to: string; label: string; icon: IconName; badge?: number };

export function Layout({ children }: { children: ReactNode }) {
  const { signOut, user } = useAuth();
  const { profile, config, feature, label, alerts } = useCompany();

  const items: NavItem[] = [
    { to: "/", label: "Overview", icon: "overview" },
    { to: "/stock", label: "Stock", icon: "stock" },
    { to: "/movements", label: "Movements", icon: "movements" },
  ];
  if (feature("wms")) items.push({ to: "/cycle-counts", label: label("cycleCount", true), icon: "cycle" });
  if (feature("managerAlerts")) {
    items.push({ to: "/alerts", label: label("managerAlerts", true), icon: "alerts", badge: alerts.unread });
  }

  const company = profile.companyName || profile.name || "Your company";

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="sidebar__brand">
          <Wordmark size={34} onDark />
        </div>

        <div className="sidebar__company">
          <p className="sidebar__company-name">{company}</p>
          {config.templateLabel ? <p className="sidebar__company-type">{config.templateLabel}</p> : null}
        </div>

        <nav className="sidebar__nav" aria-label="Main">
          {items.map((item) => (
            <NavLink key={item.to} to={item.to} end={item.to === "/"} className="nav-link">
              <Icon name={item.icon} />
              <span>{item.label}</span>
              {item.badge ? (
                <span className="nav-link__badge" aria-label={`${item.badge} unread`}>
                  {item.badge > 99 ? "99+" : item.badge}
                </span>
              ) : null}
            </NavLink>
          ))}
        </nav>

        <div className="sidebar__foot">
          <p className="sidebar__user" title={user?.email ?? ""}>
            {user?.email}
          </p>
          <button type="button" className="sidebar__signout" onClick={() => void signOut()}>
            <Icon name="logout" size={18} />
            Sign out
          </button>
        </div>
      </aside>

      <div className="main">
        <main className="content">{children}</main>
        <SiteFooter />
      </div>
    </div>
  );
}
