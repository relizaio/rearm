# D-Link R18 HBOM — how it was generated

Files in this directory:

| File | What it is |
|---|---|
| `gen_hbom.py` | Hand-curated generator. Every value in the HBOM is a literal in this script; nothing is scraped automatically. |
| `d-link-r18.hbom.cdx.json` | Output, **version 2** (2026-11-09). `python3 gen_hbom.py > d-link-r18.hbom.cdx.json` |
| `gen_hbom.py.v1`, `d-link-r18.hbom.cdx.v1.json` | Version 1 (2026-11-08), kept for diffing. |
| `HBOM-PROVENANCE.md` | This file. |
| `photos/` | The published photos: board, rear buttons, UART header and SoC/DRAM/flash area (2026-11-09), and the U15 flash close-up (2026-11-11). |

Format: CycloneDX **2.0-dev** (HBOM/"inventories" model). Validated with Python `jsonschema` 4.10.3
against `schema/2.0/cyclonedx-2.0-bundled.schema.json` from `github.com/CycloneDX/specification` at commit
`ea8c360` (2026-11-02): v1 and v2 both **0 errors**.

Each component carries an `r18:evidence` property naming its sources. This document expands those tags.

Only the files listed above are published. Paths under `~/openwrt/` and `HANDOFF.md` are the owner's private working files (logs, the NAND backup, which contains ODM secrets, and session notes); they are cited as evidence but **not published**.

---

## 1. Evidence sources

| Tag | Source | Date | Where it lives |
|---|---|---|---|
| **P** | Board photos taken by the owner, case opened (top side of PCB) | 2026-11-08 | Uploaded to the earlier Claude session; **not saved on disk**. The facts read from them are only preserved as text in `gen_hbom.py`. |
| **L** | Stock firmware serial boot log, captured over UART at 115200 8N1 | 2026-11-08 | `~/openwrt/r18-boot-clean.txt` (1544 lines), raw: `r18-boot-full.raw` (not published) |
| **A** | Offline analysis of the stock firmware from the verified NAND backup | 2026-11-08/09 | `~/openwrt/backup/` (contains ODM secrets), extracted rootfs in `~/openwrt/wifi/rootfs-slave/`, kernel in `~/openwrt/stockkernel/` (not published) |
| **P3** | Photo of the bottom rating label by the owner | 2026-11-10 | **Not stored** (shows default Wi-Fi/admin passwords and S/N); facts transcribed here |
| **T** | Hardware tests while running OpenWrt from RAM (no flash writes) | 2026-11-09 | Serial logs `~/openwrt/logs/kexec*.out`, `HANDOFF.md` §11 (not published) |
| **U** | Owner's direct observation during tests (incl. LED photos uploaded in the 2026-11-09 session) | 2026-11-09 | LED photos uploaded to the 2026-11-09 session; **not published** (not in `photos/`) |
| **P2** | Close-up photos by the owner: SoC/DRAM/flash area; rear panel with buttons | 2026-11-09 | `photos/2026-11-09-soc-dram-flash.png`, `photos/2026-11-09-rear-buttons.jpg`, `photos/2026-11-09-uart-header.jpg` |
| **P4** | Close-up of SPI-NAND U15 (package, marking, white dot) by the owner | 2026-11-11 | `photos/2026-11-11-u15-flash-closeup.png` |
| **X** | External public reference | — | URLs in the HBOM |
| **I** | Inference / general knowledge, not measured on this unit | — | Marked below; treat as unverified |

---

## 2. Device-level fields

| Field | Value | Source | How |
|---|---|---|---|
| Model / marketing name | R18, "EAGLE PRO AI AX1800" | P, L, X | Case/label in photos; `model name: (R18)` (L:37); D-Link product page |
| FCC ID | KA2R18A1 | P, X | Label photo; fccid.io |
| ROM id | DLK6E8112001 | L | `romid: (DLK6E8112001)` (L:35), read by bootloader from ODM CSID |
| Base MAC | C8:78:7D:65:BF:80 | L, A, T | `beid mac:` (L:40); located as TLV value at **ODM partition + 0x82** by byte search in `backup/parts/mtd-ODM.bin` (private NAND backup, not published); OpenWrt eth0 got exactly this MAC via nvmem (T) |
| MAC allocation | eth = base, 2.4 GHz = base+9, 5 GHz unknown | L, T | `now bb MainSsid mac c8:78:7d:65:bf:89` (L:1048). 5 GHz = base+10 is only our DTS choice (**I**) |
| HW revision | **A1** | P3 | M/N `R18A1`, P/N `CR18LCA.......A1` (dots printed on label), FCC ID KA2R18A1, IC 4216A-R18A1, CAN ICES-003(B)/NMB-003(B), CE, "Made in Taiwan", input 12 V ⎓ 1 A centre-positive |
| Label MAC | C8:78:7D:65:BF:81 = base+1 | P3, L | Label `MAC:C8787D65BF81`; default SSID `R18-BF81` and stock backhaul SSID `R18-backhaul-BF81` (L) use the same suffix ⇒ likely WAN MAC. Deliberately NOT recorded: label Wi-Fi password, device password, serial number |
| HS codes, party addresses/URLs | — | **I** | General knowledge, not from the device |
| OpenWrt status | RAM-boot only; Ethernet, switch, NAND (ro), LEDs, buttons, Wi-Fi verified | T | See §4 |

---

## 3. Components

### PCB
| Field | Source | How |
|---|---|---|
| Silkscreen `EPMBDIRR18E3OXXX`, `100B18` | P | Read from photo (`100B18` also visible in the 2026-11-09 LED photos, U) |

### SoC — EcoNet EN7528HU
| Field | Source | How |
|---|---|---|
| Marking `ECONET / EN7528HU / 2144-AFAL / ACMSLF8H`, date code 2144 | P | Read from package in photo (also visible in 2026-11-09 photos, U) |
| MIPS 1004Kc, PRId 0x0001992f | L, T | `CPU0 revision is: 0001992f (MIPS 1004Kc)` (L:80); same under OpenWrt |
| 2 cores × 2 VPE = 4 threads | L, T | `Detected 3 available secondary CPU(s)` (L:92); OpenWrt `VPE topology {2,2} total 4` |
| 900 MHz | L | `CPU frequency 900.00 MHz` (L:116), `ddr-900` (L:8) |
| 32K I$/D$, 256K L2 | L, T | L:93–95; identical lines in OpenWrt boot |
| Integrated GbE switch + 4 PHYs | L, T | Stock `switch_port_map` (L:404–409); OpenWrt `mt7530-mmio` + `EcoNet EN7528 PHY` on MDIO 9–12 |
| PCIe RC0 = Gen1-only PHY, RC1 = Gen2 | A, T | **v2 correction.** v1 said "2× Gen2 x1" (**I**). Mainline DTS binds `econet,en7528-pcie-gen1` PHY to RC0; OpenWrt log: RC0 stays 2.5 GT/s, RC1 retrains to 5.0 GT/s |
| "RC0 must not be retrained" | T | Experiments 2026-11-09: MMIO through RC0 hung the SoC; with `econet,no-retrain` (test patch) `devmem 0x20000000` returned `0xF0030048`. Stock only retrains RC1 (`mt7512_pcie_rc1_retrain`, L and disassembly of stock kernel, A) |
| GPIO 0–41, IRQ capable | T | Upstream DTS `gpio-ranges`; buttons on GPIO0/7 delivered IRQs |
| "econet,en751221 (stock)" compatible | L | Stock kernel identification |

### DRAM — Nanya NT5CC128M16JR-EK (U1001), 256 MiB
| Field | Source | How |
|---|---|---|
| Marking `NANYA 2205 / NT5CC128M16JR-EK`, date code 2205 | P2 | Read from package in close-up (v2.1) |
| Designator U1001 | P2 | Silkscreen next to the package |
| 96-ball FBGA | P2 | Silkscreen ball-grid outline 1–9 × A–T (= 9 × 16 positions minus depopulated centre, standard x16 DDR3 96-ball) |
| DDR3L (1.35 V), 2 Gbit 128M×16, speed grade "-EK" (DDR3-1866 class) | **I** | Decoded from Nanya part-number scheme (`NT5CC` = DDR3L, `128M16` = 128M×16, `-EK` speed bin); not checked against the datasheet |
| DDR3, 256 MB, run at DDR-900 | L | `DDR3 init`, `DRAM size=256MB`, `ddr-900` (L:6–8) |
| 256 MiB usable | T | OpenWrt with `memory reg = <0 0x10000000>` runs stably; with the generic 64 MiB DTS it OOM'd |

### SPI-NAND — Macronix MX35LF1GE4AB-Z4I (U15)
| Field | Source | How |
|---|---|---|
| Marking `MXIC X221410 / MX35LF1GE4AB-Z4I / 2S609279`, designator U15 | P, P4, U | Read from photo; line 1 corrected 2026-11-11 |
| JEDEC c2 12, 128 MiB, 2048+64 pages, 128 KiB blocks | L, T | `spi_nand_probe: mfr_id=0xc2, dev_id=0x12` (L:13); OpenWrt `spi-nand: Macronix ... 128 MiB, block size 128 KiB, page size 2048, OOB 64` |
| BMT pool 81 blocks | L | `bmt pool size: 81` (L:16) |
| Package WSON-8 8×6 mm | P4, X | **Resolved 2026-11-11.** Close-up shows no lead shoulders/bends: the shiny "leads" are solder-wetted PCB pads extending past a no-lead body (that is what looked gull-wing in P2). 4 pads per side; body ≈ 8 × 6 mm from the pad-pitch ratio (1.27 mm). Matches Macronix ordering code `-Z4I` = 8-WSON 8×6 mm |
| White dot on the package | P4, **I** | Ink dot in the pin-1 corner (beside the MXIC logo; the text is upside-down in the photo, so pin 1 is bottom-right in it). Purpose not documented; commonly a programming/QC mark |
| Marking line 1 | P4, U | `X221410`, confirmed by the owner on the part (2026-11-11). Earlier versions had the misread `X22J410` |
| Full dump, sha256 `0f136bde…0fd367`, 0 bad blocks | T | `nanddump` of `/dev/mtd0ro` twice from RAM-booted OpenWrt, compared with on-device `sha256sum` (2026-11-08) |

### Firmware / data in NAND
| Field | Source | How |
|---|---|---|
| Bootloader "free bootbase 1.1", built 26 Nov 2022 | L | `EN7528 at Sat 26 Nov 2022 ... version 1.1 free bootbase` (L:12) |
| Bootloader writes flash each boot, dual-image selection via benv | L | `save_section` messages; `sw_curboot_id: (1)` (L:53) and `sw_commit/sw_active/sw_tryactive` |
| Partition map | L | Kernel MTD table (L:239–258) and cmdline parts (L:228); unnamed gap 0x6980000–0x6dc0000 inferred from the gap in that table |
| Stock OS: Linux 3.18.21, BusyBox 1.00, built 2024-04-19 | L | L:74, L:326 |
| tclinux = 1.00.18, tclinux_slave = 1.10.01 (active) | L, A | bldr `imginfo`; HDR2 headers in backup |
| ODM holds per-unit secrets | A | Inspection of `mtd-ODM.bin` (base64 key material after the MAC record) |

### Wi-Fi — MT7915-family DBDC (component-choice MT7905DAN XOR MT7915DAN)
| Field | Source | How |
|---|---|---|
| PCI IDs 14c3:7915 (+ hif2 14c3:7916) | L, T | `mt7916_hif_init`, `device_id=0x7915` (L:269, L:278); OpenWrt `lspci`-equivalent from sysfs |
| Topology: main on RC1 (`0001:01:00.0`), hif2 on RC0 (`0000:01:00.0`) | T | `/sys/bus/pci/devices`; v1 did not know which RC carried which |
| HW_REV 0x94, HW/SW 0x8a108a10 | T | mt76 debug print `rev 79150094` (instrumented probe) and `HW/SW Version: 0x8a108a10` |
| 4 RF chains, 2+2 DBDC | T | `iw phy`: phy0 TX/RX 0x3, phy1 configured 0xc |
| MT7905DAN vs MT7915DAN | **I** | AX1800 class suggests MT7905DAN; nothing in software distinguishes them. **Final as component choice (XOR)**: the owner decided on 2026-11-11 not to remove the RF shield, so this will not be resolved |
| MT7975 A-die | L, **I** | `[a-die version:2]` (L:1046); part number inferred. Not visually confirmed and will not be (shield stays on); `r18:todo` replaced by `r18:resolution` |
| Stock FW `MT7915_MP_7_4_2045-20220915163802` | L | L:967 |
| Calibration: none per-unit | L, A, T | Stock reads `reservearea` offset 0 (L:594) → all zeros in backup (A) → falls back to rootfs file (L:595, L:1040). That file (`RT30xxEEPROM.bin`, 3584 B, extracted with `unsquashfs` from `tclinux_slave`) has chip id 0x7915 and MediaTek's generic MAC `00:0c:43:26:46:44` (A). mt76 without it: `eeprom load fail, use default bin` ⇒ efuse empty (T) |
| Works under OpenWrt | T, U | Scan 2.4/5 GHz; AP test with owner's phone: 2.4 GHz HE 2SS 271 Mbit/s PHY, 5 GHz HE80 681 Mbit/s PHY, speedtests 100 / 209 down (ISP-bound) |

### RF shield, antennas
| Field | Source | How |
|---|---|---|
| Shield over Wi-Fi section | P | Photo (also in 2026-11-09 photos) |
| 4 external antennas, pads labelled 2.4G ×2, 5G ×2 | P, U | Photo; silkscreen `2.4G`/`5G`, `CON1901–1904` visible in 2026-11-09 photos |

### Ethernet
| Field | Source | How |
|---|---|---|
| Magnetics FPE LG48207SN ×2, date code 2229J | P | Read from photo |
| RJ45: yellow WAN + 3× ganged LAN | P | Photo |
| WAN = switch port 4 | L, T | Stock `switch_port_map` (L:404–409) + cable test |
| LAN1 = port 3, LAN2 = port 2, LAN3 = port 1, all 1000 Mbps | T, U | Owner moved one cable through every socket; kernel `lanX: Link is Up - 1Gbps/Full` per move (2026-11-09). An earlier 100 Mbps link on LAN1 disappeared after re-seating |

### Buttons, LEDs
| Field | Source | How |
|---|---|---|
| Rear push switch next to DC jack | P | Photo |
| reset = GPIO0, WPS = GPIO7, active-low | A, T, U | GPIO table disassembled from stock `amit_gpio_led_plat_dev.ko` (`gpio_keys_button` init: code 0x211/gpio 7, code 408/gpio 0); verified by press test (hotplug log `reset pressed/released`, `wps pressed/released`) |
| WPS = protruding round black button, RESET = pinhole below it; rear panel between INTERNET jack and DC jack | P2, U | Rear-panel photo with case labels "WPS" / "RESET"; owner: top-left of the rear when the router faces the user. Combined with the press test this maps WPS→GPIO7, RESET→GPIO0. Component quantity changed 1 → 2 |
| LEDs: 4 lenses; 1 power white GPIO1 / orange GPIO2, 2 internet white GPIO6 / orange GPIO4 (active-high), 3 = 2.4 GHz, 4 = 5 GHz on MT7915 LED pins | A, T, U | GPIO/polarity table from `ecnt_leds_gpio_data` in the same stock module; then set each LED from Linux (`/sys/class/leds/*/brightness`) while the owner reported/photographed which lens lit (3 photos 2026-11-09) |

### Debug / test points
| Field | Source | How |
|---|---|---|
| 4-hole console header, square pad = VCC | P, T | Photo; used for all work. GND/TX/RX confirmed working (bldr shell, XMODEM, Linux console) |
| Pin order: 1 VCC (square) → 2 TX → 3 RX → 4 GND | P2, U | Owner, with header photo (wires attached, normal orientation, top to bottom). TX/RX named from the router side; consistent with the working wiring adapter RXD→router TX |
| UART2 test pads TP416–TP423 | P | Silkscreen in photo |
| Unpopulated 2×5 and 2×3 headers ("likely JTAG/SPI") | P, P2, **I** | Footprints in photo; 2×5 is **J1001**, pins 1–10 numbered, pin 1 square, directly beside flash U15 (P2). Function guessed |
| DC barrel jack | P | Photo |

---

## 4. How the "T" tests were done (2026-11-09)

All on OpenWrt main @ `7edc67adadb1` (kernel 6.18.60), booted from RAM via bootloader `xmdm` + `jump`, then
`kexec` into the R18 dev image. Flash was mounted read-only throughout; nothing was written.

| Test | Method |
|---|---|
| CPU/RAM/flash identity | OpenWrt boot log on serial |
| PCIe topology / IDs | `/sys/bus/pci/devices/*/{vendor,device,resource,config}` |
| PCIe RC0 hang + fix | Instrumented mt76 probe (`pr_emerg` per step), `devmem` reads of BAR space and of the AHB window registers `0x1fb81438`/`0x1fb83438`; variants: window size, probe order, driver_override, no-retrain. Power cycles via Kasa plug (`power.sh`, a private script, not published) |
| Wi-Fi chains / rev | `iw phy`, mt76 dmesg |
| Wi-Fi function | `iw dev scan`; hostapd AP with the owner's phone; `iw station dump` for PHY rate/RSSI |
| LEDs | Each LED forced on/off via sysfs, owner reported position/colour with photos |
| Buttons | `/etc/rc.button/{reset,wps}` replaced by logging scripts; owner pressed each; `/proc/interrupts`, `/sys/kernel/debug/gpio` |
| Ports | Owner moved one cable through all sockets; `dmesg` link events + `/sys/class/net/*/speed` |

---

## 5. Changes v1 → v2

- PCIe description corrected (RC0 Gen1-only, RC1 Gen2) and retrain finding added.
- Wi-Fi: topology, HW_REV, HW/SW version, chain count, calibration findings.
- New `leds-front` component.
- Buttons: GPIO numbers and polarity (physical mapping still open).
- RJ45: verified port map and link speed.
- ODM MAC offset, benv flag names, unnamed NAND gap, backup facts, "ODM contains secrets" note.
- Console header: RX confirmed.
- Device: MAC allocation and OpenWrt status properties; metadata method/timestamp; BOM `version` 1 → 2.

## 5b. Changes v2 → v2.1 (same BOM `version` 2, regenerated)

- DRAM: part number NT5CC128M16JR-EK, date code 2205, designator U1001, FBGA-96 (from P2).
- Unpopulated 2×5 header identified as J1001 next to U15.
- Buttons: WPS = round button (GPIO7), RESET = pinhole (GPIO0), quantity 2 (rear-panel photo).
- Console header pinout: 1 VCC, 2 TX, 3 RX, 4 GND.
- Photos now kept in `photos/` (the 2026-11-08 originals are still missing).

## 5c. Changes v2.1 → v2.2

- Rating label (P3): HW A1, M/N, P/N, IC ID, CA/EU certifications, power rating, origin, label MAC (base+1).
- Secrets on the label (default passwords) and the S/N intentionally excluded.

## 5d. Changes v2.2 → v2.3

- U15 package confirmed WSON-8 8×6 mm from close-up P4 (gull-wing doubt resolved); pin-1 white dot recorded; marking line 1 corrected X22J410 → X221410 (owner-confirmed).
- Wi-Fi: RF shield will not be opened. Baseband kept as component choice MT7905DAN XOR MT7915DAN with `r18:resolution` = final; A-die `r18:todo` replaced by `r18:resolution`; metadata method note updated.

## 6. Still open

1. ~~MT7905DAN vs MT7915DAN and the MT7975 A-die~~ closed as won't-resolve in v2.3: shield stays on (owner decision 2026-11-11); baseband stays a component choice (XOR), A-die stays inferred from the log.
2. ~~Which physical button is which~~ done in v2.1 (WPS = round button → GPIO7, RESET = pinhole → GPIO0).
3. ~~Nanya DRAM part number~~ done in v2.1 (NT5CC128M16JR-EK). Datasheet check of DDR3L/speed decode still pending.
4. ~~HW revision~~ done in v2.2 (A1, from the rating label).
5. ~~Console header pin order~~ done in v2.1 (VCC, TX, RX, GND).
6. Function of the unpopulated headers.
7. 5 GHz MAC offset used by stock firmware.
