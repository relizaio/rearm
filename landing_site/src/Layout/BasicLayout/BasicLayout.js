import React from 'react'
import Header from './Header/Header'
import Footer from './Footer/Footer'

const BasicLayout = ({ children }) => {
  return (
    <div>
      <Header />
      <div style={{ minHeight: "calc(100vh - 616px)" }}>{children}</div>
      <Footer />
    </div>
  )
}

export default BasicLayout