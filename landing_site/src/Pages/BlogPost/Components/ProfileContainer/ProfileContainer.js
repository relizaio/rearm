import React, { useRef } from 'react'
import styles from "./ProfileContainer.module.css"
import stylesParent from "../../About.module.css"
import Slider from 'react-slick/lib/slider'
import { FaArrowRight, FaArrowLeft } from 'react-icons/fa'


const ProfileContainer = ({ data }) => {
    const sliderRef1 = useRef()
    const settings = {
        autoplay: true,
        autoplaySpeed: 5000,
        speed: 500,
        slidesToShow: data?.profileArray?.length >= 3 ? 3 : data?.profileArray?.length,
        slidesToScroll: data?.profileArray?.length >= 3 ? 2 : data?.profileArray?.length - 1,
        initialSlide: 0,
        infinite: true,
        responsive: [
            {
                breakpoint: 1024,
                settings: {
                    slidesToShow: 2,
                    slidesToScroll: 1,
                }
            },
            {
                breakpoint: 540,
                settings: {
                    slidesToShow: 1,
                    slidesToScroll: 1,
                    initialSlide: 1
                }
            }
        ]
    };
    return (
        <div className={`row`}>
            <div className='col-12'>
                <div className='row'>
                <div className='col-12 col-md-7'>
                        <h3 className={stylesParent.C1_title1}>{data?.title}</h3>
                        <h3 className={`${stylesParent.C1_title2} mb-0`}>{data?.text}</h3>
                    </div>
                    <div className='col-12 col-md-5 align-items-end justify-content-end d-none d-sm-flex'>
                        <div className='sliderBtnContainer'>
                            <button className={`${styles.btn_slider_left}`} onClick={() => sliderRef1.current.slickPrev()}><FaArrowLeft /></button>
                            <button className={`${styles.btn_slider_right}`} onClick={() => sliderRef1.current.slickNext()}><FaArrowRight /></button>
                        </div>
                    </div>
                </div>
            </div>
            <div className='col-12'>
                <div className={`row ${styles.cardGap}`}>
                    <div className="sliderContinerMain" style={{padding:"0"}}>
                        <div className={styles.sliderContiner}>
                            <Slider ref={sliderRef1} {...settings}>
                                {data?.profileArray?.map((profile) => {
                                    return (
                                        <div>
                                            <div className={styles.profileCard}>
                                                <img src={profile?.profilePic} alt="" style={{ width: "100%" }} />
                                                <div className={styles.cardBody}>
                                                    <h4 className={styles.profileCard_title}>{profile?.name}</h4>
                                                    <p className={styles.profileCard_designation}>{profile?.designation}</p>
                                                </div>
                                            </div>
                                        </div>
                                    )
                                })}
                            </Slider>
                        </div>
                        <div className='sliderBtnContainer d-block d-sm-none'>
                            <button className={`btn_slider ${styles.btn_slider_left}`} onClick={() => sliderRef1.current.slickPrev()}><FaArrowLeft /></button>
                            <button className={`btn_slider ${styles.btn_slider_right}`} onClick={() => sliderRef1.current.slickNext()}><FaArrowRight /></button>
                        </div>
                    </div>

                </div>
            </div>
        </div>
    )
}

export default ProfileContainer