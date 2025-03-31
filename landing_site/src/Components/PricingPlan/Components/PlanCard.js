import React from 'react'
import styles from "../PricingPlan.module.css"
import tickBlue from "../../../Assets/tickBlue.svg"


const PlanCard = ({ item, selectedPlan, setSelectedPlan }) => {
    return (
        <div className={`row ${styles.planCard} ${selectedPlan === item?.id ? styles.planCard_Active : styles.planCard_Inactive}`} onClick={() => setSelectedPlan(item?.id)}>
            <div className='col-12'>
                <div className='mx-auto' style={{ borderBottom: "1px solid #001C80", maxWidth: "300px" }}>
                    <h4 className={`${styles.planCard_title1} text-center`}>{item?.title}</h4>
                    <div className='d-flex justify-content-center'>
                        <img src={item?.icon} alt="" />
                    </div>
                    <h4 className={`mx-auto text-center ${styles.amount}`}>{item?.amount}</h4>
                    <h4 className={`mx-auto text-center ${styles.type}`}>{item?.type}</h4>
                </div>
                <div style={{ maxWidth: "200px" }} className={`mx-auto ${styles.planConditionsMain}`}>
                    <p className={`${styles.planConditions}`}><img src={tickBlue} alt="" style={{ margin: "0 5px" }} /> {item?.space}</p>
                    <p className={`${styles.planConditions}`}><img src={tickBlue} alt="" style={{ margin: "0 5px" }} /> {item?.yearSupport}</p>
                    { item?.querries &&
                        <p className={`${styles.planConditions}`}><img src={tickBlue} alt="" style={{ margin: "0 5px" }} /> {item?.querries}</p>
                    }
                    { item?.statistics &&
                        <p className={`${styles.planConditions}`}><img src={tickBlue} alt="" style={{ margin: "0 5px" }} /> {item?.statistics}</p>
                    }
                    { item?.domain &&
                        <p className={`${styles.planConditions}`}><img src={tickBlue} alt="" style={{ margin: "0 5px" }} /> {item?.domain}</p>
                    }
                    { item?.trial &&
                        <p className={`${styles.planConditions}`}><img src={tickBlue} alt="" style={{ margin: "0 5px" }} /> {item?.trial}</p>
                    }
                </div>
                <div className='d-flex justify-content-center'>
                    { item.id === 0 &&
                        <a href='https://github.com/relizaio/rearm' target="_blank" style={{ textDecoration: "none" }}>
                            <button className={`${selectedPlan === item?.id ? styles.btn_getStarted_active : styles.btn_getStarted_inactive}`}>See On GitHub</button>
                        </a>
                    }
                    { item.id !== 0 &&
                        <a href='mailto:info@reliza.io' style={{ textDecoration: "none" }}>
                            <button className={`${selectedPlan === item?.id ? styles.btn_getStarted_active : styles.btn_getStarted_inactive}`}>Contact us</button>
                        </a>
                    }
                </div>
            </div>
        </div>
    )
}

export default PlanCard