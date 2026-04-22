# [cite_start]Smart Medicine Box [cite: 1]

> [cite_start]A Hardware-Software Assistive System for Medication Adherence [cite: 2]

[cite_start]Developed by Parag Bansal & Romit Kumar Sethi at ABV-IIITM Gwalior[cite: 3, 96, 97, 101].

---

## 🚀 Project Overview

[cite_start]Medication non-adherence affects approximately 50% of patients with chronic diseases, resulting in an estimated 125,000 preventable deaths annually[cite: 7, 117]. [cite_start]Traditional passive pillboxes provide no active alerts or feedback[cite: 9, 119]. [cite_start]Standard smartphone alarms lack a physical connection to indicate which specific medication compartment must be opened at the point of intake[cite: 10, 106, 120]. 

[cite_start]The Smart Medicine Box addresses this gap by utilizing a 6-compartment hardware enclosure linked via Bluetooth Low Energy (BLE) to a custom Android scheduling application[cite: 12, 107, 121].

### Key Features
* [cite_start]**Real-Time Alerts:** Delivers visual LED and audio buzzer notifications at the precise scheduled times[cite: 15, 16].
* [cite_start]**Offline Operation:** Utilizes a DS3231 RTC to ensure accurate timekeeping entirely independent of a continuous smartphone connection[cite: 17, 18, 109].
* [cite_start]**Adherence Tracking:** Every medication dose event is logged and synchronized to the Android app via BLE[cite: 19, 20].
* [cite_start]**Affordability:** Built with a total Bill of Materials (BOM) cost under ₹1,500, making it deployable at scale[cite: 21, 22].

---

## 🛠️ Tech Stack

### Hardware Components
* [cite_start]**Microcontroller:** ESP32 DevKit V1 programmed in MicroPython using the Thonny IDE[cite: 25, 26, 126, 128].
* [cite_start]**RTC Module:** DS3231 precision real-time clock operating over I2C[cite: 27, 28, 126].
* [cite_start]**Alert Outputs:** 6x 5mm LEDs (one per compartment) and an active buzzer for 1 kHz PWM audio alerts[cite: 29, 30, 53, 126].
* [cite_start]**Enclosure & Power:** Custom 6-compartment box with a tactile push button for acknowledgement, powered by a 3.7V 18650 Li-ion battery, a TP4056 charging module, and an MT3608 DC-DC step-up booster[cite: 31, 32, 126].

### Software Stack
* [cite_start]**Android Application:** Native Kotlin application featuring dynamic Dark Mode and a 6-grid dashboard[cite: 34, 35, 111, 150, 151].
* [cite_start]**Local Database:** Room Persistence Library (SQLite) manages medication logs and ensures zero data loss during schema migrations (v2→v3)[cite: 35, 41, 156, 157].
* [cite_start]**Connectivity:** Bluetooth Low Energy (BLE) UART utilizing the Nordic NUS Profile for bidirectional data transfer[cite: 36, 37, 159].

---

## ⚙️ System Architecture & Working Methodology

[cite_start]The system functions in a three-stage bidirectional flow where the Android app acts as the central scheduler and the ESP32 acts as the autonomous executor[cite: 43, 48, 50, 130].

1. [cite_start]**Scheduling:** The user configures medication times in the app, which transmits structured BLE commands formatted as `TIME:YYYY,MM,DD,WD,HH,MM,SS` and `ALARM:LEDx,HH,MM`[cite: 51, 132, 133].
2. [cite_start]**Execution:** The ESP32 polls the DS3231 RTC every second[cite: 45, 135]. [cite_start]Alarms are persisted in an `alarms.json` file on the ESP32's flash memory, allowing them to survive power cycles without requiring re-syncing[cite: 39, 53, 55, 135].
3. [cite_start]**Notification:** Upon an RTC time match, the ESP32 firmware triggers simultaneous compartment LED illumination and a 1 kHz PWM buzzer tone[cite: 53, 136].
4. [cite_start]**Acknowledgement:** The user presses the tactile button (GPIO 13, debounced 50 ms) to silence the alarm[cite: 53, 138]. [cite_start]This deliberate, physical intake gesture improves true adherence compared to passive smartphone dismissal[cite: 57].
5. [cite_start]**Offline Sync & Inventory:** If the smartphone is disconnected, the `TAKEN` event is written to `history.json` on the flash memory[cite: 53, 140]. [cite_start]Upon BLE reconnection, an Android SyncManager automatically pulls the history file to reconcile all offline logs[cite: 64, 65, 142]. [cite_start]Additionally, a Smart Inventory engine automatically deducts stock upon acknowledgement, triggers low-stock warnings at ≤ 3 units, and prevents negative stock values[cite: 66, 67, 68, 69, 145, 147].

---

## 🔮 Future Scope

* [cite_start]**Deep Sleep Mode:** Reduce the ESP32 idle current from 140mA to <5mA using `machine.lightsleep()` to optimize battery life for long-term deployment[cite: 82, 83, 170, 171].
* [cite_start]**Caregiver Notifications:** Automatically send daily adherence summaries via SMS or WhatsApp to family members upon detecting missed events[cite: 85, 86, 173].
* [cite_start]**IR Compartment Sensing:** Implement compartment-specific IR sensors to verify that medication is retrieved from the correct slot, preventing accidental mis-dosing[cite: 88, 89, 175].
* [cite_start]**Real-Time Battery Monitoring:** Display a live battery percentage on the app dashboard using a voltage divider on an ADC GPIO[cite: 172].
* [cite_start]**Multiple Daily Doses:** Expand the database model to support morning, afternoon, and evening dose schedules per individual compartment[cite: 174].
