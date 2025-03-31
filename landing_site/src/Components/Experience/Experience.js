import React from 'react'
import styles from "./Experience.module.css"
import experience from "../../Assets/AboutUs/experience.svg"
import users from "../../Assets/AboutUs/users.svg"
import customers from "../../Assets/AboutUs/customers.svg"


const Experience = () => {
    const container2Array = [
        {
          image: experience,
          text1: "10",
          text2: "years experience"
        },
        {
          image: users,
          text1: "2M+",
          text2: "Users"
        },
        {
          image: customers,
          text1: "70k",
          text2: "companies"
        },
      ]
    return (
        <div className={`d-flex justify-content-between ${styles.experience}`}>
            {/* <div className='d-flex justify-content-evenly flex-wrap'> */}
            {container2Array?.map((item) => {
                return (
                    <div className='justify-content-center d-flex'>
                            <div><img src={item?.image} alt="" className={styles.image} /></div>
                            <div className={styles.textBox}>
                                <h4 className={styles.C2_text1}>{item?.text1}</h4>
                                <p className={styles.C2_text2}>{item?.text2}</p>
                            </div>
                    </div>
                )
            })
            }
            {/* </div> */}
        </div>
    )
}

export default Experience