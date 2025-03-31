import React from 'react'
import styles from "./AppCard.module.css"

const AppCard = ({ item }) => {
    return (
        <div class={`${styles.appCard} h-100`}>
            <div class={`card-body`}>
                <div className='row align-items-start' >
                    <div className='col-12 d-flex justify-content-center mb-3'>
                        <img src={item?.icon} alt="icon" style={{ width: "54px" }} />
                    </div>
                    <div className='col-12'>
                        <h4 className={styles.title} >{item?.title}</h4>
                        <p className={styles.type} >{item?.type}</p>
                        <p className={styles.text} >{item?.text}</p>
                    </div>
                </div>
            </div>
        </div>
    )
}

export default AppCard