import React from 'react'
import styles from "./ChooseUsCard.module.css"

const ChooseUsCard = ({ item }) => {
    return (
        <div class={`${styles.appCard} h-100`}>
            <div>
                <div className='row align-items-start' >
                    <div className='col-12 d-flex justify-content-center mb-4'>
                        <img src={item?.icon} alt="icon" />
                    </div>
                    <div className='col-12'>
                        <h4 className={`${styles.title}`}>{item?.title}</h4>
                        <p className={`${styles.text}`} >{item?.text}</p>
                    </div>
                </div>
            </div>
        </div>
    )
}

export default ChooseUsCard