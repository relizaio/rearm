import React, { useState } from 'react'
import BasicLayout from '../../Layout/BasicLayout/BasicLayout'
import ServiceCard from '../../Components/ServiceCard/ServiceCard'
import WealthNurturing from "../../Assets/Service/WealthNurturing.svg"
import StratagyResearch from "../../Assets/Service/StratagyResearch.svg"
import AutomatedReminders from "../../Assets/Service/AutomatedReminders.svg"
import GuaranteeQuality from "../../Assets/Service/GuaranteeQuality.svg"
import SteadyGrowth from "../../Assets/Service/SteadyGrowth.svg"
import BoostSEORanking from "../../Assets/Service/BoostSEORanking.svg"
import BestEmailTemplates from "../../Assets/Service/BestEmailTemplates.svg"
import AppIntegration from "../../Assets/Service/AppIntegration.svg"
import DailyReports from "../../Assets/Service/DailyReports.svg"
import PricingPlan from '../../Components/PricingPlan/PricingPlan'
import styles from "./Services.module.css"
import Glass from "../../Assets/Service/Glass.png"
import LastContainer2 from '../../Components/LastContainer/LastContainer2'
import TitileComponent from '../../Components/TitileComponent/TitileComponent'


const Services = () => {
  const serviceArray = [
    {
      icon: StratagyResearch,
      title: "Distributed System Architecture and Set Up",
      text: "End to end set up of DevOps architecture and practices in the organization."
    },
    {
      icon: SteadyGrowth,
      title: "Monitoring",
      text: "Deploying and maintaining industry adopted monitoring stacks."
    },
    {
      icon: BoostSEORanking,
      title: "High Availability",
      text: "Configuring and maintaining high availability using industry adopted solutions, such as Kubernetes."
    },
    {
      icon: AppIntegration,
      title: "Incident Response",
      text: "On call, escalation and emergency work on quick incident resolution."
    },
    {
      icon: BestEmailTemplates,
      title: "Notifications",
      text: "Ensuring timely notifications and reporting on important events."
    },
    {
      icon: WealthNurturing,
      title: "Cost Effectiveness",
      text: "Flexible support options with included Reliza Hub subscription provide better cost efficiency than other options."
    }
  ]
  const [selectedButton1, setSelectedButton1] = useState("Security")
  const buttonArray1 = [
    { title: "Security" },
    { title: "Continuous Monitoring" },
    { title: "Data Storage" },
  ]
  const titleDetails = {
    heading: "Services",
    title: "DevOps For Your Organization",
    titleMaxWidth: "900px",
    text: [
      {
        text: "We provide end-to-end DevOps consulting services and support for your organization.",
        maxWidth: "550px",
      }
    ]
  }
  return (
    <BasicLayout>
      <div>
        <div className={`container-fluid ${styles.container1} mainPaddingContainer`}>
          <TitileComponent titleDetails={titleDetails} />
        </div>
        <div className={`container-fluid ${styles.containerService} mainPaddingContainer`}>
          <div className='row justify-content-center'>
            {
              serviceArray?.map((item, index) => {
                return (
                  <div className='col-12 col-sm-6 col-lg-4 col-xxl-3  p-3' key={index}>
                    <ServiceCard item={item} />
                  </div>
                )
              })}
          </div>
        </div>
        <div className={`container-fluid mainPaddingContainer ${styles.container3}`}>
          <h4 className={`${styles.pricingPlan_title1} text-center`}>Service Pricing</h4>
          <h4 className={`mx-auto text-center ${styles.pricingPlan_title2}`} style={{ maxWidth: "600px" }}>Service is billed hourly. <a href="https://calendly.com/pavel_reliza/demo" target="_blank" rel="noopener noreferrer nofollow">Book a demo</a> with us to discuss pricing.</h4>
          <h5 className={`mx-auto text-center`} style={{ maxWidth: "600px" }}>Special monthly support rates and discounts for Reliza Hub subscribers available.</h5>
        </div>
      </div>
    </BasicLayout>
  )
}

export default Services