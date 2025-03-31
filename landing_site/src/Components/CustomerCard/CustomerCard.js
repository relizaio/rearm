import React from 'react'
import styles from "./CustomerCard.module.css"

const CustomerCard = ({ details }) => {
    return (
        <div class={`${styles.customerCard} h-100`}>
            <div class={`card-body`}>
                <div className='row align-items-start m-0' >
                    <div className={`col-12 d-flex ${styles.feedbackCard_userProfile} align-items-center`}>
                        {/* <div className='d-flex '> */}
                            <div className='m-2'>
                                <img src={details?.profilePic} alt="icon" className={styles.profilePic} />
                            </div>
                            <div className='d-flex flex-column justify-content-center h-100 mx-2'>
                                <h6 className={styles.name}>{details?.name}</h6>
                                <p className={styles.jobProfile} >{details?.jobProfile}</p>
                            </div>
                        {/* </div> */}
                    </div>
                    {/* <div className={`col-12 ${styles.feedbackCard_userProfile}`}> */}
                        {details?.texts?.map((text)=>{
                            return(
                                <p className={styles.text}>{text?.text}</p>
                            )
                        })}
                    {/* </div> */}
                </div>
            </div>
        </div>
    )
}

export default CustomerCard