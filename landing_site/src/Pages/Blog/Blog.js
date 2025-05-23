import React from 'react'
import BasicLayout from '../../Layout/BasicLayout/BasicLayout'
import styles from "./Blog.module.css"
import about1 from "../../Assets/AboutUs/about1.png"
import about2_1 from "../../Assets/AboutUs/about2_1.png"
import about2_2 from "../../Assets/AboutUs/about2_2.png"
import ChooseUsCard from './Components/ChooseUsCard/ChooseUsCard'
import Deployment from "../../Assets/AboutUs/Deployment.png"
import Changes from "../../Assets/AboutUs/Changes.png"
import Audit from "../../Assets/AboutUs/Audit.png"
import team_profile1 from "../../Assets/AboutUs/profilePics/team/team_profile1.png"
import team_profile2 from "../../Assets/AboutUs/profilePics/team/team_profile2.png"
import team_profile3 from "../../Assets/AboutUs/profilePics/team/team_profile3.png"
import board_profile1 from "../../Assets/AboutUs/profilePics/board/board_profile1.png"
import board_profile2 from "../../Assets/AboutUs/profilePics/board/board_profile2.png"
import board_profile3 from "../../Assets/AboutUs/profilePics/board/board_profile3.png"
import aribnb from "../../Assets/AboutUs/teamworkSoftware/aribnb.png"
import fedex from "../../Assets/AboutUs/teamworkSoftware/fedex.png"
import google from "../../Assets/AboutUs/teamworkSoftware/google.png"
import hubspot from "../../Assets/AboutUs/teamworkSoftware/hubspot.png"
import microsoft from "../../Assets/AboutUs/teamworkSoftware/microsoft.png"
import walmart from "../../Assets/AboutUs/teamworkSoftware/walmart.png"
import LastContainer1 from '../../Components/LastContainer/LastContainer1'
import Experience from '../../Components/Experience/Experience'


const AboutPage = () => {
  const chooseUsArray = [
    {
      icon: Deployment,
      title: "Deployments",
      text: "Know exactly what versions of your Bundles and Projects are deployed in each Environment."
    },
    {
      icon: Changes,
      title: "Changes",
      text: 'Do queries like "What are all the changes that happened across my instances between 5am and 7am UTC last Tuesday?"'
    },
    {
      icon: Audit,
      title: "Audit",
      text: "Reliza Hub is built for auditability. Every change is recorded."
    },
  ]
  const ourTeam = {
    title: "Meet Our  Team",
    text: "Leadership at Reliza comes from all over. Here is the team at the helm of the ship.",
    profileArray: [
      {
        profilePic: team_profile1,
        name: "Samuel Willson",
        designation: "Digital Artist"
      },
      {
        profilePic: team_profile2,
        name: "Angelina Hellhop",
        designation: "Digital Artist"
      },
      {
        profilePic: team_profile3,
        name: "Kyle Generale",
        designation: "Digital Artist"
      },
      {
        profilePic: team_profile1,
        name: "Samuel Willson",
        designation: "Digital Artist"
      },
      {
        profilePic: team_profile2,
        name: "Angelina Hellhop",
        designation: "Digital Artist"
      },
      {
        profilePic: team_profile3,
        name: "Kyle Generale",
        designation: "Digital Artist"
      },
      {
        profilePic: team_profile1,
        name: "Samuel Willson",
        designation: "Digital Artist"
      },
      {
        profilePic: team_profile2,
        name: "Angelina Hellhop",
        designation: "Digital Artist"
      },
      {
        profilePic: team_profile3,
        name: "Kyle Generale",
        designation: "Digital Artist"
      },
    ]
  }
  const ourBoard = {
    title: "Meet Our  Advisory board",
    text: "Leadership at Reliza comes from all over. Here is the team at the helm of the ship.",
    profileArray: [
      {
        profilePic: board_profile1,
        name: "Samuel Willson",
        designation: "Digital Artist"
      },
      {
        profilePic: board_profile2,
        name: "Angelina Hellhop",
        designation: "Digital Artist"
      },
      {
        profilePic: board_profile3,
        name: "Kyle Generale",
        designation: "Digital Artist"
      },
      {
        profilePic: board_profile1,
        name: "Samuel Willson",
        designation: "Digital Artist"
      },
      {
        profilePic: board_profile2,
        name: "Angelina Hellhop",
        designation: "Digital Artist"
      },
      {
        profilePic: board_profile3,
        name: "Kyle Generale",
        designation: "Digital Artist"
      },
      {
        profilePic: board_profile1,
        name: "Samuel Willson",
        designation: "Digital Artist"
      },
      {
        profilePic: board_profile2,
        name: "Angelina Hellhop",
        designation: "Digital Artist"
      },
      {
        profilePic: board_profile3,
        name: "Kyle Generale",
        designation: "Digital Artist"
      },
    ]
  }
  const teamWorkSoftware = [
    { icon: aribnb },
    { icon: hubspot },
    { icon: google },
    { icon: microsoft },
    { icon: walmart },
    { icon: fedex },
  ]
  return (
    <BasicLayout>
      <div className={`${styles.container1} container-fluid`}>
        <div className='row'>
          <div className={`col-12 col-sm-7`}>
            <div className={`mainPaddingContainer_sm_7`}>
              <h3 className={styles.C1_title1}>About Us</h3>
              <h3 className={styles.C1_title2}>Reliza - Future of DevOps</h3>
              <p className={styles.C1_text1}>Reliza was established in 2019 to help companies navigate modern DevOps practices and transition to GoalOps. Our mission is to connect whole tech organization - from Developers to Marketing and Sales - around the common Goal. <hr style={{ height: "0", margin: "5px 0" }} />Reliza Hub is currently working in a public preview mode free of charge! You may start using it here - no registration required to try! Reliza Hub is a GoalOps SaaS platform that provides single pane of glass view for your releases, instances and deployments. Whether you're following a mono-repo or multi-repo, monolith or microservices, Git or SVN, Reliza Hub is there to help organize everything.</p>
            </div>
          </div>
          <div className='col-12 col-sm-5 p-0'>
            <div className={styles.C1_right}>
              <div className={styles.C1_right1}><img src={about1} alt='' style={{ width: "100%" }} /></div>
              <div className={styles.C1_right2}><img src={about2_1} alt='' style={{ width: "100%" }} /></div>
              <div className={styles.C1_right3}><img src={about2_2} alt='' style={{ width: "100%" }} /></div>
            </div>
          </div>
        </div>
      </div>
      <div className="mainPaddingContainer">
        <div className={`container-fluid ${styles.container2}`}>
          <Experience />
        </div>
        <div className={`container-fluid ${styles.container3}`}>
          <div className='row'>
            <div className='col-12 col-md-6'>
              <h3 className={styles.C1_title1}>Why Choose Us</h3>
              <h3 className={styles.C1_title2}>A Tool For Futur Of Developer Work</h3>
            </div>
            <div className={`row ${styles.cardGap}`}>
              {chooseUsArray?.map((item) => {
                return (
                  <div className='col-12 col-sm-6 col-md-4'>
                    <ChooseUsCard item={item} />
                  </div>
                )
              })}
            </div>
          </div>
        </div>
      </div>
      <LastContainer1 />
    </BasicLayout >
  )
}

export default AboutPage