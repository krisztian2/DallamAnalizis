package com.example.dudaapp2

// Android szükséges könyvtárak
import android.annotation.SuppressLint // Engedélyek kezeléséhez szükséges elnyomás
import android.bluetooth.* // Bluetooth, BluetoothGatt, Callback osztályok
import android.bluetooth.le.ScanCallback // BLE szkennelés visszahívási interfésze
import android.bluetooth.le.ScanResult // Szkennelési eredmény tárolója
import android.content.Context // Konfigurációs információk eléréséhez
import android.os.Build // Android verziószám ellenőrzéséhez
import android.os.Handler // Aszinkron feladatok ütemezéséhez
import android.os.Looper // A fő szál (UI thread) eléréséhez
import android.util.Log // Naplózáshoz
import java.util.* // UUID (Universal Unique Identifier) kezeléséhez

/**
 * Felelős a Bluetooth Low Energy (BLE) kommunikációért az ESP32-Duda eszközzel.
 * Kezeli a keresést, csatlakozást, adatírást és az élő adatok fogadását.
 * @param context Az alkalmazás kontextusa
 * @param vm A ViewModel a kapcsolat állapotának és a fogadott adatok frissítéséhez
 */
@SuppressLint("MissingPermission") // Az Android Studio ne figyelmeztessen az engedélyek hiányára (feltételezve, hogy a MainActivity kezeli azokat)
class BleManager(private val context: Context, private val vm: MainViewModel) {

    // A BLE szolgáltatás (Service) egyedi azonosítója, amelyet az ESP32-n definiáltak
    private val serviceUuid = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")
    // A jellemző (Characteristic) egyedi azonosítója (ezen keresztül írunk és olvasunk adatot)
    private val charUuid = UUID.fromString("0000abcd-0000-1000-8000-00805f9b34fb")

    private var gatt: BluetoothGatt? = null // A GATT (Generic Attribute Profile) kapcsolat objektuma
    private var characteristic: BluetoothGattCharacteristic? = null // A használt BLE jellemző objektuma
    private val handler = Handler(Looper.getMainLooper()) // Handler a fő szálon történő ütemezéshez
    private val adapter: BluetoothAdapter? = // A helyi Bluetooth adapter
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var isScanning = false // Jelzi, hogy a szkennelés aktív-e

    /**
     * Elindítja a BLE szkennelést az "ESP32-Duda" eszköz megtalálására és csatlakozik hozzá.
     * Ez a metódus kezeli az automatikus újra-csatlakozást is (reconnect logika).
     */
    fun startScanAndConnect() {
        if (adapter == null || !adapter.isEnabled) {
            vm.setState("BT KIKAPCSOLVA") // Visszajelzés a ViewModel-nek
            return
        }

        vm.setState("KERESÉS...")
        val scanner = adapter.bluetoothLeScanner
        if (scanner != null) {
            isScanning = true
            scanner.startScan(scanCallback) // Szkennelés indítása
            // Időzítő a szkennelés leállítására 10 másodperc után
            handler.postDelayed({
                if (isScanning) {
                    scanner.stopScan(scanCallback)
                    isScanning = false
                    if (gatt == null) vm.setState("NEM TALÁLHATÓ")
                }
            }, 10000)
        } else {
            vm.setState("SCANNER HIBA")
        }
    }

    // Szkennelési visszahívások kezelése
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val name = device.name ?: ""

            // Az "ESP32-Duda" eszköz szűrése
            if (name.startsWith("ESP32-Duda")) {
                if (isScanning) {
                    adapter?.bluetoothLeScanner?.stopScan(this) // Szkennelés leállítása
                    isScanning = false
                    vm.setState("MEGTALÁLVA: $name")
                    connectToDevice(device) // Csatlakozás az eszközhöz
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            vm.setState("KERESÉS HIBA: $errorCode")
        }
    }

    /**
     * Csatlakozik a megtalált Bluetooth eszközhöz a connectGatt metódussal.
     */
    private fun connectToDevice(device: BluetoothDevice) {
        vm.setState("CSATLAKOZÁS...")
        // TRANSPORT_LE használata a LE kapcsolat kényszerítésére, ha elérhető (API 23+)
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            // Régebbi API-k esetén a sima connectGatt
            device.connectGatt(context, false, gattCallback)
        }
    }

    /**
     * Adatokat ír a jellemzőre (Characteristic) (pl. vezérlőparancsok küldése az ESP32-nek).
     * @param bytes A küldendő bájttömb (pl. "DALLAM_ON".toByteArray())
     */
    fun writeCommand(bytes: ByteArray) {
        val c = characteristic ?: return // Ellenőrzi, hogy a jellemző létezik-e
        val g = gatt ?: return // Ellenőrzi, hogy a GATT kapcsolat létezik-e
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Új API (33+) használata a bájttömb közvetlen átadásával
                g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                // Régebbi API-k esetén a Characteristic objektumon keresztül állítjuk be az értéket
                @Suppress("deprecation")
                c.value = bytes
                @Suppress("deprecation")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("deprecation")
                g.writeCharacteristic(c)
            }
        } catch (e: Exception) {
            Log.e("BleManager", "Írási hiba: ${e.message}")
        }
    }

    // Bluetooth GATT visszahívások kezelése
    private val gattCallback = object : BluetoothGattCallback() {
        /**
         * Segédfüggvény az automatikus újracsatlakozási folyamat elindításához.
         * Felszabadítja az erőforrásokat és újraindítja a szkennelést/csatlakozást.
         */
        private fun attemptReconnect() {
            gatt?.close() // GATT kapcsolat lezárása
            gatt = null
            characteristic = null
            // 2 másodperc késleltetés után újraindítja a keresést
            handler.postDelayed({ startScanAndConnect() }, 2000)
        }

        /**
         * Segédfüggvény a fogadott adatok feldolgozásához, a két onCharacteristicChanged metódus számára.
         */
        private fun handleCharacteristicChanged(value: ByteArray) {
            Log.d("BLE-DATA", "Raw: " + value.joinToString(","))
            vm.onDataReceived(value) // Adatok továbbítása a ViewModel-nek
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                vm.setState("MEGSZAKADT")
                attemptReconnect() // Automatikus újracsatlakozás bontás esetén
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Ha kritikus hiba történt a kapcsolatban (pl. status 133)
                vm.setState("HIBA: $status")
                attemptReconnect()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                vm.setState("KAPCSOLÓDVA")
                // Szolgáltatások felfedezése kis késleltetéssel
                handler.postDelayed({
                    if (gatt != null) g.discoverServices()
                }, 600)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            // Szolgáltatás lekérése
            val svc = g.getService(serviceUuid) ?: run {
                vm.setState("SZERVIZ HIBA")
                return
            }

            // Jellemző (Characteristic) lekérése
            val chr = svc.getCharacteristic(charUuid) ?: run {
                vm.setState("KARAKTERISZTIKA HIBA")
                return
            }

            characteristic = chr
            g.setCharacteristicNotification(chr, true) // Értesítések engedélyezése

            // A 0x2902 descriptor (Client Characteristic Configuration Descriptor - CCCD)
            // szükséges az értesítések aktiválásához
            val descUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            val descriptor = chr.getDescriptor(descUuid)

            if (descriptor != null) {
                // Descriptor írása kis késleltetéssel
                handler.postDelayed({
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("deprecation")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("deprecation")
                        g.writeDescriptor(descriptor)
                    }
                }, 300)
            } else {
                vm.setState("READY") // Kész állapot
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                vm.setState("READY") // Kész állapot
            } else {
                Log.e("BleManager", "Descriptor írási hiba: $status")
                vm.setState("READY (Descriptor hiba: $status)")
            }
        }

        // --- Karakterisztika Változás Kezelése (Conflicting Overloads Fix) ---
        // A metódus felülírása a modern (API 33+) 3-paraméteres metódussal
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // A bájttömb közvetlenül megérkezik a `value` paraméterben
            handleCharacteristicChanged(value)
        }

        // A metódus felülírása a régebbi (API < 33) 2-paraméteres metódussal
        @Suppress("deprecation")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // A bájttömböt a `characteristic.value` mezőből kell kiolvasni
            handleCharacteristicChanged(characteristic.value ?: return)
        }
    }
}