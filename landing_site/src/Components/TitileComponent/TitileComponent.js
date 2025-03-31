import React from 'react'
import styles from "./TitileComponent.module.css"

const TitileComponent = ({ titleDetails }) => {
    return (
        <div className='row mx-auto' style={{ maxWidth: titleDetails?.titleMaxWidth }}>
            <div className='col-12'>
                <p className={styles.heading}>{titleDetails?.heading}</p>
            </div>
            <div className='col-12'>
                <h1 className={styles.title}>{titleDetails?.title}</h1>
            </div>
            <div className='col-12'>
                {titleDetails?.text?.map((item) => {
                    return (
                        <p className={`${styles.text} mx-auto`} style={{ maxWidth: item?.maxWidth }}>{item?.text}</p>
                    )
                })}
            </div>
        </div>
    )
}

export default TitileComponent