"use client";
import Link from "next/link";
import Image from "next/image";
import { FaGithub } from 'react-icons/fa';
import { useState, useEffect, useRef } from 'react';

export default function Header() {
  const [menuOpen, setMenuOpen] = useState(false);
  const navRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (navRef.current && !navRef.current.contains(event.target as Node)) {
        setMenuOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  useEffect(() => {
    function handleScroll() {
      setMenuOpen(false);
    }

    if (!menuOpen) {
      return;
    }

    window.addEventListener('scroll', handleScroll, { passive: true });

    return () => window.removeEventListener('scroll', handleScroll);
  }, [menuOpen]);

  const closeMenu = () => setMenuOpen(false);

  return (
    <div style={{ marginBottom: "106px" }}>
      <nav ref={navRef} className="navbar navbar-expand-lg navbar-light fixed-top" style={{ background: "white", minHeight: "100px", boxShadow: "rgba(0, 0, 0, 0.1) 0px 20px 25px -5px, rgba(0, 0, 0, 0.04) 0px 10px 10px -5px" }}>
        <div className="container-fluid">
          <Link className="navbar-brand d-flex align-items-center" href="/" style={{ marginLeft: "20px", marginRight: "-20px", gap: "10px" }}>
            <Image src="/home/logo_reliza_birds.png" alt="ReARM" width={50} height={50} />
            <span style={{ fontWeight: 700, fontSize: "1.4rem", color: "rgb(24,33,77)", letterSpacing: "0.02em" }}>ReARM</span>
          </Link>
          <button
            className={`navbar-toggler${menuOpen ? '' : ' collapsed'}`}
            type="button"
            aria-controls="navbarSupportedContent"
            aria-expanded={menuOpen}
            aria-label="Toggle navigation"
            onClick={() => setMenuOpen(!menuOpen)}
          >
            <span className="navbar-toggler-icon"></span>
          </button>
          <div className={`collapse navbar-collapse${menuOpen ? ' show' : ''}`} id="navbarSupportedContent">
            <ul className="navbar-nav mx-auto mb-2 mb-lg-0">
              <li className="nav-item d-flex align-items-center p-3">
                <Link className="nav-link fs-6 link-inactive" href="/blog" onClick={closeMenu}>Blog</Link>
              </li>
              <li className="nav-item d-flex align-items-center p-3">
                <Link className="nav-link fs-6 link-inactive" href="/news" onClick={closeMenu}>News</Link>
              </li>
              <li className="nav-item d-flex align-items-center p-3">
                <a className="nav-link fs-6" href="https://docs.rearmhq.com" target="_blank" style={{ textDecoration: "none" }} onClick={closeMenu}>Documentation</a>
              </li>
              <li className="nav-item d-flex align-items-center p-3">
                <Link className="nav-link fs-6 link-inactive" href="/comparisons" onClick={closeMenu}>Comparisons</Link>
              </li>
              <li className="nav-item d-flex align-items-center p-3">
                <a className="nav-link fs-6" href="https://d7ge14utcyki8.cloudfront.net/ReARM_Product_Info_Datasheet_v5_2026-03-13.pdf" target="_blank" rel="noopener noreferrer" style={{ textDecoration: "none" }} onClick={closeMenu}>Datasheet</a>
              </li>
              <li className="nav-item d-flex align-items-center p-3">
                <a className="nav-link fs-6" href="/#homePagePricing" style={{ textDecoration: "none" }}>Pricing</a>
              </li>
              <li className="nav-item d-flex align-items-center p-3">
                <a className="nav-link fs-6" href="https://discord.gg/UTxjBf9juQ" target="_blank" rel="noopener noreferrer" style={{ textDecoration: "none" }} onClick={closeMenu}>Discord</a>
              </li>
              <li className="nav-item p-3">
                <div className="d-flex">
                  <a href="https://calendly.com/pavel-reliza/30min" target="_blank" rel="noopener noreferrer" style={{ textDecoration: "none" }} onClick={closeMenu}>
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
