import React from 'react'
import styles from "./ServiceCard.module.css"

const ServiceCard = ({ item }) => {
    return (
        <div class={`${styles.appCard} h-100`}>
            <div>
                <div className='row align-items-start' >
                    <div className='col-12 d-flex justify-content-center mb-3'>
                        <img src={item?.icon} alt="icon" style={{ width: "54px" }} />
                    </div>
                    <div className='col-12'>
                        <h4 className={`text-center fw-bold ${styles.title}`} style={{ color: "rgba(0,0,0,0,8)", fontSize:"20px" }}>{item?.title}</h4>
                        <p className={`text-center mb-2 ${styles.text}`} style={{ color: "#ADABB7",fontSize:"16px", lineHeight:'26px%' }}>{item?.text}</p>
                    </div>
                </div>
            </div>
        </div>
    )
}

export default ServiceCard