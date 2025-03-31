import React from 'react'
import { Link } from 'react-router-dom'
import styles from "../../Pages/ContactUs/ContactUs.module.css"

const LastContainer1 = () => {
    return (
        <div className='integration-getStarted'>
            <div className='container' style={{ padding: "6% 25px" }}>
                <div className='row align-items-center'>
                    <div className='col-12'>
                        <p className='text-center' >Questions about product or pricing?</p>
                        <h1 className='text-center mx-auto' style={{ maxWidth: "650px" }}>Book demo with us!</h1>
                    </div>
                    <div className='d-flex justify-content-center' >
                        {/*<Link className='contactUs-btn_ContactUs fw-bold' to="/contact-us">Book Demo</Link>*/}
                        <a className='contactUs-btn_ContactUs fw-bold' href="https://calendly.com/pavel_reliza/demo">Book Private Demo</a>
                    </div>
                </div>
            </div>
            <div id="mc_embed_signup">
                <form action="https://relizahub.us10.list-manage.com/subscribe/post?u=4b4c84aa576f5c0665c654b00&amp;id=5ec5adc899&amp;f_id=00b7e5e5f0" method="post" id="mc-embedded-subscribe-form" name="mc-embedded-subscribe-form" target="_self">
                    <div className='container text-center' id="mc_embed_signup_scroll">
                        <div className='row align-items-center' >
                            <div className='col-12'>
                                <input type="email" className={styles.formInput} name="EMAIL" id="mce-EMAIL" placeholder="Enter your email address to subscribe to our newsletter" required />
                                <span id="mce-EMAIL-HELPERTEXT"></span>
                            </div>
                            <div id="mce-responses">
                                <div id="mce-error-response" style={{display: 'none'}}></div>
                                <div id="mce-success-response" style={{display: 'none'}}></div>
                            </div>
                            <div style={{position: 'absolute', left: '-5000px'}} aria-hidden="true">
                                <input type="text" name="b_4b4c84aa576f5c0665c654b00_5ec5adc899" tabIndex="-1" value="" />
                            </div>
                            <div className='d-flex justify-content-center' style={{ padding: "2% 25px" }}>
                                <input className='contactUs-btn_ContactUs fw-bold' type="submit" value="Subscribe" name="subscribe" id="mc-embedded-subscribe" />
                            </div>
                        </div>
                    </div>
                </form>
            </div>
        </div>
    )
}

export default LastContainer1