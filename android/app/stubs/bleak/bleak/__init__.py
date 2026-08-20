"""Android packaging stub for FindMy.py's optional desktop BLE scanner.

LibrePods uses its existing Android Bluetooth implementation for nearby devices. The
FindMy network path only imports account, keychain, accessory and report modules; FindMy.py
loads its Bleak scanner lazily. Keeping this explicit failing surface prevents pip from
pulling Linux D-Bus packages which cannot run inside Android/Chaquopy.
"""


class BleakScanner:
    def __init__(self, *args, **kwargs):
        raise RuntimeError(
            "FindMy.py's desktop Bleak scanner is unavailable on Android; "
            "use LibrePods' native Android scanner"
        )


__all__ = ["BleakScanner"]
