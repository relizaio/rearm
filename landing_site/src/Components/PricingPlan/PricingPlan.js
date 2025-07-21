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
    let startupPrice = '$190'
    let standardPrice = '$1490'
    let priceType = 'US'
    const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone
    if (timezone) {
        if (timezone === 'GB' || timezone === 'GB-Eire') {
            priceType = 'GB'
        } else if (timezone.includes('Europe')) {
            switch (timezone) {
                case 'Europe/Belfast':
                case 'Europe/London':
                    priceType = 'GB'
                    break
                default:
                    priceType = 'EU'
                    break
            }
        } else if (timezone.includes('America')) {
            switch (timezone) {
                case 'America/Atikokan':
                case 'America/Blanc-Sablon':
                case 'America/Cambridge_Bay':
                case 'America/Coral_Harbour':
                case 'America/Creston':
                case 'America/Dawson':
                case 'America/Dawson_Creek':
                case 'America/Edmonton':
                case 'America/Fort_Nelson':
                case 'America/Glace_Bay':
                case 'America/Goose_Bay':
                case 'America/Halifax':
                case 'America/Inuvik':
                case 'America/Iqaluit':
                case 'America/Moncton':
                case 'America/Montreal':
                case 'America/Nipigon':
                case 'America/Pangnirtung':
                case 'America/Rainy_River':
                case 'America/Rankin_Inlet':
                case 'America/Regina':
                case 'America/Resolute':
                case 'America/St_Johns':
                case 'America/Swift_Current':
                case 'America/Thunder_Bay':
                case 'America/Toronto':
                case 'America/Vancouver':
                case 'America/Whitehorse':
                case 'America/Winnipeg':
                case 'Canada/Atlantic':
                case 'Canada/Central':
                case 'Canada/Eastern':
                case 'Canada/Mountain':
                case 'Canada/Newfoundland':
                case 'Canada/Pacific':
                case 'Canada/Saskatchewan':
                case 'Canada/Yukon':
                    priceType = 'CA'
                    break
                default:
                    break
            }
        }
    }
    if (priceType === 'EU') {
        startupPrice = '€150'
        standardPrice = '€1250'
    } else if (priceType === 'CA') {
        startupPrice = 'C$240'
        standardPrice = 'C$1990'
    } else if (priceType === 'GB') {
        startupPrice = '£130'
        standardPrice = '£1090'
    }
    const monthlyPlan = [
        {
            id: 0,
            title: "ReARM CE",
            icon: starterPlan,
            amount: "Free",
            type: "Forever",
            space: "FOSS ReARM Community Edition",
            yearSupport: "Self-Hosted",
            querries: "Community support",
            statistics: "All core SBOM / xBOM storage and retrieval features",
            trial: "Vulnerabilities and Violations via self-managed Dependency-Track Integration"
        },
        {
            id: 1,
            title: "ReARM Pro - Startup",
            icon: starterPlan,
            amount: startupPrice,
            type: "Per Month",
            space: "Up to 3 team members",
            yearSupport: "Premium support",
            querries: "Managed Dependency-Track Integration",
            statistics: "Approvals, Triggers and Marketing Releases",
            domain: "Managed Service",
            trial: "Free 90 day trial"
        },
        {
            id: 2,
            title: "ReARM Pro - Standard",
            icon: basicPlan,
            amount: standardPrice,
            type: "Per Month",
            space: "Up to 20 team members",
            yearSupport: "Premium support",
            querries: "Managed Dependency-Track Integration",
            statistics: "Approvals, Triggers and Marketing Releases",
            domain: "Managed Service with SSO",
            trial: "Free 90 day trial"
        },
        {
            id: 3,
            title: "ReARM Pro - Enterprise",
            icon: premiumPlan,
            amount: "Contact",
            type: "us",
            space: "More than 20 team members",
            yearSupport: "Premium Support",
            querries: "Managed Dependency-Track Integration",
            statistics: "Approvals, Triggers and Marketing Releases",
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