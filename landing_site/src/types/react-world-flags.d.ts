declare module "react-world-flags" {
  import { FC, CSSProperties } from "react";

  interface FlagProps {
    code: string;
    style?: CSSProperties;
    className?: string;
    height?: number | string;
    width?: number | string;
    fallback?: React.ReactNode;
  }

  const Flag: FC<FlagProps>;
  export default Flag;
}
