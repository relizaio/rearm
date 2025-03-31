import React from 'react'
import BasicLayout from '../../Layout/BasicLayout/BasicLayout'
import FeedbackCard from '../../Components/FeedbackCard/FeedbackCard'
import LastContainer1 from '../../Components/LastContainer/LastContainer1'
import styles from "./Customers.module.css"
import TitileComponent from '../../Components/TitileComponent/TitileComponent'


const Customers = () => {

  const feedbackArray = [
    {
      profilePic: "https://cdn.pixabay.com/photo/2016/08/08/09/17/avatar-1577909_960_720.png",
      name: "Joshua Franklin",
      jobProfile: "Front-End Developer",
      text: <span>"My wireframing process has seriously upgraded since I’ve started using  Figma.<br /><br /> Wireframing process is easy like never before! Thanks a lot!”</span>
    },
    {
      profilePic: "https://cdn.iconscout.com/icon/free/png-256/avatar-370-456322.png",
      name: "Joshua Franklin",
      jobProfile: "Front-End Developer",
      text: <span>"My wireframing process has seriously upgraded since I’ve started using  Figma.<br /><br /> Wireframing process is easy like never before! Thanks a lot!”</span>,
    },
    {
      profilePic: "https://cdn.iconscout.com/icon/free/png-256/avatar-370-456322.png",
      name: "Joshua Franklin",
      jobProfile: "Front-End Developer",
      text: <span>"My wireframing process has seriously upgraded since I’ve started using  Figma.<br /><br /> Wireframing process is easy like never before! Thanks a lot!”</span>,
    },
    {
      profilePic: "https://cdn.iconscout.com/icon/free/png-256/avatar-370-456322.png",
      name: "Joshua Franklin",
      jobProfile: "Front-End Developer",
      text: <span>"My wireframing process has seriously upgraded since I’ve started using  Figma.<br /><br /> Wireframing process is easy like never before! Thanks a lot!”</span>,
    },
    {
      profilePic: "https://cdn.iconscout.com/icon/free/png-256/avatar-370-456322.png",
      name: "Joshua Franklin",
      jobProfile: "Front-End Developer",
      text: <span>"My wireframing process has seriously upgraded since I’ve started using  Figma.<br /><br /> Wireframing process is easy like never before! Thanks a lot!”</span>,
    },
    {
      profilePic: "https://cdn.iconscout.com/icon/free/png-256/avatar-370-456322.png",
      name: "Joshua Franklin",
      jobProfile: "Front-End Developer",
      text: <span>"My wireframing process has seriously upgraded since I’ve started using  Figma.<br /><br /> Wireframing process is easy like never before! Thanks a lot!”</span>,
    },
    {
      profilePic: "https://cdn.iconscout.com/icon/free/png-256/avatar-370-456322.png",
      name: "Joshua Franklin",
      jobProfile: "Front-End Developer",
      text: <span>"My wireframing process has seriously upgraded since I’ve started using  Figma.<br /><br /> Wireframing process is easy like never before! Thanks a lot!”</span>,
    },
    {
      profilePic: "https://cdn.iconscout.com/icon/free/png-256/avatar-370-456322.png",
      name: "Joshua Franklin",
      jobProfile: "Front-End Developer",
      text: <span>"My wireframing process has seriously upgraded since I’ve started using  Figma.<br /><br /> Wireframing process is easy like never before! Thanks a lot!”</span>,
    },
    {
      profilePic: "https://cdn.iconscout.com/icon/free/png-256/avatar-370-456322.png",
      name: "Joshua Franklin",
      jobProfile: "Front-End Developer",
      text: <span>"My wireframing process has seriously upgraded since I’ve started using  Figma.<br /><br /> Wireframing process is easy like never before! Thanks a lot!”</span>,
    },
  ]
  const titleDetails = {
    title: "Our Customers",
    titleMaxWidth: "900px",
    text: [
      {
        text: "Now you can build fast your awesome website with these ready-to-use blocks, elements and sections.",
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
            <div className='row justify-content-center'>
              {feedbackArray?.map((item, index) => {
                return (
                  <div className='col-12 col-sm-6 col-lg-4 p-1 mb-3 mb-sm-0 p-sm-3' key={index}>
                    <FeedbackCard item={item} />
                  </div>
                )
              })}
            </div>
          </div>
        </div>
        <LastContainer1 />
    </BasicLayout>
  )
}

export default Customers