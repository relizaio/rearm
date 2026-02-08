import { FaDiscord, FaLinkedinIn, FaGithub } from 'react-icons/fa';
import { FaBluesky } from 'react-icons/fa6';
import { FaXTwitter } from 'react-icons/fa6';

export default function Footer() {
  return (
    <footer className="footer">
      <div className="footer_container">
        <div className="footer_top">
          <div className="footer_logo">ReARM</div>
          <ul className="footer_nav">
            <li><a className="footer_links" href="/">Home</a></li>
            <li><a className="footer_links" href="/blog">Blog</a></li>
            <li><a className="footer_links" href="/#homePagePricing">Pricing</a></li>
          </ul>
          <div className="footer_social">
            <a className="footer_social_link" href="https://discord.gg/UTxjBf9juQ" target="_blank" rel="noopener noreferrer" title="Discord">
              <FaDiscord />
            </a>
            <a className="footer_social_link" href="https://twitter.com/relizaio" target="_blank" title="Twitter">
              <FaXTwitter />
            </a>
            <a className="footer_social_link" href="https://bsky.app/profile/reliza.bsky.social" target="_blank" rel="noopener noreferrer" title="BlueSky">
              <FaBluesky />
            </a>
            <a className="footer_social_link" href="https://www.linkedin.com/company/relizaio" target="_blank" rel="noopener noreferrer" title="LinkedIn">
              <FaLinkedinIn />
            </a>
            <a className="footer_social_link" href="https://www.github.com/relizaio/rearm" target="_blank" title="GitHub">
              <FaGithub />
            </a>
          </div>
        </div>
        <div className="footer_bottom">
          <span className="footer_rights">© 2019-2026 Reliza Incorporated. All rights reserved.</span>
          <a className="footer_rights" target="_blank" href="/privacy.html" rel="noreferrer">Privacy Policy</a>
          <a className="footer_rights" target="_blank" href="/tos.html" rel="noreferrer">Terms of Service</a>
        </div>
      </div>
    </footer>
  );
}
