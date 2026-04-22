import bluetooth
import struct
import time
from micropython import const

_IRQ_CENTRAL_CONNECT    = const(1)
_IRQ_CENTRAL_DISCONNECT = const(2)
_IRQ_GATTS_WRITE        = const(3)

_FLAG_READ          = const(0x0002)
_FLAG_NOTIFY        = const(0x0010)
_FLAG_WRITE         = const(0x0008)
_FLAG_WRITE_NO_RESP = const(0x0004)

_UART_UUID = bluetooth.UUID("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
_TX_UUID   = bluetooth.UUID("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
_RX_UUID   = bluetooth.UUID("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

_TX_CHAR      = (_TX_UUID, _FLAG_READ | _FLAG_NOTIFY,)
_RX_CHAR      = (_RX_UUID, _FLAG_WRITE | _FLAG_WRITE_NO_RESP,)
_UART_SERVICE = (_UART_UUID, (_TX_CHAR, _RX_CHAR,),)


class BLEUART:
    def __init__(self, ble, name="MedBox", rxbuf=256):
        self._ble         = ble
        self._name        = name
        self._connections = set()
        self._rx_buffer   = bytearray()
        self._handler     = None

        print("Resetting BLE...")
        try:
            self._ble.active(False)
        except:
            pass
        time.sleep(2)

        print("Activating BLE...")
        self._ble.active(True)
        time.sleep(2)

        self._ble.irq(self._irq)

        print("Registering GATT services...")
        ((self._tx_handle, self._rx_handle),) = \
            self._ble.gatts_register_services((_UART_SERVICE,))
        
        # True appended for buffer overflow protection
        self._ble.gatts_set_buffer(self._rx_handle, rxbuf, True)

        self._payload = self._advertising_payload(name=name)

        print("Starting BLE advertising...")
        self._advertise()

    def irq_handler(self, handler):
        self._handler = handler

    def _irq(self, event, data):
        if event == _IRQ_CENTRAL_CONNECT:
            conn_handle, _, _ = data
            self._connections.add(conn_handle)
            print("BLE Connected! Handle:", conn_handle)

        elif event == _IRQ_CENTRAL_DISCONNECT:
            conn_handle, _, _ = data
            self._connections.discard(conn_handle)
            print("BLE Disconnected — restarting advertising...")
            time.sleep_ms(500)
            self._advertise()

        elif event == _IRQ_GATTS_WRITE:
            conn_handle, attr_handle = data
            if attr_handle == self._rx_handle:
                incoming = self._ble.gatts_read(self._rx_handle)
                if incoming:
                    self._rx_buffer.extend(incoming) 
                    if self._handler:
                        self._handler()

    def any(self):
        return len(self._rx_buffer)

    def read(self, sz=None):
        if not self.any():
            return None
        if sz is None:
            data = self._rx_buffer
            self._rx_buffer = bytearray()
            return data
        data = self._rx_buffer[:sz]
        self._rx_buffer = self._rx_buffer[sz:]
        return data

    def write(self, data):
        for conn_handle in self._connections:
            try:
                self._ble.gatts_notify(
                    conn_handle, self._tx_handle, data)
            except Exception as e:
                print("BLE TX error:", e)

    def close(self):
        for conn_handle in self._connections:
            try:
                self._ble.gap_disconnect(conn_handle)
            except:
                pass
        self._connections.clear()

    def is_connected(self):
        return len(self._connections) > 0

    def _advertise(self, interval_us=500000):
        for attempt in range(5):
            try:
                self._ble.gap_advertise(
                    interval_us, adv_data=self._payload)
                print("BLE advertising OK!")
                return
            except Exception as e:
                print("Adv attempt", attempt + 1, "failed:", e)
                time.sleep_ms(500)
        print("ERROR: Could not start BLE advertising")

    def _advertising_payload(self, limited_disc=False, br_edr=False, name=None):
        payload = bytearray()
        def _append(adv_type, value):
            nonlocal payload
            payload += struct.pack("BB", len(value) + 1, adv_type) + value

        _append(0x01, struct.pack("B", (0x01 if limited_disc else 0x02) + (0x18 if br_edr else 0x04)))
        if name:
            _append(0x09, name.encode())
        return payload
