import time

class DS3231:
    def __init__(self, i2c, addr=0x68):
        self.i2c = i2c
        self.addr = addr

    def _dec2bcd(self, value):
        return (value // 10) << 4 | (value % 10)

    def _bcd2dec(self, value):
        return ((value >> 4) * 10) + (value & 0x0F)

    def datetime(self, datetime_tuple=None):
        if datetime_tuple is None:
            try:
                buf = self.i2c.readfrom_mem(self.addr, 0x00, 7)
            except OSError:
                print("DS3231 not found at I2C address 0x68. Check wiring.")
                return (2000, 1, 1, 1, 0, 0, 0, 0)
            
            second = self._bcd2dec(buf[0])
            minute = self._bcd2dec(buf[1])
            hour = self._bcd2dec(buf[2] & 0x3F)
            weekday = self._bcd2dec(buf[3])
            day = self._bcd2dec(buf[4])
            month = self._bcd2dec(buf[5] & 0x1F)
            year = self._bcd2dec(buf[6]) + 2000
            
            return (year, month, day, weekday, hour, minute, second, 0)
            
        else:
            if len(datetime_tuple) == 8:
                year, month, day, weekday, hour, minute, second, _ = datetime_tuple
            else:
                year, month, day, weekday, hour, minute, second = datetime_tuple[:7]
            
            buf = bytearray(7)
            buf[0] = self._dec2bcd(second)
            buf[1] = self._dec2bcd(minute)
            buf[2] = self._dec2bcd(hour)
            buf[3] = self._dec2bcd(weekday)
            buf[4] = self._dec2bcd(day)
            buf[5] = self._dec2bcd(month)
            buf[6] = self._dec2bcd(year - 2000)
            
            self.i2c.writeto_mem(self.addr, 0x00, buf)
