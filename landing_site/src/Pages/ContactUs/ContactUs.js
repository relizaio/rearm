import React, { useState } from 'react'
import styles from "./ContactUs.module.css"
import BasicLayout from '../../Layout/BasicLayout/BasicLayout'
import google from "../../Assets/Pricing/brands/google.png"
import amazon from "../../Assets/Pricing/brands/amazon.png"
import intel from "../../Assets/Pricing/brands/intel.png"
import atlassian from "../../Assets/Pricing/brands/atlassian.png"
import sanFransisco from "../../Assets/ContactUs/sanFransisco.png"
import relizaOffice from "../../Assets/ContactUs/relizaOffice.png"
import LastContainer2 from '../../Components/LastContainer/LastContainer2'

const ContactUs = () => {
  const [formData, setFormData] = useState({ name: "", email: "", topic: "", message: "" })
  const brandsArray = [
    { icon: google },
    { icon: amazon },
    { icon: intel },
    { icon: atlassian },
  ]

  const formArray = [
    {
      name: "name",
      label: "Your name*",
      placeHolder: "Enter your name",
      type: "text",
    },
    {
      name: "email",
      label: "Your email*",
      placeHolder: "Enter your email",
      type: "email",
    },
    {
      name: "topic",
      label: "Choose topic*",
      placeHolder: "Select one topic",
      type: "select",
      options: [
        { title: "Select one topic", value: "0" },
        { title: "title1", value: "1" },
        { title: "title2", value: "2" },
        { title: "title3", value: "3" },
      ]
    },
    {
      name: "message",
      label: "Message*",
      placeHolder: "Write your message",
      type: "textarea",
    },
  ]
  const officeArray = [
    {
      image: relizaOffice,
      title: "Reliza Office",
      address: "1000 Innovation Drive, Suite 500 Kanata,ONTARIO K2K3E7 Canada",
      mapLink: "https://goo.gl/maps/Y6GZHE9vGALJKM4T8"
    },
    {
      image: sanFransisco,
      title: "San Fransisco",
      address: "1085 Homer St. Vancouver BC, CanadaV6B 2X5",
      mapLink: "https://goo.gl/maps/vP3Te2sh1N6ne2Wn8"
    },
  ]
  const handleChange = (e) => {
    setFormData({ ...formData, [e.target.name]: e.target.value })
  }
  const handleSubmit = () => {
    // console.log(formData);
  }
  return (
    <BasicLayout>
      <div>
        <div className={`${styles.contactUs}`}>
          <div className={`${styles.container1} mainPaddingContainer`}>
            <div className='container-fluid'>
              <div className='row'>
                <div className='col-12 col-md-6'>
                  <div style={{ maxWidth: "536px" }}>
                    <h3 className={styles.C1_title1}>Contact Us</h3>
                    <h3 className={styles.C1_title2} style={{ maxWidth: "500px" }}>Talk to our product analytics expert</h3>
                    <p className={styles.C1_text1}>Have questions about pricing, plans, or Growthly? Fill out the form and our product analytics expert will be in touch directly.</p>
                    <div className='d-flex justify-content-between flex-wrap' style={{ maxWidth: "400px" }}>
                      <a className={`${styles.C1_callUs} ${styles.C1_btn1}`} href="tel:6699849439">Call us <br /> (669) 984-9439</a>
                      <a className={`${styles.C1_emailUs} ${styles.C1_btn1}`} href="mailto:help@Reliza.com">Email us <br /> help@Reliza.com</a>
                    </div>
                  </div>
                </div>
                <div className='col-12 col-md-6'></div>
              </div>
            </div>
          </div>
          <div className={`${styles.containerForm} container-fluid mainPaddingContainer`}>

            <div className='row d-flex justify-content-center'>
              <div className={styles.C1_right_card} style={{ maxWidth: "500px" }}>
                <div className='row g-4'>
                  <div className='col-12'>
                    <h4 className={styles.formTitle}>Contact Us 👋</h4>
                    <p className={styles.formDesc}>If you have any question or issue’s to use our product. Fill the form below. We’ll help you.</p>
                  </div>
                  <div className='col-12'>
                    <form>
                      <div className='row g-4'>
                        {formArray?.map((item) => {
                          return (
                            item?.type === "textarea" ?
                              <div className='col-12'>
                                <label for={item?.name} className={styles.formLabel}>{item?.label}</label>
                                <textarea className={styles.formInput} id={item?.name} name={item?.name} rows="4" cols="50" value={formData?.[item.name]} onChange={handleChange} placeholder={item?.placeHolder} />
                              </div>
                              :
                              item?.type === "select" ?
                                <div className='col-12'>
                                  <label for={item?.name} className={styles.formLabel}>{item?.label}</label>
                                  <select className={styles.formInput} name={item?.name} id={item?.name} value={formData?.[item.name]} onChange={handleChange} placeholder={item?.placeHolder}>
                                    {item?.options?.map((option) => {
                                      return (
                                        <option value={option?.value}>{option?.title}</option>
                                      )
                                    })}
                                  </select>
                                </div>
                                :
                                <div className='col-12 col-md-6'>
                                  <label for={item?.name} className={styles.formLabel}>{item?.label}</label>
                                  <input className={styles.formInput} type={item?.type} size="100%" id={item?.name} name={item?.name} value={formData?.[item.name]} onChange={handleChange} placeholder={item?.placeHolder} />
                                </div>
                          )
                        })}
                        <div className='col-12'>
                          <button className={styles.form_btnSubmit} onClick={handleSubmit}>Contact Us</button>
                        </div>
                      </div>
                    </form>
                  </div>
                </div>
              </div>
            </div>
          </div>
          <div className={`${styles.container2} container-fluid mainPaddingContainer`}>
            <div className={`col-12 col-md-6 ${styles.brandImageContainer}`}>
              {brandsArray?.map((item) => {
                return (
                  <img src={item?.icon} alt="" className={styles.brandImage} />
                )
              })}
            </div>
          </div>
        </div>
        <div className={`container-fluid ${styles.container3} mainPaddingContainer`}>
          <div className={`row ${styles.C3_row1} align-items-center`}>
            <div className='col-12 col-md-6'>
              <h3 className={styles.C3_title1}>Our offices</h3>
            </div>
            <div className='col-12 col-md-6'>
              <p className={styles.C3_text1}>Amazing cities, totally tricked out offices. Explore the world of Oval.</p>
            </div>
          </div>
          <div className='row g-2 g-lg-5'>
            {officeArray?.map((item) => {
              return (
                <div className='col-12 col-md-6'>
                  <div className={`${styles.contactCard}`}>
                    <img src={item?.image} alt="" style={{ width: "100%" }} />
                    <div className={styles.cardBody}>
                      <h4 className={styles.contactCard_title}>{item?.title}</h4>
                      <p className={styles.contactCard_address}>{item?.address}</p>
                      <a className={styles.contactCard_maps} target="_blank" href={item?.mapLink}>Open in Maps &nbsp;  &gt;</a>
                    </div>
                  </div>
                </div>
              )
            })}
          </div>
        </div>
        <LastContainer2 />
      </div>
    </BasicLayout>
  )
}

export default ContactUs