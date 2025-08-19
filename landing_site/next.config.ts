import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Enable static export for serving via Nginx
  output: "export",
  images: {
    // Allow next export to emit <img> without optimization step
    unoptimized: true,
  },
  trailingSlash: true,
};

export default nextConfig;
