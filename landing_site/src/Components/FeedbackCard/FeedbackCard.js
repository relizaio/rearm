import React from 'react'
import styles from "./FeedbackCard.module.css"


const FeedbackCard = ({ item }) => {
    return (
        <div class={`${styles.feedbackCard} h-100`}>
            <div class={`card-body`}>
                <div className='row align-items-start m-0' >
                    <div className='col-12'>
                        <p className={styles.text}>{item?.text}</p>
                    </div>
                    <div className={`col-12 d-flex ${styles.feedbackCard_userProfile}`}>
                        <div className='d-flex'>
                            <div className='m-2'>
                                <img src={item?.profilePic} alt="icon" style={{ width: "54px", borderRadius:"50vh" }} />
                            </div>
                            <div className='d-flex flex-column justify-content-center h-100 mx-4'>
                                <h6 className={styles.name} >{item?.name}</h6>
                                <p className={styles.name} style={{color:"#5C6077"}}>{item?.jobProfile}</p>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    )
}

export default FeedbackCard