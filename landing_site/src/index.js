import React from 'react';
// import ReactDOM from 'react-dom';
import {createRoot} from 'react-dom/client';
import './index.css';
import App from './App';
import reportWebVitals from './reportWebVitals';
import { BrowserRouter } from 'react-router-dom';
import ScrollToTop from './Components/ScrollToTop/ScrollToTop';
import { Buffer } from 'buffer'

const rootElement = document.getElementById('root');
const root = createRoot(rootElement);
window.Buffer = Buffer

root.render(
  <React.StrictMode>
    <BrowserRouter>
      <ScrollToTop >
        <App />
      </ScrollToTop>
    </BrowserRouter>
  </React.StrictMode>
)

// If you want to start measuring performance in your app, pass a function
// to log results (for example: reportWebVitals(console.log))
// or send to an analytics endpoint. Learn more: https://bit.ly/CRA-vitals
reportWebVitals();
