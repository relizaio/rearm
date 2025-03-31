import React from 'react'
import styles from "./Component1.module.css"

const Component1 = ({ details, index }) => {

  return (
    <div className={`${index % 2 === 0 ? "row" : "row flex-row-reverse"} ${styles?.component1Main} align-items-center`}>
      <div className={`col-12 col-sm-6`}>
        <div className={`${styles.textContent}`}>
          <h3 className={styles.title}>{details?.title}</h3>
          {details?.texts?.map((text) => {
            return (
              <><p className={styles.text}>{text?.text}</p><br className={`d-none d-sm-block`} /></>
            )
          })}
          {/* <div className='d-flex'><a href='https://app.relizahub.com' target="_blank" style={{ textDecoration: "none" }}><button className={`d-none d-sm-block ${styles.btn_usingFree}`}>Start using for free</button></a></div> */}
        </div>
      </div>
      <div className={`col-12 col-sm-6 ${styles.imageCard}`}>
        <img src={details?.image} alt="" style={{ width: "100%" }} />
      </div>
      <div className='d-flex'><a href='https://app.relizahub.com' target="_blank" style={{ textDecoration: "none" }}><button className={`d-block d-sm-none ${styles.btn_usingFree}`}>Start using for free</button></a></div>
    </div>
  )
}

export default Component1