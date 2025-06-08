---
title: "SBOMs Remain, Attestations Out - Amendments to Executive Order 14144"
date: "2025-06-08"
---

## SBOMs Remain, Attestations Out - Amendments to Executive Order 14144

US President Donald Trump signed [an executive order](https://www.whitehouse.gov/presidential-actions/2025/06/sustaining-select-efforts-to-strengthen-the-nations-cybersecurity-and-amending-executive-order-13694-and-executive-order-14144/) on June 6, 2025, that introduces substantial amendments to Executive Orders [13694](https://www.federalregister.gov/documents/2015/04/02/2015-07788/blocking-the-property-of-certain-persons-engaging-in-significant-malicious-cyber-enabled-activities) and [14144](https://www.federalregister.gov/documents/2025/01/17/2025-01470/strengthening-and-promoting-innovation-in-the-nations-cybersecurity).

The White House also released a corresponding [fact sheet](https://www.whitehouse.gov/fact-sheets/2025/06/fact-sheet-president-donald-j-trump-reprioritizes-cybersecurity-efforts-to-protect-america/) outlining the details of the changes.

We analyzed the amendments as they relate to SBOM and Attestation requirements. Below are our findings. While we made our best effort to provide an accurate analysis, nothing herein constitutes legal advice. The full implications for U.S. government procurement practices remain to be seen.

### EO 14028 Left Intact

Notably, no changes were made to Executive Order [14028](https://www.federalregister.gov/documents/2021/05/17/2021-10460/improving-the-nations-cybersecurity). However, there were 6 mentions of the EO 14028 in the original text of the EO 14144, but only a single mention survived after the amendments, and it pertains solely to definitions.

This is significant for SBOMs, as EO 14028 explicitly mandates the provision of SBOMs for all procured software products.

### SSDF Becomes the Foundation for Future Guidance

[NIST Special Publication 800–218 (Secure Software Development Framework (SSDF))](https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-218.pdf) is identified as the foundation upon which future industry guidance will be built. SSDF includes SBOMs among its recommended practices for secure software development.

While we should expect that future guidance developed under this new executive order may differ from existing SSDF, it looks very likely that SBOM-related provisions will survive. 

Regulations concerning Internet of Things (IoT) devices, including the [Cyber Trust Mark](https://www.fcc.gov/CyberTrustMark), also appear to remain in effect and may even be expanded. We speculate that SBOM/HBOM combinations and initiatives such as the [Transparency Exchange API](https://github.com/CycloneDX/transparency-exchange-api/) will continue to be highly relevant in this context.

### Attestations No Longer Required

In contrast, the provisions related to Secure Software Development Attestations originally included in EO 14144 have been explicitly removed. While attestations are still mentioned in SSDF, those references are marginal. It now appears very likely that attestations will no longer be mandatory for U.S. government software procurement.

### Immediate Implications

In the short term, the focus of the software supply chain security industry is expected to shift back toward SBOMs rather than attestations. It also seems that mechanisms for SBOM storage, exchange and validation such as Transparency Exchange API are becoming increasingly relevant.

It is still possible that attestation requirements will return at a certain point, but it is unlikely that attestations will remain an immediate focus of the industry. It is more probable that their relevance will re-emerge only after SBOMs are broadly adopted, understood, and integrated into well-defined workflows.