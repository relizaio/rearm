#!/usr/bin/env python3
"""Generate the D-Link R18 HBOM (CycloneDX 2.0-dev) from what has been observed so far.
Evidence sources: board photos (2026-11-08), stock serial boot log (../r18-boot-clean.txt),
OpenWrt RAM-boot bring-up on the device (2026-11-09: LED/button/port tests, mt76, PCIe).
Run: python3 gen_hbom.py > d-link-r18.hbom.cdx.json
"""
import json

SERIAL = "urn:uuid:46c3e053-2a89-48dc-98a3-d160ebe58ac3"
TS = "2026-11-11T12:00:00Z"


def prop(name, value):
    return {"name": name, "value": value}


def org(ref, name, roles, iso=None, locality=None, url=None):
    o = {"name": name}
    if iso:
        a = {"isoCode": iso, "country": iso[:2]}
        if locality:
            a["locality"] = locality
        o["addresses"] = [a]
    if url:
        o["url"] = [{"url": url}]
    return {"refId": ref, "roles": [{"role": r} for r in roles], "organization": o}


def mfr(ref):
    return [{"ref": ref}]


def mpn(party, *values):
    return [{"party": party, "identities": [{"scheme": "mpn", "value": v} for v in values]}]


def cls(category, sub=None, hs=None):
    c = {"category": category}
    if sub:
        c["subcategory"] = sub
    if hs:
        c["codes"] = [{"standard": "HS", "value": hs[0], "url": "https://www.trade.gov/harmonized-system-hs-codes"}]
    return c


def evidence(*src):
    return prop("r18:evidence", "; ".join(src))


# --- parties (declared once on the device, referenced elsewhere by refId) ---
parties = [
    org("party-dlink", "D-Link Corporation", ["manufacturer", "supplier"], "TW", "Taipei", "https://www.dlink.com"),
    org("party-econet", "EcoNet Technology (Airoha / MediaTek group)", ["manufacturer"], "TW", url="https://www.econet-inc.com"),
    org("party-mediatek", "MediaTek Inc.", ["manufacturer"], "TW", "Hsinchu", "https://www.mediatek.com"),
    org("party-macronix", "Macronix International Co., Ltd.", ["manufacturer"], "TW", "Hsinchu", "https://www.mxic.com.tw"),
    org("party-nanya", "Nanya Technology Corporation", ["manufacturer"], "TW", "New Taipei", "https://www.nanya.com"),
    org("party-fpe", "FPE (magnetics vendor, marking 'FPE')", ["manufacturer"]),
]

# ---------------- firmware / data held in SPI-NAND ----------------
fw_bootloader = {
    "type": "firmware", "refId": "fw-bootbase",
    "name": "EcoNet EN7528 free bootbase", "version": "1.1",
    "description": "Stage-1/2 bootloader in NAND 0x0-0x40000 (mtd 'bootloader'). Built 'Sat 26 Nov 2022 12:46:20 AM CST'. "
                   "Offers 1 s window: \"Press 'x' or 'b' key ... to enter or skip bootloader upgrade\". Default IP 192.168.0.1. "
                   "Handles dual-image (tclinux/tclinux_slave) selection via benv and writes flash on each boot.",
    "parties": mfr("party-econet"),
    "properties": [prop("r18:nand:offset", "0x00000000"), prop("r18:nand:size", "0x00040000"),
                   evidence("serial boot log")],
}
fw_stock = {
    "type": "firmware", "refId": "fw-stock-tclinux",
    "name": "D-Link R18 stock firmware (TCLinux)",
    "description": "EcoNet SDK based firmware: Linux 3.18.21 SMP (gcc 4.6.3, Buildroot 2015.08.1, built Fri Apr 19 21:09:56 CST 2024), "
                   "BusyBox v1.00, squashfs root. Dual copies: tclinux (0x80000, 45 MiB) and tclinux_slave (0x2d80000, 45 MiB); "
                   "currently booting slave (sw_curboot_id=1). Serial console RX disabled in this firmware.",
    "parties": mfr("party-dlink"),
    "components": [
        {"type": "operating-system", "refId": "sw-linux-3.18.21", "name": "linux", "version": "3.18.21"},
        {"type": "application", "refId": "sw-busybox-1.00", "name": "busybox", "version": "1.00"},
    ],
    "properties": [prop("r18:nand:partitions", "tclinux@0x80000+0x2d00000, tclinux_slave@0x2d80000+0x2d00000"),
                   evidence("serial boot log")],
}

data_parts = {
    "type": "data", "refId": "data-nand-config",
    "name": "Device configuration and identity partitions",
    "description": "romfile 0x40000 (256K); Config0 0x5a80000, Config1 0x5b80000, ODM 0x5c80000 (CSID 'P section': model R18, "
                   "romid DLK6E8112001, default IP; base MAC as TLV record value at ODM+0x82), benv 0x5d80000 (boot flags sw_curboot_id/"
                   "sw_commit/sw_active/sw_tryactive), mydlink 0x5e80000 (2M), "
                   "storage 0x6080000 (9M), unnamed gap 0x6980000-0x6dc0000, reservearea 0x6dc0000-0x7000000 (BMT/BBT pool + backups; "
                   "Wi-Fi EEPROM probed at offset 0 but all zeros). Contains per-unit secrets (ODM) - not for publication.",
    "properties": [evidence("serial boot log (MTD table)")],
}

fw_wifi = {
    "type": "firmware", "refId": "fw-mt7915-wm",
    "name": "MediaTek MT7915 WM/WA firmware (stock, vendor driver)",
    "version": "MT7915_MP_7_4_2045-20220915163802",
    "description": "Loaded from rootfs by the proprietary RT2860AP/mt_wifi driver. Calibration falls back to "
                   "/etc/Wireless/RT2860AP_AC/RT30xxEEPROM.bin in rootfs (3584 B, chip id 0x7915, generic MAC 00:0c:43:26:46:44 "
                   "= MediaTek default image, not per-unit). No per-unit cal in flash; efuse also empty (mt76: 'eeprom load fail'). "
                   "OpenWrt mt76 works with this image + linux-firmware MT7915 WM/WA (build 20240429).",
    "parties": mfr("party-mediatek"),
    "properties": [evidence("serial boot log ('t-neptune-mp-mt7915-2045')")],
}

# ---------------- hardware ----------------
soc = {
    "type": "device", "refId": "soc-en7528hu",
    "name": "EN7528HU", "group": "EcoNet",
    "description": "Network SoC, MIPS32 1004Kc (2 cores x 2 VPE = 4 threads) @ 900 MHz, 32K I$/D$, 256K L2. Integrated 4-port "
                   "GbE switch + PHYs (MT7530-compatible, mmio), 2x PCIe RC: RC0 (0x1fb81000) Gen1-only PHY, RC1 (0x1fb83000) Gen2 x1, "
                   "HW NAT/QDMA, SPI-NAND controller, UART, GPIO (42 pins, IRQ-capable).",
    "parties": mfr("party-econet"),
    "identifiers": mpn("party-econet", "EN7528HU"),
    "classification": cls("semiconductor", "system-on-chip", ("8542.31",)),
    "deviceType": "qfp",
    "quantity": {"value": 1},
    "properties": [prop("r18:marking", "ECONET / EN7528HU / 2144-AFAL / ACMSLF8H"),
                   prop("r18:date-code", "2144 (2021 wk44)"),
                   prop("r18:cpu-prid", "0x0001992f"),
                   prop("r18:linux:compatible", "econet,en751221 (stock); OpenWrt target econet/en7528, board dlink,r18"),
                   prop("r18:pcie:rc0", "Gen1-only PHY; must not be retrained (Gen2 retrain breaks MMIO to Gen2-capable endpoint)"),
                   prop("r18:pcie:rc1", "Gen2 x1, link 5.0 GT/s after retrain"),
                   evidence("photo", "serial boot log")],
}

dram = {
    "type": "device", "refId": "dram-nanya-ddr3",
    "name": "NT5CC128M16JR-EK", "group": "Nanya",
    "description": "2 Gbit (128M x16) DDR3L SDRAM, single device next to SoC, initialised by bootbase as "
                   "'DDR3 ... DRAM size=256MB, ddr-900' (run at DDR-900). 256 MiB confirmed usable under Linux 6.18 "
                   "(OpenWrt RAM boot).",
    "parties": mfr("party-nanya"),
    "identifiers": mpn("party-nanya", "NT5CC128M16JR-EK"),
    "boardLocation": {"designators": ["U1001"], "layer": "top"},
    "classification": cls("semiconductor", "dram", ("8542.32",)),
    "deviceType": {"name": "FBGA-96"},
    "quantity": {"value": 1},
    "properties": [prop("r18:capacity", "256 MiB"),
                   prop("r18:marking", "NANYA 2205 / NT5CC128M16JR-EK"),
                   prop("r18:date-code", "2205 (2022 wk05)"),
                   prop("r18:package-evidence", "silkscreen ball grid 1-9 x A-T (96 balls)"),
                   evidence("photo 2026-11-09", "serial boot log")],
}

flash = {
    "type": "device", "refId": "u15-spinand",
    "name": "MX35LF1GE4AB", "group": "Macronix", "version": "-Z4I",
    "description": "1 Gbit (128 MiB) 3.3 V SLC SPI-NAND, 2048+64 B pages, 128 KiB blocks, on-die ECC. "
                   "JEDEC ID c2 12. Vendor BMT/BBT pool of 81 blocks at end of device. Full raw dump verified twice "
                   "(sha256 0f136bde...0fd367); 0 bad-block markers, only 1-bit ECC corrections.",
    "parties": mfr("party-macronix"),
    "identifiers": mpn("party-macronix", "MX35LF1GE4AB-Z4I"),
    "classification": cls("semiconductor", "nand-flash", ("8542.32",)),
    "boardLocation": {"designators": ["U15"], "layer": "top"},
    "deviceType": {"name": "WSON-8 8x6mm"},
    "quantity": {"value": 1},
    "components": [fw_bootloader, fw_stock, data_parts],
    "properties": [prop("r18:marking", "MXIC X221410 / MX35LF1GE4AB-Z4I / 2S609279"),
                   prop("r18:marking-note", "line 1 is X221410, confirmed by owner on the part 2026-11-11 (earlier misread as X22J410)"),
                   prop("r18:package-evidence", "close-up 2026-11-11: no lead shoulders/bends, flat solder-wetted PCB pads "
                        "extending past the body (no-lead WSON, not gull-wing SOP); 4 pads per side at 1.27 mm pitch; "
                        "body ~8 x 6 mm by pitch ratio; consistent with -Z4I = 8-WSON 8x6mm"),
                   prop("r18:pin1-mark", "white ink dot in the pin-1 corner (next to the MXIC logo); dot likely a "
                        "factory/programming QC mark (inference)"),
                   evidence("photo", "photo 2026-11-11 close-up", "serial boot log")],
}

wifi_die = {
    "type": "component-choice", "refId": "choice-wifi-bb",
    "name": "Wi-Fi 6 baseband (under RF shield)",
    "description": "One MT7915-family chip: main host interface 14c3:7915 on PCIe RC1 (Gen2), second host interface (hif2) "
                   "14c3:7916 on RC0 (Gen1). HW_REV 0x94, HW/SW version 0x8a108a10. 4 RF chains, DBDC split 2+2 "
                   "(2.4 GHz chains 0x3, 5 GHz chains 0xc). AX1800 class suggests MT7905DAN; MT7915DAN shares the PCI ID and "
                   "chain count. Recorded as a component choice by design: the RF shield will not be removed (owner decision "
                   "2026-11-11), so the exact part stays unresolved.",
    "operator": "XOR",
    "properties": [prop("r18:resolution", "final: component choice, not to be resolved (shield stays on, owner decision 2026-11-11)"),
                   prop("r18:distinguishing", "software identity is identical (PCI 14c3:7915/7916, HW_REV 0x94, 4 chains); "
                        "only the package marking under the shield would tell them apart"),
                   evidence("serial boot log", "OpenWrt RAM-boot tests")],
    "components": [
        {"type": "device", "refId": "wifi-mt7905dan", "name": "MT7905DAN", "group": "MediaTek",
         "parties": mfr("party-mediatek"), "identifiers": mpn("party-mediatek", "MT7905DAN"),
         "classification": cls("semiconductor", "wifi-soc", ("8542.31",)), "deviceType": "qfn",
         "components": [fw_wifi]},
        {"type": "device", "refId": "wifi-mt7915dan", "name": "MT7915DAN", "group": "MediaTek",
         "parties": mfr("party-mediatek"), "identifiers": mpn("party-mediatek", "MT7915DAN"),
         "classification": cls("semiconductor", "wifi-soc", ("8542.31",)), "deviceType": "qfn",
         "components": [{"ref": "fw-mt7915-wm"}]},
    ],
}

wifi_adie = {
    "type": "device", "refId": "wifi-mt7975",
    "name": "MT7975 (A-die RF transceiver)", "group": "MediaTek",
    "description": "Companion RF die for MT7915/MT7905 ('a-die version:2' in log). Under the shield; exact part number not visually confirmed and will not be (shield stays on, owner "
                   "decision 2026-11-11).",
    "parties": mfr("party-mediatek"),
    "classification": cls("semiconductor", "rf-transceiver", ("8542.39",)),
    "deviceType": "qfn",
    "quantity": {"value": 1},
    "properties": [prop("r18:resolution", "final: MT7975 family per log, exact marking not inspected (shield stays on)"),
                   evidence("serial boot log")],
}

shield = {
    "type": "device", "refId": "rf-shield",
    "name": "RF shield can (Wi-Fi section)",
    "description": "Soldered tin-plated shield covering Wi-Fi baseband, A-die and FEMs.",
    "classification": cls("mechanical", "emi-shield"),
    "deviceType": "smd", "quantity": {"value": 1},
    "properties": [evidence("photo")],
}

magnetics = {
    "type": "device", "refId": "eth-magnetics",
    "name": "LG48207SN", "group": "FPE",
    "description": "Gigabit Ethernet LAN transformer (dual-port, 48-pin), date code 2229J.",
    "parties": mfr("party-fpe"),
    "identifiers": mpn("party-fpe", "LG48207SN"),
    "classification": cls("passive", "lan-transformer", ("8504.31",)),
    "deviceType": "smd", "quantity": {"value": 2},
    "properties": [evidence("photo")],
}

rj45_wan = {
    "type": "device", "refId": "rj45-wan", "name": "RJ45 jack, WAN (yellow)",
    "classification": cls("connector", "rj45", ("8536.69",)), "deviceType": "pth", "quantity": {"value": 1},
    "properties": [prop("r18:switch-port", "4 (WAN)"), prop("r18:linux:ifname", "wan"), prop("r18:link", "1000 Mbps verified"),
                   evidence("photo", "serial boot log", "cable test 2026-11-09")],
}
rj45_lan = {
    "type": "device", "refId": "rj45-lan", "name": "RJ45 jack 1x3 ganged, LAN1-3",
    "classification": cls("connector", "rj45", ("8536.69",)), "deviceType": "pth", "quantity": {"value": 1},
    "properties": [prop("r18:switch-port", "LAN1=port 3, LAN2=port 2, LAN3=port 1"), prop("r18:link", "1000 Mbps verified on all"),
                   evidence("photo", "cable test 2026-11-09")],
}
dc_jack = {
    "type": "device", "refId": "dc-jack", "name": "DC barrel power jack",
    "description": "12 V DC input, 1 A, centre positive (rating label).",
    "classification": cls("connector", "dc-power-jack", ("8536.69",)), "deviceType": "pth", "quantity": {"value": 1},
    "properties": [prop("r18:rating", "12V 1A, centre positive"), evidence("photo", "rating label photo 2026-11-10")],
}
switch_btn = {
    "type": "device", "refId": "sw-rear", "name": "Rear buttons: WPS push button + RESET pinhole",
    "description": "Rear panel between the yellow INTERNET (WAN) jack and the DC jack (top-left corner of the rear when the "
                   "router stands facing the user). WPS = protruding round black button (label 'WPS') -> GPIO7; "
                   "RESET = recessed pinhole below it (label 'RESET') -> GPIO0. Both active-low with IRQ; two separate "
                   "board-edge switches behind the panel (one actuated via a white plastic plunger).",
    "classification": cls("electromechanical", "switch", ("8536.50",)), "deviceType": "smd", "quantity": {"value": 2},
    "properties": [prop("r18:gpio", "reset=GPIO0 (KEY_RESTART), wps=GPIO7 (KEY_WPS_BUTTON), active-low"),
                   evidence("photo", "stock module", "press test", "rear panel photo 2026-11-09")],
}
leds = {
    "type": "device", "refId": "leds-front", "name": "Front status LEDs (4 lenses)",
    "description": "Left to right: 1 power (bicolor white GPIO1 active-low / orange GPIO2 active-low), "
                   "2 internet (bicolor white GPIO6 active-low / orange GPIO4 active-high), "
                   "3 2.4 GHz Wi-Fi (white, MT7915 LED pin band 0), 4 5 GHz Wi-Fi (white, MT7915 LED pin band 1).",
    "classification": cls("optoelectronic", "led", ("8541.41",)), "deviceType": "smd", "quantity": {"value": 4},
    "properties": [evidence("stock module", "LED test with photos 2026-11-09")],
}
antennas = {
    "type": "device", "refId": "antennas", "name": "External dipole antennas with coax pigtails",
    "description": "4 non-detachable external antennas; pigtails to IPEX/U.FL-style pads labelled 2.4G (x2) and 5G (x2).",
    "classification": cls("rf", "antenna", ("8529.10",)), "deviceType": {"name": "cable-assembly"},
    "quantity": {"value": 4},
    "properties": [evidence("photo")],
}
uart_console = {
    "type": "device", "refId": "hdr-uart-console", "name": "Serial console header (unpopulated, 4-hole 2.54 mm)",
    "description": "SoC UART0 console, 115200 8N1, 3.3 V TTL. Pin 1 (square pad) = VCC (never connect). GND, TX and RX all "
                   "confirmed working (bootloader shell, XMODEM upload, Linux console).",
    "classification": cls("connector", "debug-header"), "deviceType": "pth", "quantity": {"value": 1},
    "properties": [prop("r18:pinout", "1=VCC (square, do not connect), 2=TX (router out), 3=RX (router in), 4=GND"),
                   prop("r18:wiring", "adapter GND->pin4, adapter RXD->pin2 (TX), adapter TXD->pin3 (RX), 3.3 V logic"),
                   evidence("photo", "serial capture", "header photo + owner wiring 2026-11-09")],
}
uart2_tp = {
    "type": "device", "refId": "tp-uart2", "name": "UART2 test points",
    "description": "UART2_TXD/RXD/RTS/CTS test pads; not the console.",
    "classification": cls("connector", "test-point"), "deviceType": "smd", "quantity": {"value": 8},
    "boardLocation": {"designators": ["TP416", "TP417", "TP418", "TP419", "TP420", "TP421", "TP422", "TP423"], "layer": "top"},
    "properties": [evidence("photo")],
}
hdr_unpop = {
    "type": "device", "refId": "hdr-unpopulated", "name": "Unpopulated pin headers (2x5 J1001 next to flash U15, 2x3)",
    "description": "J1001: 2x5, 2.54 mm, pins numbered 1-10 (pin 1 square), directly beside the SPI-NAND U15 and SoC; "
                   "likely EJTAG or SPI programming. 2x3 footprint also present. Functions unverified.",
    "classification": cls("connector", "debug-header"), "deviceType": "pth", "quantity": {"value": 2},
    "boardLocation": {"designators": ["J1001"], "layer": "top"},
    "properties": [evidence("photo", "photo 2026-11-09")],
}
pcb = {
    "type": "device", "refId": "pcb-main", "name": "R18 main PCB",
    "description": "Single main board; silkscreen 'EPMBDIRR18E3OXXX' and '100B18'.",
    "classification": cls("pcb", "rigid-pcb", ("8534.00",)), "quantity": {"value": 1},
    "properties": [prop("r18:silkscreen", "EPMBDIRR18E3OXXX; 100B18"), evidence("photo")],
}

components = [pcb, soc, dram, flash, wifi_die, wifi_adie, shield, magnetics,
              rj45_wan, rj45_lan, dc_jack, switch_btn, leds, antennas, uart_console, uart2_tp, hdr_unpop]

device = {
    "type": "device", "refId": "dlink-r18",
    "name": "EAGLE PRO AI AX1800 Smart Router", "group": "D-Link", "version": "R18 HW A1",
    "description": "Dual-band Wi-Fi 6 (AX1800) router: EcoNet EN7528HU SoC, 256 MiB DDR3, 128 MiB SPI-NAND, MediaTek MT79x5 "
                   "DBDC Wi-Fi over PCIe, 1x WAN + 3x LAN gigabit. Being ported to OpenWrt (econet/en7528).",
    "parties": parties,
    "identifiers": [
        {"party": "party-dlink", "identities": [
            {"scheme": "model-number", "value": "R18"},
            {"scheme": "fcc-id", "value": "KA2R18A1"},
            {"scheme": {"name": "ised-ic", "description": "Innovation, Science and Economic Development Canada certification number"},
             "value": "4216A-R18A1"},
            {"scheme": {"name": "dlink-model-number", "description": "M/N on the rating label"}, "value": "R18A1"},
            {"scheme": {"name": "dlink-part-number", "description": "P/N on the rating label (middle characters printed as dots)"},
             "value": "CR18LCA.......A1"},
            {"scheme": "mac-address", "value": "C8:78:7D:65:BF:80"},
            {"scheme": {"name": "label-mac", "description": "MAC printed on the rating label (= base+1, likely WAN)"},
             "value": "C8:78:7D:65:BF:81"},
            {"scheme": {"name": "dlink-romid", "description": "D-Link ROM id from ODM/CSID P-section"},
             "value": "DLK6E8112001"},
        ]},
    ],
    "classification": cls("networking-equipment", "wireless-router", ("8517.62",)),
    "quantity": {"value": 1},
    "certifications": [
        {"standard": "FCC Part 15", "identifier": "KA2R18A1", "jurisdiction": "US",
         "url": "https://fccid.io/KA2R18A1"},
        {"standard": "ISED RSS / CAN ICES-003(B)/NMB-003(B)", "identifier": "4216A-R18A1", "jurisdiction": "CA"},
        {"standard": "CE", "jurisdiction": "EU"},
    ],
    "tags": ["hbom", "router", "wifi6", "econet", "en7528", "openwrt-port"],
    "externalReferences": [
        {"type": "website", "url": "https://www.dlink.com/en/products/r18-eagle-pro-ai-ax1800-smart-router"},
        {"type": "certification-report", "url": "https://fccid.io/KA2R18A1"},
        {"type": "vcs", "url": "https://github.com/openwrt/openwrt/tree/main/target/linux/econet",
         "comment": "OpenWrt econet target (en7528 subtarget)"},
    ],
    "properties": [prop("r18:ram", "256 MiB DDR3"), prop("r18:flash", "128 MiB SPI-NAND"),
                   prop("r18:console", "115200 8N1 3.3V"),
                   prop("r18:mac-allocation", "base = ODM+0x82 = ...BF:80 (stock eth0/LAN); label MAC = base+1 = ...BF:81 "
                        "(default SSID R18-BF81; likely WAN); 2.4 GHz Wi-Fi = base+9 (stock MainSsid); 5 GHz not observed"),
                   prop("r18:power-input", "12 V DC, 1 A, centre positive (rating label)"),
                   prop("r18:country-of-origin", "TW (rating label: Made in Taiwan)"),
                   prop("r18:openwrt-status", "RAM-boot only (2026-11-09): Ethernet, switch, NAND (ro), LEDs, buttons, Wi-Fi both bands verified")],
}

bom = {
    "$schema": "http://cyclonedx.org/schema/2.0/",
    "specFormat": "CycloneDX",
    "specVersion": "2.0",
    "serialNumber": SERIAL,
    "version": 2,
    "metadata": {
        "timestamp": TS,
        "lifecycles": [{"phase": "operations"}, {"phase": "discovery"}],
        "tools": {"components": [{"type": "application", "name": "Claude Code", "group": "Anthropic"}]},
        "properties": [prop("r18:method", "visual inspection of board photos + stock serial boot log + stock firmware analysis + "
                                      "OpenWrt RAM-boot hardware tests; Wi-Fi section under shield not inspected and not planned to be "
                                      "(Wi-Fi baseband kept as component choice)")],
    },
    "inventories": [{"refId": "inv-dlink-r18", "subject": device, "components": components}],
    "dependencies": [
        {"ref": "dlink-r18", "dependsOn": [c["refId"] for c in components]},
        {"ref": "soc-en7528hu", "dependsOn": ["dram-nanya-ddr3", "u15-spinand", "eth-magnetics"]},
        {"ref": "u15-spinand", "dependsOn": ["fw-bootbase", "fw-stock-tclinux", "data-nand-config"]},
        {"ref": "choice-wifi-bb", "dependsOn": ["wifi-mt7975", "fw-mt7915-wm", "antennas"]},
        {"ref": "fw-stock-tclinux", "dependsOn": ["sw-linux-3.18.21", "sw-busybox-1.00", "fw-mt7915-wm"]},
    ],
    "compositions": [
        {"kind": "components", "aggregate": "incomplete", "targets": ["inv-dlink-r18"]},
    ],
}
print(json.dumps(bom, indent=2))
