import React from 'react'
import { Link } from 'react-router-dom'
import { FaDiscord, FaLinkedinIn, FaGithub } from 'react-icons/fa'
import { FaBluesky } from 'react-icons/fa6'
import Logo from "../../../Assets/Logo.svg"
import { navLinks } from "../../Constants/NavLinks.js"


const Footer = () => {


  const socialLinks = [
    {
      link: FaDiscord,
      url: "https://discord.gg/UTxjBf9juQ",
    },
    {
      link: FaBluesky,
      url: "https://bsky.app/profile/reliza.bsky.social",
    },
    {
      link: FaLinkedinIn,
      url: "https://www.linkedin.com/company/relizaio",
    },
    {
      link: FaGithub,
      url: "https://www.github.com/relizaio/rearm",
    },
  ]
  return (
    <div style={{ borderTop: "1px solid rgba(0,0,0,0.15)", padding: "22px 0" }}>
      <div className='container d-none d-md-block'>
        <div className='row align-items-center'>
          <div className='col-2'>
            <img src={Logo} alt='Logo' />
          </div>
          <div className='col-8 d-flex justify-content-center flex-wrap'>
            {navLinks?.map((item, index) => {
              return (
                <li key={item?.path || index} className="d-flex align-items-center p-3">
                  <Link className={`fs-6 footer_links`} aria-current="page" to={item?.path}>{item?.title}</Link>
                </li>
              )
            })}
          </div>
          <div className='col-2 d-flex'>
            {socialLinks?.map((item, index) => {
              return (
                <a key={item?.url || index} target="_blank" rel="noreferrer" href={item?.url} style={{ padding: "10px", margin: "10px" }}>
                  <item.link style={{ color: "rgb(24,33,77)", fontSize: "24px" }} />
                </a>
              )
            })}
          </div>
        </div>
        <div className='justify-content-center d-flex mt-4'>
          <span className="footer_rights">© 2019-2025 All rights reserved</span>
          <a className="footer_rights" target="_blank" href="/privacy.html">Privacy Policy</a>
          <a className="footer_rights" target="_blank" href="/tos.html">Terms of Service</a>
        </div>
      </div>


      <div className='container d-block d-md-none'>
        <div className='row'>
          {navLinks?.map((item, index) => {
            return (
              <div key={item?.path || index} className='col-6'>
                <li className="d-flex align-items-center p-2">
                  <Link className={`fs-6 footer_links`} aria-current="page" to={item?.path}>{item?.title}</Link>
                </li>
              </div>
            )
          })}
        </div>
        <div className='row mt-3'>
          <div className='col d-flex'>
            {socialLinks?.map((item, index) => {
              return (
                <a key={item?.url || index} target="_blank" rel="noreferrer" href={item?.url} style={{ padding: "10px", margin: "0px" }}>
                  <item.link style={{ color: "rgb(24,33,77)", fontSize: "13px" }} />
                </a>
              )
            })}
          </div>
        </div>
        <div className="d-flex justify-content-between align-items-center mt-4">
          <img src={Logo} alt='Logo' style={{ width: "20%" }} />
          <span className="footer_rights">© 2019-2025 All rights reserved</span>
        </div>
      </div>
    </div>
  )
}

export default Footer