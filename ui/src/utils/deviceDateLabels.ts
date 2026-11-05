// The device support window's two dates, by their IMDRF N70 meaning (FDAW-3, B11).
//
// End of life is the LAST SALE DATE; end of support is the date of the LAST SECURITY UPDATE.
// Under IMDRF N70 end of life normally comes first. The old copy ("end of sale / end of life
// (EOL)") read the two as one and told the reader nothing about which question each answers.
//
// The two header strings must equal the backend's FdaDocumentStrings.DEVICE_EOS_LABEL /
// DEVICE_EOL_LABEL byte for byte: the addendum CSV header is read back by label on the backend
// side, and the UI CSV is the same document (addendumParity.spec.ts).

/** Header of the device end-of-support row in the addendum and the support statement. */
export const DEVICE_EOS_LABEL = 'Device end of support (last security update)'
/** Header of the device end-of-life row in the addendum and the support statement. */
export const DEVICE_EOL_LABEL = 'Device end of life (last sale date)'
/** Field label of the end-of-support picker (component Device tab, shipment override). */
export const EOS_FIELD_LABEL = 'End of support (last security update)'
/** Field label of the end-of-life picker (component Device tab, shipment override). */
export const EOL_FIELD_LABEL = 'End of life (last sale date)'
