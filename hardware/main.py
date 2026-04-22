import bluetooth
import time
import json
import gc
from machine import Pin, PWM, I2C
from ble_uart import BLEUART
import ds3231

time.sleep(1.5)

# ── PIN SETUP ─────────────────────────────────────────────────
i2c    = I2C(0, sda=Pin(21), scl=Pin(22), freq=400000)
rtc    = ds3231.DS3231(i2c)
buzzer = PWM(Pin(32), freq=1000, duty=0)
button = Pin(13, Pin.IN, Pin.PULL_UP)

LED_PINS = {
    "LED1": Pin(18, Pin.OUT),
    "LED2": Pin(19, Pin.OUT),
    "LED3": Pin(23, Pin.OUT),
    "LED4": Pin(25, Pin.OUT),
    "LED5": Pin(26, Pin.OUT),
    "LED6": Pin(27, Pin.OUT),
}
for led in LED_PINS.values():
    led.value(0)

# ── BLE SETUP ─────────────────────────────────────────────────
print("Booting BLE...")
ble  = bluetooth.BLE()
uart = BLEUART(ble, name="MedBox")

# ── FLASH FILE FUNCTIONS ───────────────────────────────────────
ALARM_FILE   = "alarms.json"
HISTORY_FILE = "history.json"   # ← NEW

def save_alarms():
    try:
        data = {k: list(v) for k, v in alarm_schedule.items()}
        with open(ALARM_FILE, "w") as f:
            json.dump(data, f)
        print("Alarms saved to flash:", data)
    except Exception as e:
        print("Save error:", e)

def load_alarms():
    try:
        with open(ALARM_FILE, "r") as f:
            data = json.load(f)
        loaded = {k: tuple(v) for k, v in data.items()}
        print("Alarms loaded from flash:", loaded)
        return loaded
    except OSError:
        print("No alarm file — starting fresh")
        return {}
    except Exception as e:
        print("Load error:", e)
        return {}

def clear_alarm_file():
    try:
        import os
        os.remove(ALARM_FILE)
        print("Alarm file deleted")
    except:
        pass

# ── NEW: OFFLINE HISTORY FUNCTIONS ────────────────────────────

def get_rtc_timestamp():
    """Returns current RTC time as ISO string: 2026-03-22T14:30:00"""
    try:
        dt = rtc.datetime()
        return "{:04d}-{:02d}-{:02d}T{:02d}:{:02d}:{:02d}".format(
            dt[0], dt[1], dt[2], dt[4], dt[5], dt[6])
    except:
        return "0000-00-00T00:00:00"

def append_history(led_key, status):
    """
    Appends one event to history.json on flash.
    Each entry: {"led": "LED1", "slot": 1, "status": "TAKEN", "ts": "2026-03-22T14:30:00"}
    File is a JSON array.
    """
    try:
        # Load existing history
        try:
            with open(HISTORY_FILE, "r") as f:
                history = json.load(f)
        except OSError:
            history = []

        # Parse slot number from led_key e.g. "LED1" → 1
        slot = int(led_key.replace("LED", "")) if led_key.startswith("LED") else 0

        # Build new entry
        entry = {
            "led"    : led_key,
            "slot"   : slot,
            "status" : status,
            "ts"     : get_rtc_timestamp()
        }

        history.append(entry)

        # Write back
        with open(HISTORY_FILE, "w") as f:
            json.dump(history, f)

        print("History saved:", entry)

    except Exception as e:
        print("append_history error:", e)

def load_history():
    """Loads all offline history entries from flash."""
    try:
        with open(HISTORY_FILE, "r") as f:
            return json.load(f)
    except OSError:
        return []
    except Exception as e:
        print("load_history error:", e)
        return []

def clear_history_file():
    """Deletes history.json from flash after Android confirms sync."""
    try:
        import os
        os.remove(HISTORY_FILE)
        print("history.json deleted from flash")
    except:
        pass

def send_history_to_android():
    """
    Sends all offline history entries to Android one by one.
    Format per line:
      LOG:LED1,1,TAKEN,2026-03-22T14:30:00\n
    Sends LOG:END\n when done.
    """
    history = load_history()
    if not history:
        uart.write(b"LOG:EMPTY\n")
        print("No offline history to sync")
        return

    print("Sending", len(history), "offline history entries...")
    for entry in history:
        line = "LOG:{},{},{},{}\n".format(
            entry.get("led",    "LED0"),
            entry.get("slot",   0),
            entry.get("status", "UNKNOWN"),
            entry.get("ts",     "0000-00-00T00:00:00")
        )
        uart.write(line.encode())
        time.sleep_ms(50)   # small gap between entries
        print("Sent:", line.strip())

    uart.write(b"LOG:END\n")
    print("All history sent to Android")

# ── STATE ─────────────────────────────────────────────────────
rx_stream            = ""
alarm_schedule       = load_alarms()
alarm_active         = False
alarm_led_key        = None
alarm_start_time     = 0
alarm_triggered_key  = -1

last_time_check      = 0
last_blink_time      = 0
last_gc_time         = 0
blink_state          = False

TIME_CHECK_INTERVAL  = 1000
BLINK_INTERVAL       = 300
MISSED_TIMEOUT       = 5 * 60 * 1000
GC_INTERVAL          = 10000

# ── PRINT BOOT INFO ───────────────────────────────────────────
try:
    dt = rtc.datetime()
    print("RTC time: {:04d}-{:02d}-{:02d} {:02d}:{:02d}:{:02d}".format(
        dt[0], dt[1], dt[2], dt[4], dt[5], dt[6]))
except Exception as e:
    print("RTC read error:", e)

print("Loaded alarms:", alarm_schedule)

# ── BLE MESSAGE PARSER ────────────────────────────────────────
def parse_message(msg):
    global alarm_schedule
    try:
        print("BLE RX Complete Command:", msg)

        if msg.startswith("TIME:"):
            parts = msg[5:].split(",")
            if len(parts) == 8:
                t = tuple(int(x) for x in parts)
                rtc.datetime(t)
                uart.write(b"ACK:TIME_SET\n")
                print(">>> RTC updated successfully:", t)
            else:
                uart.write(b"ERR:TIME_FORMAT\n")

        elif msg.startswith("ALARM:"):
            parts = msg[6:].split(",")
            if len(parts) == 3:
                led_key = parts[0].upper()
                hour    = int(parts[1])
                minute  = int(parts[2])
                if (led_key in LED_PINS and
                    0 <= hour <= 23 and
                    0 <= minute <= 59):
                    alarm_schedule[led_key] = (hour, minute)
                    save_alarms()
                    uart.write(
                        ("ACK:ALARM_SET:" + led_key + "\n").encode())
                    print(">>> Alarm set:", led_key,
                          "{:02d}:{:02d}".format(hour, minute))
                else:
                    uart.write(b"ERR:ALARM_INVALID\n")
            else:
                uart.write(b"ERR:ALARM_FORMAT\n")

        elif msg.startswith("CLEAR:ALL"):
            alarm_schedule.clear()
            clear_alarm_file()
            uart.write(b"ACK:CLEAR_ALL\n")
            print(">>> All alarms cleared")

        elif msg.startswith("CLEAR:"):
            led_key = msg[6:].upper()
            if led_key in alarm_schedule:
                del alarm_schedule[led_key]
                save_alarms()
                uart.write(("ACK:CLEAR:" + led_key + "\n").encode())
                print(">>> Cleared alarm:", led_key)
            else:
                uart.write(b"ERR:NOT_FOUND\n")

        elif msg.startswith("STATUS"):
            try:
                dt     = rtc.datetime()
                status = "TIME:{:02d}:{:02d}:{:02d}|".format(
                    dt[4], dt[5], dt[6])
            except:
                status = "TIME:??|"
            for key, val in alarm_schedule.items():
                status += "{}={:02d}:{:02d};".format(
                    key, val[0], val[1])
            status += "\n"
            uart.write(status.encode())
            print(">>> Status sent:", status.strip())

        elif msg.startswith("TEST:"):
            led_key = msg[5:].upper().strip()
            if led_key in LED_PINS:
                print(">>> Manual TEST trigger:", led_key)
                start_alarm(led_key)
                uart.write(("ACK:TEST:" + led_key + "\n").encode())
            else:
                uart.write(b"ERR:LED_NOT_FOUND\n")

        # ── NEW: Offline sync commands ─────────────────────
        elif msg.startswith("SYNC_LOGS"):
            # Android requesting all missed offline history
            print(">>> Android requesting offline sync...")
            send_history_to_android()

        elif msg.startswith("CLEAR_LOGS"):
            # Android confirmed it received all logs — safe to delete
            clear_history_file()
            uart.write(b"ACK:LOGS_CLEARED\n")
            print(">>> history.json cleared on Android confirmation")

        else:
            uart.write(b"ERR:UNKNOWN\n")
            print("Unknown command ignored:", msg)

    except Exception as e:
        print("Parse error:", msg, "->", e)
        uart.write(b"ERR:PARSE\n")

def on_ble_rx():
    pass

uart.irq_handler(on_ble_rx)

# ── ALARM CONTROL ─────────────────────────────────────────────
def start_alarm(led_key):
    global alarm_active, alarm_led_key, alarm_start_time
    alarm_active     = True
    alarm_led_key    = led_key
    alarm_start_time = time.ticks_ms()
    buzzer.freq(1000)
    buzzer.duty(512)
    print(">>> ALARM STARTED:", led_key)
    uart.write(("ALARM:" + led_key + "\n").encode())

def stop_alarm(reason="BUTTON"):
    global alarm_active, alarm_led_key, blink_state
    stopped_led  = alarm_led_key
    alarm_active = False
    blink_state  = False

    if alarm_led_key and alarm_led_key in LED_PINS:
        LED_PINS[alarm_led_key].value(0)
    alarm_led_key = None
    buzzer.duty(0)
    print(">>> ALARM STOPPED ({}) LED: {}".format(reason, stopped_led))

    if stopped_led:
        if reason == "BUTTON":
            # ── Try BLE first, save to flash as fallback ──
            if uart.is_connected():
                uart.write(("TAKEN:" + stopped_led + "\n").encode())
                print("Sent TAKEN via BLE:", stopped_led)
            else:
                append_history(stopped_led, "TAKEN")
                print("BLE offline — saved TAKEN to history.json")

        elif reason == "TIMEOUT":
            # ── Try BLE first, save to flash as fallback ──
            if uart.is_connected():
                uart.write(("MISSED:" + stopped_led + "\n").encode())
                print("Sent MISSED via BLE:", stopped_led)
            else:
                append_history(stopped_led, "MISSED")
                print("BLE offline — saved MISSED to history.json")

def beep(freq=1000, duration_ms=100):
    buzzer.freq(freq)
    buzzer.duty(512)
    time.sleep_ms(duration_ms)
    buzzer.duty(0)
    time.sleep_ms(50)

# ── MAIN LOOP ─────────────────────────────────────────────────
print("\n=== Smart Medicine Box Ready ===")
print("BLE broadcasting as MedBox")

beep(1000, 100)
time.sleep_ms(100)
beep(1500, 100)

while True:
    try:
        now_ms = time.ticks_ms()

        # ── 1. Process BLE messages (MTU Stream Buffer) ───────
        if uart.any():
            data = uart.read()
            if data:
                rx_stream += data.decode("utf-8", "ignore")

                for cmd in ["TIME:", "ALARM:", "CLEAR:", "STATUS",
                            "TEST:", "SYNC_LOGS", "CLEAR_LOGS"]:
                    rx_stream = rx_stream.replace(cmd, "\n" + cmd)

                rx_stream = rx_stream.replace("\n\n", "\n")

                while "\n" in rx_stream:
                    line, rx_stream = rx_stream.split("\n", 1)
                    line = line.strip()
                    if line:
                        parse_message(line)

        # ── 2. Check RTC every second ─────────────────────────
        if time.ticks_diff(now_ms, last_time_check) >= TIME_CHECK_INTERVAL:
            last_time_check = now_ms
            dt             = rtc.datetime()
            current_hour   = dt[4]
            current_minute = dt[5]
            current_second = dt[6]

            if current_second % 10 == 0:
                print("Time: {:02d}:{:02d}:{:02d} | Alarms: {}".format(
                    current_hour, current_minute,
                    current_second, alarm_schedule))

            if current_second == 0 and not alarm_active:
                for led_key, (a_hour, a_min) in alarm_schedule.items():
                    trigger_key = a_hour * 100 + a_min
                    if (a_hour == current_hour and
                        a_min  == current_minute and
                        alarm_triggered_key != trigger_key):
                        alarm_triggered_key = trigger_key
                        start_alarm(led_key)
                        break

        # ── 3. Blink LED while alarm active ──────────────────
        if alarm_active:
            if time.ticks_diff(now_ms, last_blink_time) >= BLINK_INTERVAL:
                last_blink_time = now_ms
                blink_state     = not blink_state
                if alarm_led_key and alarm_led_key in LED_PINS:
                    LED_PINS[alarm_led_key].value(
                        1 if blink_state else 0)

        # ── 4. Auto MISSED after 5 minutes ───────────────────
        if alarm_active:
            elapsed = time.ticks_diff(now_ms, alarm_start_time)
            if elapsed >= MISSED_TIMEOUT:
                stop_alarm(reason="TIMEOUT")

        # ── 5. Check button press (active LOW) ───────────────
        if alarm_active and button.value() == 0:
            time.sleep_ms(50)
            if button.value() == 0:
                stop_alarm(reason="BUTTON")
                while button.value() == 0:
                    time.sleep_ms(10)

        # ── 6. Garbage Collection ─────────────────────────────
        if time.ticks_diff(now_ms, last_gc_time) >= GC_INTERVAL:
            last_gc_time = now_ms
            gc.collect()

        # ── 7. Tiny sleep ─────────────────────────────────────
        time.sleep_ms(10)

    except Exception as e:
        print("CRITICAL LOOP ERROR:", e)
        time.sleep_ms(1000)
