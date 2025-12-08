package com.example.dudaapp2

// AndroidX Lifecycle és ViewModel komponensek
import androidx.lifecycle.LiveData // Nem szerkeszthető adatfolyam
import androidx.lifecycle.MutableLiveData // Szerkeszthető adatfolyam
import androidx.lifecycle.ViewModel // Az Activity-től független adatréteg

/**
 * Adatmodell az ESP32-ből érkező kezdeti állapot (initial status) tárolására.
 * @param pipe A kezdeti aktív síp (nem használt, de a BLE protokoll része)
 * @param dVol Dallamsíp (Melody Pipe) kezdeti hangerő
 * @param kVol Kontrasíp (Counter Pipe) kezdeti hangerő
 * @param bVol Bordósíp (Drone Pipe) kezdeti hangerő
 */
data class InitStatus(val pipe: Int, val dVol: Int, val kVol: Int, val bVol: Int)

/**
 * A MainViewModel tárolja az APP aktuális állapotát és a Bluetooth-on
 * keresztül érkező élő adatokat. Túléli az Activity konfigurációs változásait (pl. képernyő elforgatás).
 */
class MainViewModel : ViewModel() {
    // Bluetooth kapcsolat állapota (pl. "KERESÉS...", "KAPCSOLÓDVA", "HIBA: 133")
    val connectionState = MutableLiveData<String>()

    // Kezdeti állapot (hangerők) privát, szerkeszthető változata
    private val _initStatus = MutableLiveData<InitStatus>()
    // Kezdeti állapot publikus, csak olvasható LiveData-ja (az Activity ezt figyeli)
    val initStatus: LiveData<InitStatus> = _initStatus

    // ESP32-ből érkező A4 referencia frekvencia
    val frequency = MutableLiveData<Int>()
    // ESP32-ből érkező élő MIDI frekvencia (a síp aktuális hangja)
    val liveMidiFreq = MutableLiveData<Float>()
    // ESP32 akkumulátor töltöttsége (%)
    val liveBattery = MutableLiveData<Int>()
    // ESP32-ből érkező Kontra síp állapot (pl. modulálva van-e)
    val liveKontraState = MutableLiveData<Boolean>()

    /**
     * Frissíti a kezdeti állapot (InitStatus) adatait.
     * @param dVol, kVol, bVol Az egyes sípok aktuális hangerő értékei (0-10)
     */
    fun updateInitStatus(pipe: Int, dVol: Int, kVol: Int, bVol: Int) {
        _initStatus.postValue(InitStatus(pipe, dVol, kVol, bVol))
    }

    /**
     * Frissíti az A4 referencia frekvencia értékét.
     * @param hz Az új frekvencia Hertz-ben (egész szám)
     */
    fun updateFrequency(hz: Int) { frequency.postValue(hz) }

    /**
     * Frissíti a Bluetooth kapcsolat állapotát (a UI-n megjelenő szöveg)
     */
    fun setState(state: String) { connectionState.postValue(state) }

    /**
     * Kezeli a Bluetooth-on keresztül érkező nyers bájttömb (data) feldolgozását.
     * A protokoll feltételezi, hogy a beérkező adatok 4 bájtot tartalmaznak.
     *
     * Adat protokoll (feltételezett):
     * [0] - MIDI frekvencia LSB (Low Byte)
     * [1] - MIDI frekvencia MSB (High Byte)
     * [2] - Kontra állapot (0 = ki, 1 = be)
     * [3] - Akkumulátor töltöttség (%)
     */
    fun onDataReceived(data: ByteArray) {
        if (data.size >= 4) {
            // MIDI Frekvencia (2 bájton, Little-endian)
            // Összefűzi a 0. és 1. bájtot egy Int-té
            val hz = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
            liveMidiFreq.postValue(hz.toFloat()) // Float-ként küldi tovább (a Main Activity-be)

            // Kontra állapot (3. bájt)
            val kontra = (data[2].toInt() != 0) // Ha a bájt értéke nem nulla, akkor igaz
            liveKontraState.postValue(kontra)

            // Akkumulátor töltöttség (4. bájt)
            val batt = data[3].toInt() and 0xFF // Csak a bájt értékét veszi
            liveBattery.postValue(batt)
        }
    }
}