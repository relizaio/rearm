import Blog from "../../Pages/Blog/Blog";
// import AboutPage from "../../Pages/AboutPage/AboutPage";
// import ContactUs from "../../Pages/ContactUs/ContactUs";
// import Customers from "../../Pages/Customers/Customers";
// import Integration from "../../Pages/Integration/Integration";
// import Pricing from "../../Pages/Pricing/Pricing";
import Services from "../../Pages/Services/Services";

export const navLinks = [
    {
        title: 'Blog',
        path: "/blog",
        element:<Blog/>
    }
] 
/*
[
    {
        title: 'About',
        path: "/about",
        element:<AboutPage/>
    },
    {
        title: 'Integration',
        path: "/integration",
        element:<Integration/>
    },
    {
        title: 'Customers',
        path: "/customers",
        element:<Customers/>
    },
    {
        title: 'Pricing',
        path: "/pricing",
        element:<Pricing/>
    },
    {
        title: 'Contact Us',
        path: "/contact-us",
        element:<ContactUs/>
    },
]
*/