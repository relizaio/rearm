import React, { useRef } from 'react'
import BasicLayout from '../../Layout/BasicLayout/BasicLayout'
import styles from "./HomePage.module.css"
import slack from "../../Assets/Home/slack.png"
import github from "../../Assets/Home/github.png"
import gitlab from "../../Assets/Home/gitlab.png"
import jenkins from "../../Assets/Home/jenkins.png"
import ado from "../../Assets/Home/ado.png"
import dtrack from "../../Assets/Home/dtrack.png"
import cosign from "../../Assets/Home/sigstore_cosign-horizontal-color-logo.svg"
import tealogo from "../../Assets/Home/tealogo.png"
import analyse from "../../Assets/Home/analyse.svg"
import colab from "../../Assets/Home/colab.svg"
import analytics from "../../Assets/Home/analytics.svg"
import Component1 from './components/Component1/Component1'
import rearm_release from "../../Assets/Home/rearm_release.png"
import create_component from "../../Assets/Home/create_component.png"
import compliance_image from "../../Assets/Home/compliance.png"
import rearm_analytics from "../../Assets/Home/rearm_analytics.png"
import auto_integrate from "../../Assets/Home/auto_integrate.png"
import rearm_approvals from "../../Assets/Home/rearm_approvals.png"
import LastContainer1 from '../../Components/LastContainer/LastContainer1'
import PricingPlan from '../../Components/PricingPlan/PricingPlan'
import { useNavigate } from 'react-router-dom'

const HomePage = () => {
  const navigation = useNavigate()
  const sliderRef1 = useRef()
  const titleDetails = {
    title: "ReARM",
    titleClass: styles.C1_title,
    titleMaxWidth: "925px",
    text: [
      {
        text: "System to Manage Releases, SBOMs, xBOMs",
        maxWidth: "611px",
        textClass: `mx-auto ${styles.C1_text}`
      }
    ]
  }

  const favApps = [
    { icon: dtrack },
    { icon: ado },
    { icon: github },
    { icon: gitlab },
    { icon: jenkins },
    { icon: slack },
    { icon: cosign },
  ]
  const featuresArray = [
    {
      icon: analyse,
      title: "Analyze your data",
      text: "Create reports with an easy to use drag-and-drop designer.",
    },
    {
      icon: colab,
      title: "Collaborate securely",
      text: "Share/publish your reports with your colleagues.",
    },
    {
      icon: analytics,
      title: "Embedded analytics",
      text: "Get a powerful analytics tool in your own brand name.",
    },
  ]
  const array2 = [
    {
      image: rearm_release,
      title: "Storage for SBOMs / xBOMs, Release Metadata",
      texts: [
        { text: "ReARM provides storage for Artifacts and Metadata, including SBOMs / xBOMs and Attestations, per each Release." }
      ]
    },
    {
      image: compliance_image,
      title: "Regulatory Compliance",
      texts: [
        { text: "ReARM ensures supply chain security compliance with various regulations, including EU CRA, NIS2, DORA, US Executive Orders 14028, 14144, Section 524B of the FD&C Act." }
      ]
    },
    {
      image: rearm_analytics,
      title: "Track Vulnerabilities and Violations across your Supply Chain",
      texts: [
        { text: "ReARM integrates with OWASP Dependency-Track to present real-time view of the state of your supply chain." }
      ]
    },
    {
      image: create_component,
      title: "Automated Versioning and Change Logs for your Releases",
      texts: [
        { text: "Choose desired versioning schema, connect to your CI and let ReARM do the rest!" }
      ]
    },
    {
      image: auto_integrate,
      title: "Automated Bundling into Products",
      texts: [
        { text: "ReARM automatically bundles your Components into Products and supports multi-level nesting." }
      ]
    },
    {
      image: rearm_approvals,
      title: "Approval and Lifecycle Management",
      texts: [
        { text: "ReARM provides rich capabilities for managing approvals and lifecycles of your releases. Both manual and automated approvals are supported." }
      ]
    }
  ]
  const customerFeedbackArray = [
    {
      profilePic: "https://cdn.pixabay.com/photo/2016/08/08/09/17/avatar-1577909_960_720.png",
      name: "Mila McSabbu",
      jobProfile: "Freelance Designer",
      texts: [
        { text: "OMG! I cannot believe that I have got a brand new landing page after getting appmax. It was super easy to edit and publish." }
      ]
    },
    {
      profilePic: "https://cdn.iconscout.com/icon/free/png-256/avatar-370-456322.png",
      name: "Robert Fox",
      jobProfile: "UI/UX Designer",
      texts: [
        { text: "OMG! I cannot believe that I have got a brand new landing page after getting appmax. It was super easy to edit and publish." }
      ]
    },
    {
      profilePic: "https://cdn.pixabay.com/photo/2016/08/08/09/17/avatar-1577909_960_720.png",
      name: "Mila McSabbu",
      jobProfile: "Freelance Designer",
      texts: [
        { text: "OMG! I cannot believe that I have got a brand new landing page after getting appmax. It was super easy to edit and publish." }
      ]
    },
    {
      profilePic: "https://cdn.iconscout.com/icon/free/png-256/avatar-370-456322.png",
      name: "Robert Fox",
      jobProfile: "UI/UX Designer",
      texts: [
        { text: "OMG! I cannot believe that I have got a brand new landing page after getting appmax. It was super easy to edit and publish." }
      ]
    },
    {
      profilePic: "https://cdn.pixabay.com/photo/2016/08/08/09/17/avatar-1577909_960_720.png",
      name: "Mila McSabbu",
      jobProfile: "Freelance Designer",
      texts: [
        { text: "OMG! I cannot believe that I have got a brand new landing page after getting appmax. It was super easy to edit and publish." }
      ]
    },
    {
      profilePic: "https://cdn.iconscout.com/icon/free/png-256/avatar-370-456322.png",
      name: "Robert Fox",
      jobProfile: "UI/UX Designer",
      texts: [
        { text: "OMG! I cannot believe that I have got a brand new landing page after getting appmax. It was super easy to edit and publish." }
      ]
    },
    {
      profilePic: "https://cdn.pixabay.com/photo/2016/08/08/09/17/avatar-1577909_960_720.png",
      name: "Mila McSabbu",
      jobProfile: "Freelance Designer",
      texts: [
        { text: "OMG! I cannot believe that I have got a brand new landing page after getting appmax. It was super easy to edit and publish." }
      ]
    },
    {
      profilePic: "https://cdn.iconscout.com/icon/free/png-256/avatar-370-456322.png",
      name: "Robert Fox",
      jobProfile: "UI/UX Designer",
      texts: [
        { text: "OMG! I cannot believe that I have got a brand new landing page after getting appmax. It was super easy to edit and publish." }
      ]
    },
    {
      profilePic: "https://cdn.pixabay.com/photo/2016/08/08/09/17/avatar-1577909_960_720.png",
      name: "Mila McSabbu",
      jobProfile: "Freelance Designer",
      texts: [
        { text: "OMG! I cannot believe that I have got a brand new landing page after getting appmax. It was super easy to edit and publish." }
      ]
    },
    {
      profilePic: "https://cdn.iconscout.com/icon/free/png-256/avatar-370-456322.png",
      name: "Robert Fox",
      jobProfile: "UI/UX Designer",
      texts: [
        { text: "OMG! I cannot believe that I have got a brand new landing page after getting appmax. It was super easy to edit and publish." }
      ]
    },
  ]
  var settings = {
    autoplay: true,
    autoplaySpeed: 5000,
    speed: 500,
    slidesToShow: customerFeedbackArray?.length >= 3 ? 3 : customerFeedbackArray?.length,
    slidesToScroll: customerFeedbackArray?.length >= 3 ? 2 : customerFeedbackArray?.length - 1,
    initialSlide: 0,
    infinite: true,
    responsive: [
      {
        breakpoint: 1600,
        settings: {
          slidesToShow: 2,
          slidesToScroll: 1,
        }
      },
      {
        breakpoint: 800,
        settings: {
          slidesToShow: 1,
          slidesToScroll: 1,
          initialSlide: 1
        }
      }
    ]
  };
  return (
    <BasicLayout>
      <div className='mainPaddingContainer'>
        <div className={`container-fluid ${styles.container1}`}>
          <div className='row mx-auto' style={{ maxWidth: titleDetails?.titleMaxWidth }}>
            <div className='col-12 mb-4'>
              <h1 className={titleDetails?.titleClass}>{titleDetails?.title}</h1>
            </div>
            <div className='col-12'>
              {titleDetails?.text?.map((item) => {
                return (
                  <p className={item?.textClass} style={{ maxWidth: item?.maxWidth }}>{item?.text}</p>
                )
              })}
            </div>
          </div>
          <div className='d-flex justify-content-center'>
            <a href='https://demo.rearmhq.com' target="_blank" style={{textDecoration:"none"}}><button className={styles.btn_usingFree}>Try Public Demo</button></a>
          </div>
        </div>
        <div className={`d-flex justify-content-center`}>
          <iframe className={`${styles.videoContainer}`} src="https://d7ge14utcyki8.cloudfront.net/ReARM_Demo_Video.mp4" width="100%" height="100%" frameborder="0" allow="autoplay; fullscreen; picture-in-picture" allowFullScreen title="ReARM Demo Walkthrough"></iframe>
        </div>
        <div className={`container-fluid ${styles.container2}`}>
          <div className='row'>
            <h3 className={`text-center ${styles.C2_title}`}>Supports</h3>
            <p className={styles.C2_text}>OWASP Transparency Exchange API</p>
            <div className={`d-flex justify-content-center ${styles.integrationsFlexWrap}`}>
              <img src={tealogo} alt="OWASP Transparency Exchange API Logo" className={styles.favAppIcons} />
            </div>
          </div>
        </div>
        <div className={`container-fluid ${styles.container2}`}>
          <div className='row'>
            <h3 className={`text-center ${styles.C2_title}`}>Integrates</h3>
            <p className={styles.C2_text}>with your favorite tools</p>
            <div className={`d-flex justify-content-center ${styles.integrationsFlexWrap}`}>
              {favApps?.map((item) => {
                return (
                  <img src={item?.icon} alt="" className={styles.favAppIcons} />
                )
              })}
            </div>
          </div>
        </div>
        {/*
        <div className={`container-fluid ${styles.container3}`}>
          <div className='row'>
            <div className='col-12 col-sm-8'>
              <h3 className={`${styles.C2_title}`}>Features</h3>
              <p className={styles.C3_text1}>Our Solution <br className='d-none d-sm-block' /> for your business</p>
              <p className={styles.C3_text2}>We are self-service data analytics software that lets you create visually appealing data visualizations and insightful dashboards in minutes.</p>
              <button className={`${styles.C3_btn_moreFeatures} d-none d-sm-block`}>More Features</button>
            </div>
            <div className='col-12 col-sm-4'>
              <div className={`row ${styles.features_right}`}>
                {featuresArray?.map((item) => {
                  return (
                    <div className={styles.C3_right_card}>
                      <div className='d-flex'>
                        <div><img src={item?.icon} alt="" /></div>
                        <div className='ps-4'>
                          <h4 className={styles.featuresArray_title}>{item?.title}</h4>
                          <p className={styles.featuresArray_text}>{item?.text}</p>
                        </div>
                      </div>
                    </div>
                  )
                })
                }
              </div>
              <button className={`${styles.C3_btn_moreFeatures} d-block d-sm-none`}>More Features</button>
            </div>
          </div>
        </div>
        <div className={`container-fluid ${styles.container4}`}>
          <Experience />
        </div>
              */}
      </div>
      <div className={`container-fluid ${styles.container4}`}>
        {array2?.map((item, index) => {
          return (
            <Component1 details={item} index={index} />
          )
        })}
      </div>
      <div className='mainPaddingContainer'>
        <div className={`container-fluid ${styles.container5}`}>
          {/*
          <div className='row'>
            <h3 className={`text-center ${styles.C2_title} text-capitalize`}>Testimonials</h3>
            <div className={`${styles.testimonialContainer}`}>
              <p className={`text-center ${styles.C5_text}`}>What our happy customer say</p>
              <div className='d-flex d-sm-none'>
                <button className={`${styles.btn_slider_left}`} onClick={() => sliderRef1.current.slickPrev()}><FaArrowLeft /></button>
                <button className={`${styles.btn_slider_right}`} onClick={() => sliderRef1.current.slickNext()}><FaArrowRight /></button>
              </div>
            </div>
            <div className="sliderContinerMain">
              <div className={styles.sliderContiner}>
                <Slider ref={sliderRef1} {...settings}>
                  {customerFeedbackArray?.map((item) => {
                    return (
                      <div>
                        <CustomerCard details={item} />
                      </div>
                    )
                  })}
                </Slider>
              </div>
              <div className='sliderBtnContainer d-none d-sm-block'>
                <button className={`btn_slider ${styles.btn_slider_left}`} onClick={() => sliderRef1.current.slickPrev()}><FaArrowLeft /></button>
                <button className={`btn_slider ${styles.btn_slider_right}`} onClick={() => sliderRef1.current.slickNext()}><FaArrowRight /></button>
              </div>
            </div>
            <div className='d-flex justify-content-center'>
              <button className={styles.btn_allCustomers} onClick={() => navigation("/customers")}>See all customer</button>
            </div>
          </div>
                */}
        </div>
      </div>
      <div id="homePagePricing" className='mainPaddingContainer'>
        <div className={`container-fluid ${styles.container6}`}>
          <h4 className={`${styles.pricingPlan_title1} text-center`}>Pricing & Plans</h4>
          <h4 className={`mx-auto text-center ${styles.pricingPlan_title2}`} style={{ maxWidth: "600px" }}>Fixed predictable rates for any team</h4>
          <PricingPlan />
        </div>
      </div>
      <LastContainer1 />
    </BasicLayout>
  )
}

export default HomePage