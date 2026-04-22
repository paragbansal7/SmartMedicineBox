# Smart Medicine Box

> A Hardware-Software Assistive System for Medication Adherence

Developed by Parag Bansal & Romit Kumar Sethi at ABV-IIITM Gwalior.

---

## 🚀 Project Overview

Medication non-adherence affects approximately 50% of patients with chronic diseases, resulting in an estimated 125,000 preventable deaths annually. Traditional passive pillboxes provide no active alerts or feedback. Standard smartphone alarms lack a physical connection to indicate which specific medication compartment must be opened at the point of intake. 

The Smart Medicine Box addresses this gap by utilizing a 6-compartment hardware enclosure linked via Bluetooth Low Energy (BLE) to a custom Android scheduling application.

### Key Features
* **Real-Time Alerts:** Delivers visual LED and audio buzzer notifications at the precise scheduled times.
* **Offline Operation:** Utilizes a DS3231 RTC to ensure accurate timekeeping entirely independent of a continuous smartphone connection.
* **Adherence Tracking:** Every medication dose event is logged and synchronized to the Android app via BLE.
* **Affordability:** Built with a total Bill of Materials (BOM) cost under ₹1,500, making it deployable at scale.

---

## 🛠️ Tech Stack

### Hardware Components
* **Microcontroller:** ESP32 DevKit V1 programmed in MicroPython using the Thonny IDE.
* **RTC Module:** DS3231 precision real-time clock operating over I2C.
* **Alert Outputs:** 6x 5mm LEDs (one per compartment) and an active buzzer for 1 kHz PWM audio alerts.
* **Enclosure & Power:** Custom 6-compartment box with a tactile push button for acknowledgement, powered by a 3.7V 18650 Li-ion battery, a TP4056 charging module, and an MT3608 DC-DC step-up booster.

### Software Stack
* **Android Application:** Native Kotlin application featuring dynamic Dark Mode and a 6-grid dashboard.
* **Local Database:** Room Persistence Library (SQLite) manages medication logs and ensures zero data loss during schema migrations (v2→v3).
* **Connectivity:** Bluetooth Low Energy (BLE) UART utilizing the Nordic NUS Profile for bidirectional data transfer.

---

## ⚙️ System Architecture & Working Methodology

The system functions in a three-stage bidirectional flow where the Android app acts as the central scheduler and the ESP32 acts as the autonomous executor.

1. **Scheduling:** The user configures medication times in the app, which transmits structured BLE commands formatted as `TIME:YYYY,MM,DD,WD,HH,MM,SS` and `ALARM:LEDx,HH,MM`.
2. **Execution:** The ESP32 polls the DS3231 RTC every second. Alarms are persisted in an `alarms.json` file on the ESP32's flash memory, allowing them to survive power cycles without requiring re-syncing.
3. **Notification:** Upon an RTC time match, the ESP32 firmware triggers simultaneous compartment LED illumination and a 1 kHz PWM buzzer tone.
4. **Acknowledgement:** The user presses the tactile button (GPIO 13, debounced 50 ms) to silence the alarm. This deliberate, physical intake gesture improves true adherence compared to passive smartphone dismissal.
5. **Offline Sync & Inventory:** If the smartphone is disconnected, the `TAKEN` event is written to `history.json` on the flash memory. Upon BLE reconnection, an Android SyncManager automatically pulls the history file to reconcile all offline logs. Additionally, a Smart Inventory engine automatically deducts stock upon acknowledgement, triggers low-stock warnings at ≤ 3 units, and prevents negative stock values.

---

## 🔮 Future Scope

* **Deep Sleep Mode:** Reduce the ESP32 idle current from 140mA to <5mA using `machine.lightsleep()` to optimize battery life for long-term deployment.
* **Caregiver Notifications:** Automatically send daily adherence summaries via SMS or WhatsApp to family members upon detecting missed events.
* **IR Compartment Sensing:** Implement compartment-specific IR sensors to verify that medication is retrieved from the correct slot, preventing accidental mis-dosing.
* **Real-Time Battery Monitoring:** Display a live battery percentage on the app dashboard using a voltage divider on an ADC GPIO.
* **Multiple Daily Doses:** Expand the database model to support morning, afternoon, and evening dose schedules per individual compartment.
