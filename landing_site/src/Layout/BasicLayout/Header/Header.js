import React from 'react'
import { Link } from 'react-router-dom'
import {navLinks} from "../../Constants/NavLinks.js"
import Logo from "../../../Assets/Logo.svg"
import { FaDiscord, FaLinkedinIn, FaGithub } from 'react-icons/fa'

const Header = () => {
  
  return (
    <div style={{marginBottom:"106px"}}>
      <nav className="navbar navbar-expand-lg navbar-light fixed-top" style={{ background: "white", minHeight: "100px", boxShadow: "rgba(0, 0, 0, 0.1) 0px 20px 25px -5px, rgba(0, 0, 0, 0.04) 0px 10px 10px -5px" }}>
        <div className="container-fluid" >
          {/* <div className='d-flex justify-content-between w-100 align-items-center' style={{ height: "80px" }}> */}
          <Link className="navbar-brand" to="/"><img src={Logo} alt="logo" style={{ padding: "15px", height:"80px" }}/></Link>
          <button className="navbar-toggler" type="button" data-bs-toggle="collapse" data-bs-target="#navbarSupportedContent" aria-controls="navbarSupportedContent" aria-expanded="false" aria-label="Toggle navigation">
            <span className="navbar-toggler-icon"></span>
          </button>
          {/* </div> */}
          <div className="collapse navbar-collapse" id="navbarSupportedContent">
            <ul className="navbar-nav mx-auto mb-2 mb-lg-0">
              {navLinks?.map((item) => {
                return ( 
                  <li className="nav-item d-flex align-items-center p-3">
                    <Link className={`nav-link fs-6 ${window.location.pathname === item?.path ? "link-active" : "link-inactive"}`} aria-current="page" to={item?.path}>{item?.title}</Link>
                  </li>
                )
              })}
              <li className="nav-item d-flex align-items-center p-3">
                <a class='nav-link fs-6' href='https://docs.rearmhq.com' target="_blank" style={{ textDecoration: "none" }}>Documentation</a>
              </li>
              <li className="nav-item d-flex align-items-center p-3">
                <a class='nav-link fs-6' href='https://d7ge14utcyki8.cloudfront.net/ReARM_Product_Info_Datasheet_v2_2025-07-10.pdf' rel="noreferrer" target="_blank" style={{ textDecoration: "none" }}>Datasheet</a>
              </li>
              <li className="nav-item d-flex align-items-center p-3">
                <a class='nav-link fs-6' href='/#homePagePricing' style={{ textDecoration: "none" }}>Pricing</a>
              </li>
              <li className="nav-item d-flex align-items-center p-3">
                <a class='nav-link fs-6' href='https://discord.gg/UTxjBf9juQ' target="_blank" rel="noreferrer" style={{ textDecoration: "none" }}>Discord</a>
              </li>
              <li className="nav-item p-3 ">
                <div className='d-flex'><a href='https://calendly.com/pavel_reliza/demo' target="_blank" rel="noreferrer" style={{ textDecoration: "none" }}><button className='nav-item btn_getStarted btn btn-outline-primary'>Get Private Demo</button></a></div>
              </li>
            </ul>
          </div>
          <div>
            <a target="_blank" rel="noreferrer" href="https://github.com/relizaio/rearm" style={{ padding: "10px", margin: "10px" }}>
              <FaGithub style={{ color: "rgb(24,33,77)", fontSize: "24px" }} />
            </a>
          </div>
        </div>
      </nav >
    </div >
  )
}

export default Header