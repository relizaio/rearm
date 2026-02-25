import Link from "next/link";
import Image from "next/image";
import { FaGithub } from 'react-icons/fa';

export default function Header() {
  return (
    <div style={{ marginBottom: "106px" }}>
      <nav className="navbar navbar-expand-lg navbar-light fixed-top" style={{ background: "white", minHeight: "100px", boxShadow: "rgba(0, 0, 0, 0.1) 0px 20px 25px -5px, rgba(0, 0, 0, 0.04) 0px 10px 10px -5px" }}>
        <div className="container-fluid">
          <Link className="navbar-brand" href="/" style={{ marginLeft: "20px", marginRight: "-20px" }}>
            <Image src="/home/rearm.png" alt="ReARM" width={75} height={74} />
          </Link>
          <button className="navbar-toggler" type="button" data-bs-toggle="collapse" data-bs-target="#navbarSupportedContent" aria-controls="navbarSupportedContent" aria-expanded="false" aria-label="Toggle navigation">
            <span className="navbar-toggler-icon"></span>
          </button>
          <div className="collapse navbar-collapse" id="navbarSupportedContent">
            <ul className="navbar-nav mx-auto mb-2 mb-lg-0">
              <li className="nav-item d-flex align-items-center p-3">
                <Link className="nav-link fs-6 link-inactive" href="/blog">Blog</Link>
              </li>
              <li className="nav-item d-flex align-items-center p-3">
                <Link className="nav-link fs-6 link-inactive" href="/news">News</Link>
              </li>
              <li className="nav-item d-flex align-items-center p-3">
                <a className="nav-link fs-6" href="https://docs.rearmhq.com" target="_blank" style={{ textDecoration: "none" }}>Documentation</a>
              </li>
              <li className="nav-item d-flex align-items-center p-3">
                <Link className="nav-link fs-6 link-inactive" href="/comparisons">Comparisons</Link>
              </li>
              <li className="nav-item d-flex align-items-center p-3">
                <a className="nav-link fs-6" href="https://d7ge14utcyki8.cloudfront.net/ReARM_Product_Info_Datasheet_v4_2025-11-24.pdf" target="_blank" rel="noopener noreferrer" style={{ textDecoration: "none" }}>Datasheet</a>
              </li>
              <li className="nav-item d-flex align-items-center p-3">
                <a className="nav-link fs-6" href="/#homePagePricing" style={{ textDecoration: "none" }}>Pricing</a>
              </li>
              <li className="nav-item d-flex align-items-center p-3">
                <a className="nav-link fs-6" href="https://discord.gg/UTxjBf9juQ" target="_blank" rel="noopener noreferrer" style={{ textDecoration: "none" }}>Discord</a>
              </li>
              <li className="nav-item p-3">
                <div className="d-flex">
                  <a href="https://calendly.com/pavel_reliza/demo" target="_blank" rel="noopener noreferrer" style={{ textDecoration: "none" }}>
                    <button className="btn_getStarted">Get Private Demo</button>
                  </a>
                </div>
              </li>
            </ul>
          </div>
          <div>
            <a target="_blank" href="https://github.com/relizaio/rearm" style={{ padding: "10px", margin: "10px" }}>
              <FaGithub style={{ color: "rgb(24,33,77)", fontSize: "24px" }} />
            </a>
          </div>
        </div>
      </nav>
    </div>
  );
}
