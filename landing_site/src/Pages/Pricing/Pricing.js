import React from 'react'
import BasicLayout from '../../Layout/BasicLayout/BasicLayout'
import TitileComponent from '../../Components/TitileComponent/TitileComponent'
import PricingPlan from '../../Components/PricingPlan/PricingPlan'
import styles from "./Pricing.module.css"
import google from "../../Assets/Pricing/brands/google.png"
import amazon from "../../Assets/Pricing/brands/amazon.png"
import intel from "../../Assets/Pricing/brands/intel.png"
import gillette from "../../Assets/Pricing/brands/gillette.png"
import cannon from "../../Assets/Pricing/brands/cannon.png"
import atlassian from "../../Assets/Pricing/brands/atlassian.png"
import { Link } from 'react-router-dom'
import Accordian from '../../Components/Accordian/Accordian'
import LastContainer1 from '../../Components/LastContainer/LastContainer1'


const Pricing = () => {

  const titleDetails = {
    heading:"Pricing",
    title: "One tool for your whole team needs.",
    titleMaxWidth: "925px",
    text: [
      {
        text: "Try Reliza free for 30 days to start connected with all your teams. Cancel any time. No credit card required.",
        maxWidth: "600px",
      }
    ]
  }
  const brandsArray = [
    { icon: google },
    { icon: amazon },
    { icon: intel },
    { icon: gillette },
    { icon: cannon },
    { icon: atlassian },
  ]

  const FAQsArray = [
    {
      title: "How is the free Personal Plan different from the Personal Pro and Team Plans?",
      text: [
        { text: "Upgrade to the Personal Pro Plan for unlimited guests, or the Team Plan if you collaborate with the same group of people automatically. You can also review and remove inactive guests in Settings & Members." }
      ]
    },
    {
      title: "What happens when I go over the guest limit on my Personal Plan?",
      text: [
        { text: "Upgrade to the Personal Pro Plan for unlimited guests, or the Team Plan if you collaborate with the same group of people automatically. You can also review and remove inactive guests in Settings & Members." }
      ]
    },
    {
      title: "How do I try out the Team Plan for free?",
      text: [
        { text: "Upgrade to the Personal Pro Plan for unlimited guests, or the Team Plan if you collaborate with the same group of people automatically. You can also review and remove inactive guests in Settings & Members." }
      ]
    },
    {
      title: "Can I use Reliza for free?",
      text: [
        { text: "Upgrade to the Personal Pro Plan for unlimited guests, or the Team Plan if you collaborate with the same group of people automatically. You can also review and remove inactive guests in Settings & Members." }
      ]
    },
    {
      title: "Do you offer student discounts?",
      text: [
        { text: "Upgrade to the Personal Pro Plan for unlimited guests, or the Team Plan if you collaborate with the same group of people automatically. You can also review and remove inactive guests in Settings & Members." }
      ]
    },
  ]
  return (
    <BasicLayout>
      <div className='mainPaddingContainer'>
        <div className={`container-fluid ${styles.container1}`}>
          <TitileComponent titleDetails={titleDetails} />
        </div>
        <div className={`container-fluid ${styles.container2}`}>
          <PricingPlan />
        </div>
        <div className={`container-fluid`}>
          <div className={`row ${styles.container3}`}>
            <h3 className={`${styles.C3Title1}`}>Leading brands trust Oval for Teamwork Software</h3>
            <div className='col-12 d-flex justify-content-center'>
              {brandsArray?.map((item,index) => {
                return (
                  <img src={item?.icon} alt="" className={`${styles.brandImage} ${(index===3||index===4) ? "d-none d-sm-block":""}`} />
                )
              })}
            </div>
          </div>
        </div>
        <div className={`container-fluid ${styles.container4}`}>
          <div className='row'>
            <div className='col-12 col-sm-5'>
              <h3 className={`${styles.C4Title1}`}>Frequently Ask Question’s</h3>
              <p className={`${styles.C4Text1}`}>Haven’t found what you’re looking for? <br /> Try the <Link to={"/contact-us"}>Contact us</Link></p>
            </div>
            <div className='col-12 col-sm-7'>
              <div class="accordion accordion-flush" id="accordionFlushExample">
                {FAQsArray?.map((item,index) => {
                  return (
                    <Accordian item={item} index={index}/>
                  )
                })}
              </div>
            </div>
          </div>
        </div>
      </div>
        <LastContainer1/>
    </BasicLayout>
  )
}

export default Pricing