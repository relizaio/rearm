// import { useEffect } from "react";
// import { withRouter } from "react-router-dom";

// function ScrollToTop({ history }) {
//     useEffect(() => {
//         const unlisten = history.listen(() => {
//             window.scrollTo(0, 0);
//         });
//         return () => {
//             unlisten();
//         };
//     }, [history]);

//     return null;
// }

// export default withRouter(ScrollToTop);

import { useEffect } from "react";
import { useLocation } from "react-router-dom";


export default function ScrollToTop({children}) {
  const { pathname } = useLocation();

  useEffect(() => {
    window.scrollTo(0, 0);
  }, [pathname]);

  return children;
}