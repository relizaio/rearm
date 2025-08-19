import type { Metadata } from "next";
import { Geist, Geist_Mono, Inter } from "next/font/google";
import "./globals.css";
import Header from "../components/Header";
import Footer from "../components/Footer";
import Script from "next/script";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

const inter = Inter({
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "ReARM by Reliza",
  description:
    "Supply Chain Security and Digital Asset Management for Releases, SBOMs, xBOMs, and Security Artifacts.",
  metadataBase: new URL(process.env.NEXT_PUBLIC_BASE_URL ?? "https://rearmhq.com"),
  openGraph: {
    title: "ReARM by Reliza",
    description:
      "Supply Chain Security and Digital Asset Management for Releases, SBOMs, xBOMs, and Security Artifacts.",
    url: "/",
    siteName: "ReARM by Reliza",
    type: "website",
  },
  twitter: {
    card: "summary",
    title: "ReARM by Reliza",
    description:
      "Supply Chain Security and Digital Asset Management for Releases, SBOMs, xBOMs, and Security Artifacts.",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <head>
        <link
          rel="stylesheet"
          href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css"
          integrity="sha384-QWTKZyjpPEjISv5WaRU9OFeRpok6YctnYmDr5pNlyT2bRjXh0JMhjY6hW+ALEwIH"
          crossOrigin="anonymous"
        />
      </head>
      
      <body className={`${geistSans.variable} ${geistMono.variable} ${inter.className}`}>
        <Header />
        {children}
        <Footer />
        <Script
          src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"
          integrity="sha384-YvpcrYf0tY3lHB60NNkmXc5s9fDVZLESaAA55NDzOxhy9GkcIdslK1eN7N6jIeHz"
          crossOrigin="anonymous"
          strategy="afterInteractive"
        />
      </body>
    </html>
  );
}
