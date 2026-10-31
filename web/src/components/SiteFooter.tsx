import { Icon } from "./Icon";
import { LogoMark } from "./Logo";

export function SiteFooter() {
  return (
    <footer className="site-footer">
      <div className="site-footer__brand">
        <LogoMark size={30} />
        <div>
          <p className="site-footer__name">SNA Infotech Pvt. Ltd.</p>
          <p className="site-footer__tag">TagScout software, RFID tags and readers from one Mumbai team.</p>
        </div>
      </div>
      <ul className="site-footer__contact">
        <li>
          <Icon name="pin" size={16} />
          Andheri and Vasai, Mumbai
        </li>
        <li>
          <Icon name="phone" size={16} />
          <a href="tel:+912242182620">+91-22-42182620</a>
        </li>
        <li>
          <Icon name="mail" size={16} />
          <a href="mailto:contactus@sna-infotech.co.in">contactus@sna-infotech.co.in</a>
        </li>
      </ul>
      <p className="site-footer__legal">© {new Date().getFullYear()} SNA Infotech Pvt. Ltd. All rights reserved.</p>
    </footer>
  );
}
