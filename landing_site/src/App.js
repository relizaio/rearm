import './App.css';
import './globalStyles.css';
import "slick-carousel/slick/slick.css";
import "slick-carousel/slick/slick-theme.css";
import { Route, Routes } from "react-router-dom";
import { Buffer } from 'buffer'

import HomePage from './Pages/HomePage/HomePage';
import { navLinks } from './Layout/Constants/NavLinks';

window.Buffer = Buffer

function App() {
  window.Buffer = Buffer

  return (
    <div >
      <Routes >
        <Route path="/" element={<HomePage />} />
        {navLinks?.map((item) => {
          return (
            <Route path={item?.path} element={item?.element} />
          )
        })}
      </Routes>
    </div>
  );
}

export default App;
