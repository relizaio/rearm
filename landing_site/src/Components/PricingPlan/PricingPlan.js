import React, { useEffect, useRef, useState } from 'react'
import PlanCard from './Components/PlanCard'
import styles from "./PricingPlan.module.css"
import basicPlan from "../../Assets/Pricing/basicPlan.svg"
import premiumPlan from "../../Assets/Pricing/premiumPlan.svg"
import starterPlan from "../../Assets/Pricing/starterPlan.svg"
import Slider from 'react-slick/lib/slider'


const PricingPlan = () => {
    const sliderRef1 = useRef()
    const [planDuration, setPlanDuration] = useState(false)
    const [selectedPlan, setSelectedPlan] = useState(1)
    const monthlyPlan = [
        {
            id: 0,
            title: "Community Edition",
            icon: starterPlan,
            amount: "Free",
            type: "Forever",
            space: "Self-Hosted",
            yearSupport: "Community support",
            querries: "All base SBOM / xBOM storage and retrieval features"
        },
        {
            id: 1,
            title: "Startup Plan",
            icon: starterPlan,
            amount: "$190",
            type: "Per Month",
            space: "Up to 3 team members",
            yearSupport: "Premium support",
            querries: "Approvals and Triggers",
            statistics: "Marketing Release Workflow",
            trial: "Free 90 day trial"
        },
        {
            id: 2,
            title: "Standard Plan",
            icon: basicPlan,
            amount: "$1490",
            type: "Per Month",
            space: "Up to 20 team members",
            yearSupport: "Premium support",
            querries: "Approvals and Triggers",
            statistics: "Marketing Release Workflow",
            domain: "Managed Service with SSO",
            trial: "Free 90 day trial"
        },
        {
            id: 3,
            title: "Enterprise Plan",
            icon: premiumPlan,
            amount: "Contact",
            type: "us",
            space: "More than 20 team members",
            yearSupport: "Premium Support",
            querries: "Approvals and Triggers",
            statistics: "Marketing Release Workflow",
            domain: "Managed Service with SSO, including on-prem install",
            trial: "Free 90 day trial"
        },
    ]
    const yearlyPlan = [
        {
            id: 0,
            title: "Starter Plan",
            icon: starterPlan,
            amount: "Free",
            type: "Per Year",
            space: "10 GB Disk Space",
            yearSupport: "1 Year Support",
            querries: "500 Queries",
            statistics: "Basic Statistics",
            domain: "Free Custom Domain"
        },
        {
            id: 1,
            title: "Basic Plan",
            icon: basicPlan,
            amount: "$432",
            type: "Per Year",
            space: "500 GB Disk Space",
            yearSupport: "5 Year Support",
            querries: "1000 Queries",
            statistics: "Basic Statistics",
            domain: "Free Custom Domain"
        },
        {
            id: 2,
            title: "Premium Plan",
            icon: premiumPlan,
            amount: "$1152",
            type: "Per Year",
            space: "800 GB Disk Space",
            yearSupport: "Unlimited Support",
            querries: "Unlimited Queries",
            statistics: "Full Statistics",
            domain: "Free Custom Domain"
        },
    ]
    const settings = {
        dots: true,
        slidesToShow: monthlyPlan?.length,
        slidesToScroll: 1,
        initialSlide: 0,
        infinite: false,
        centerMode: true,
        className: "center",
        centerPadding: "20px",
        afterChange: function (index) {
            setSelectedPlan(index)
        },
        responsive: [
            {
                breakpoint: 800,
                settings: {
                    slidesToShow: 1,
                    slidesToScroll: 1,
                    initialSlide: 1
                }
            }
        ]
    };
    return (
        <div className={`row ${styles.pricingPlan}`}>
            {/*
            <div className={`col-12 d-flex align-items-center justify-content-center ${styles.durationContainer}`}>
                <span className={`${styles.durationLabel}`}>Monthly</span>
                <label class={styles.switch}>
                    <input type="checkbox" checked={planDuration} onClick={() => setPlanDuration(!planDuration)} />
                    <span class={`${styles.slider} ${styles.round}`}></span>
                </label>
                <span className={`${styles.durationLabel}`}>Yearly</span>
                <span className={`${styles.save}`}>Save 20%</span>
            </div>
            */}
            <div className='col-12'>
                <div className="sliderContinerMain" style={{ padding: "0" }}>
                    <div className={styles.sliderContiner}>
                        <Slider ref={sliderRef1} {...settings}>
                            {planDuration ?
                                yearlyPlan?.map((item) => {
                                    return (
                                        <div className='col-4 p-4'>
                                            <PlanCard item={item} selectedPlan={selectedPlan} setSelectedPlan={setSelectedPlan} />
                                        </div>
                                    )
                                })
                                :
                                monthlyPlan?.map((item) => {
                                    return (
                                        <div className='col-4 p-4'>
                                            <PlanCard item={item} selectedPlan={selectedPlan} setSelectedPlan={setSelectedPlan} />
                                        </div>
                                    )
                                })
                            }
                        </Slider>
                    </div>
                </div>
            </div>
        </div>
    )
}

export default PricingPlan