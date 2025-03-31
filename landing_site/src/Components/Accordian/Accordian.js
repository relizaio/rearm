import React from 'react'
import styles from "./Accordian.module.css"

const Accordian = ({ item, index }) => {
    return (
        <div className={`accordion-item`}>
            <h2 className={`accordion-header`} id="flush-headingOne">
                <div className={`${styles.titleBox} accordion-button collapsed`} type="button" data-bs-toggle="collapse" data-bs-target={`#collapse${index}`} aria-expanded="false" aria-controls={`collapse${index}`}>
                    <h4 className={`${styles.title}`}>{item?.title}</h4>
                </div>
            </h2>
            <div id={`collapse${index}`} className={`accordion-collapse collapse`} aria-labelledby="flush-headingOne" data-bs-parent="#accordionFlushExample">
                <div className={`${styles.body}`}>
                    {item?.text?.map((text1) => {
                        return (
                            <p className={`${styles.text}`} >{text1.text}</p>
                        )
                    })}
                </div>
            </div>
        </div>
    )
}

export default Accordian