import React, { useState } from 'react'
import styles from "./Integration.module.css"
import AppCard from '../../Components/AppCard/AppCard'
import BasicLayout from '../../Layout/BasicLayout/BasicLayout'
import stack_overflow from "../../Assets/Integrations/stack_overflow.svg"
import dropbox from "../../Assets/Integrations/dropbox.svg"
import google_meet from "../../Assets/Integrations/google_meet.svg"
import mailchimp from "../../Assets/Integrations/mailchimp.svg"
import ms_excel from "../../Assets/Integrations/ms_excel.svg"
import ms_skype from "../../Assets/Integrations/ms_skype.svg"
import slack from "../../Assets/Integrations/slack.svg"
import trello from "../../Assets/Integrations/trello.svg"
import wordpress from "../../Assets/Integrations/wordpress.svg"
import LastContainer2 from '../../Components/LastContainer/LastContainer2'
import TitileComponent from '../../Components/TitileComponent/TitileComponent'

const Integration = () => {
  const [selectedFilter, setSelectedFilter] = useState("")
  const filterButtonArray = [
    {
      title: "All Apps",
      value: "",
    },
    {
      title: "Social",
      value: "Social",
    },
    {
      title: "Business",
      value: "Business",
    },
    {
      title: "Analitycs",
      value: "Analitycs",
    },
    {
      title: "Management",
      value: "Management",
    },
  ]
  const appCardData = [
    {
      icon: stack_overflow,
      title: "Stack Overflow",
      type: "Social",
      text: "orem ipsum dolor sit amet, consectetur adipiscing elit. Lobortis arcu enim urna adipiscing prae"
    },
    {
      icon: ms_excel,
      title: "Microsoft Excel",
      type: "Analitycs",
      text: "orem ipsum dolor sit amet, consectetur adipiscing elit. Lobortis arcu enim urna adipiscing prae"
    },
    {
      icon: ms_skype,
      title: "Microsoft Skype",
      type: "Social",
      text: "orem ipsum dolor sit amet, consectetur adipiscing elit. Lobortis arcu enim urna adipiscing prae"
    },
    {
      icon: trello,
      title: "Trello",
      type: "Management",
      text: "orem ipsum dolor sit amet, consectetur adipiscing elit. Lobortis arcu enim urna adipiscing prae"
    },
    {
      icon: slack,
      title: "Slack",
      type: "Social",
      text: "orem ipsum dolor sit amet, consectetur adipiscing elit. Lobortis arcu enim urna adipiscing prae"
    },
    {
      icon: google_meet,
      title: "Google Meet",
      type: "Management",
      text: "orem ipsum dolor sit amet, consectetur adipiscing elit. Lobortis arcu enim urna adipiscing prae"
    },
    {
      icon: mailchimp,
      title: "Mail chimp",
      type: "Business",
      text: "orem ipsum dolor sit amet, consectetur adipiscing elit. Lobortis arcu enim urna adipiscing prae"
    },
    {
      icon: dropbox,
      title: "Drop Box",
      type: "Business",
      text: "orem ipsum dolor sit amet, consectetur adipiscing elit. Lobortis arcu enim urna adipiscing prae"
    },
    {
      icon: wordpress,
      title: "Wordpress",
      type: "Business",
      text: "orem ipsum dolor sit amet, consectetur adipiscing elit. Lobortis arcu enim urna adipiscing prae"
    },
  ]
  const titleDetails = {
    heading: "Integration",
    title: "All your secrets, where they need to be",
    titleMaxWidth: "900px",
    text: [
      {
        text: "Now you can build fast your awesome website with these ready-to-use blocks,elements and sections.",
        maxWidth: "550px",
      }
    ]
  }
  return (
    <BasicLayout>
      <div className='mainPaddingContainer'>
        <div className={`container-fluid ${styles.container1}`}>
          <TitileComponent titleDetails={titleDetails} />
        </div>
        <div className={`container-fluid ${styles.container2}`}>
          <div className={`d-flex justify-content-between mx-auto ${styles.C2_btnContainer}`} >
            {filterButtonArray?.map((item, index) => {
              return (
                <div className='d-flex justify-content-center'>
                  <button className={selectedFilter === item?.value ? styles.integration_btn_filter_active : styles.integration_btn_filter_inactive} onClick={() => setSelectedFilter(item?.value)}>{item?.title}</button>
                </div>
              )
            })}
          </div>
          <div className='row'>
            {selectedFilter === "" ?
              appCardData?.map((item, index) => {
                return (
                  <div className='col-12 col-sm-6 col-lg-4 p-3' key={index}>
                    <AppCard item={item} />
                  </div>
                )
              })
              : appCardData?.filter((item) => item?.type === selectedFilter)?.map((item, index) => {
                return (
                  <div className='col-12 col-sm-6 col-lg-4 p-3' key={index}>
                    <AppCard item={item} />
                  </div>
                )
              })}
          </div>
        </div>
      </div>
      <LastContainer2 />
    </BasicLayout>
  )
}

export default Integration