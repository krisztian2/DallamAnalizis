package com.example.dudaapp2

// Az Android rendszer alapvető komponensei és szolgáltatásai
import android.Manifest // Engedélyek kezeléséhez
import android.content.pm.PackageManager // Engedélyek állapotának ellenőrzéséhez
import android.media.MediaPlayer // Hangfájlok (pl. felvett WAV) lejátszásához
import android.os.Build // Az Android verziószám ellenőrzéséhez (engedélyek miatt szükséges)
import android.os.Bundle // Az Activity állapotának mentéséhez/visszaállításához
import android.os.Environment // Fájlrendszer elérési utakhoz, pl. külső tárhely
import android.os.Handler // Késleltetett vagy ismétlődő feladatok kezeléséhez (pl. heartbeat)
import android.os.Looper // A Handler-nek szükséges (fő szálon futáshoz)
import android.view.View // Alapvető UI elem, láthatóság (VISIBLE/GONE) beállításához
import android.widget.Button // Gomb UI elem
import android.widget.ImageButton // Ikonos gomb UI elem
import android.widget.TextView // Szövegmező UI elem
import android.widget.Toast // Rövid üzenetek megjelenítésére
import androidx.activity.ComponentActivity // Alap Activity osztály a modern Android fejlesztéshez
import androidx.activity.viewModels // ViewModel delegálásához
import androidx.appcompat.app.AlertDialog // Párbeszédablakok (pl. felvétel lista) megjelenítéséhez
import androidx.core.app.ActivityCompat // Engedélyek kéréséhez
import androidx.core.content.ContextCompat // Színek és engedélyek állapotának lekérdezéséhez
import java.io.File // Fájlkezeléshez (mentés, lejátszás)
import java.io.FileOutputStream // Nem használt, de a fájlkezeléshez importálva volt
import java.text.SimpleDateFormat // Dátum és idő formázásához (pl. felvétel fájlnév)
import java.util.Date // Aktuális dátum lekéréséhez
import java.util.Locale // Nyelvi beállítások a dátum formázásához
import kotlin.math.abs // Abszolút érték számításához (frekvencia különbség)
import kotlin.math.ln // Természetes logaritmus számításához (Hz -> MIDI konverzió)
import kotlin.math.roundToInt // Kerekítéshez (Hz -> MIDI konverzió)
import com.example.dudaapp2.MqttService
/**
 * A fő Activity, amely kezeli a felhasználói felületet, a Bluetooth kommunikációt (BleManager)
 * és a hanggenerálást (BagpipeSynth), valamint a felvételi logikát.
 */
class MainActivity : ComponentActivity() {

    // ViewModel delegálás: a konfigurációt és élő adatokat tárolja, ami túléli az Activity újraindulását
    private val viewModel: MainViewModel by viewModels()
    // A Bluetooth Low Energy (BLE) kommunikációért felelős osztály
    private lateinit var bleManager: BleManager
    // Alap frekvencia változtatásának lépésmérete
    private val FREQ_STEP_SIZE = 1.0f

    // --- AUDIO SYNTH (Saját implementáció) ---
    // A duda hanggenerálásáért felelős osztály
    private val bagpipeSynth = BagpipeSynth()

    // --- FELVÉTELI ÁLLAPOTOK ---
    private var isRecording: Boolean = false // Igaz, ha épp zajlik a WAV felvétel
    private var mediaPlayer: MediaPlayer? = null // Lejátszó a felvett WAV fájlokhoz
    private var currentMidiFile: File? = null // Nem használt, de a MIDI felvétel logikához tervezve
    private var midiWriter: SimpleMidiWriter? = null // Osztály a MIDI adatok rögzítéséhez

    // --- DUDALÁS ÁLLAPOTOK ---
    private var currentBaseFreq: Float = 440.0f // Aktuális alap A4 frekvencia (alaphangolás)
    private var isDallamOn: Boolean = true // Dallamsíp (Melody Pipe) aktív?
    private var isKontraOn: Boolean = true // Kontrasíp (Counter Pipe) aktív?
    private var isBordoOn: Boolean = true // Bordósíp (Drone Pipe) aktív?
    private var isDudalasOn: Boolean = false // A duda hanggenerálás (Synth) be van kapcsolva?

    private var activePipe: Int = 1 // Aktuálisan kiválasztott síp (nem használt a hangerőben, de a kezdeti terv része volt)
    private var volDallam: Int = 5 // Dallamsíp hangerő 0-10
    private var volKontra: Int = 5 // Kontrasíp hangerő 0-10
    private var volBordo: Int = 5 // Bordósíp hangerő 0-10

    // --- UI ELEMEK (Lateinit: később inicializálva) ---
    private lateinit var btnSipDallam: Button
    private lateinit var btnSipKontra: Button
    private lateinit var btnSipBordo: Button

    private lateinit var tvFrequency: TextView // Aktuális frekvencia kijelzése (Base vagy Live MIDI)
    private lateinit var tvVolume: TextView // Aktuális síp hangerő kijelzése
    private lateinit var tvCurrentNote: TextView // Éppen játszott hang neve
    private lateinit var tvBattery: TextView // ESP32 akkumulátor töltöttsége

    private lateinit var btnIncreaseFreq: ImageButton // Alapfrekvencia növelése
    private lateinit var btnDecreaseFreq: ImageButton // Alapfrekvencia csökkentése
    private lateinit var btnIncreaseVol: ImageButton // Hangerő növelése
    private lateinit var btnDecreaseVol: ImageButton // Hangerő csökkentése

    private lateinit var btnPlay: Button // Felvételek lejátszása
    private lateinit var btnRecord: Button // Felvétel indítása/leállítása
    private lateinit var btnDudalas: Button // A duda (synth) be/ki kapcsolása

    private var lastPlayedHz: Float = 0.0f // Utoljára rögzített frekvencia a MIDI logikához
    private var noteStartTime: Long = 0L // Hang kezdetének ideje (nem használt, MIDI tartamhoz kellene)

    // Szívverés (Heartbeat) a kapcsolat fenntartásához
    private val handler = Handler(Looper.getMainLooper())
    // Ismétlődő feladat: 4 másodpercenként fut, és "életjelet" küld az ESP32-nek, ha aktív a dudalás
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (isDudalasOn) {
                // Ez a parancs (MIDI_ON) ébren tartja az ESP32-t és jelzi, hogy adatokat várunk
                bleManager.writeCommand("MIDI_ON".toByteArray())
            }
            handler.postDelayed(this, 4000) // Újraütemezi önmagát
        }
    }

    /**
     * Az Activity létrehozásakor lefutó metódus. Inicializálja az UI-t és az adatkezelést.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // UI layout betöltése
        MqttService.setupMqtt("ssl://eu.thingsboard.cloud:8883", "YwkQ0v9okYXznDOVvz8a")
        bleManager = BleManager(this, viewModel) // BLE menedzser inicializálása
        bindViews() // UI elemek csatlakoztatása (findViewByID)
        wireButtons() // Gombok kattintáskezelőinek beállítása

        updateSipButtonVisuals() // Sípgombok kezdeti megjelenése
        updateDudalasButtonVisual() // Dudalás gomb kezdeti megjelenése
        syncVolumeLabel() // Hangerő felirat szinkronizálása
        setAllControlsVisible(false) // Minden vezérlő elrejtése induláskor (amíg nincs kapcsolat)

        // Synth inicializálása
        bagpipeSynth.baseFreq = currentBaseFreq
        updateSynthVolumes()

        // --- OBSERVEREK (Élő adatfigyelés a ViewModel-ből) ---

        // Kapcsolati állapot figyelése
        viewModel.connectionState.observe(this) { state ->
            val layout = findViewById<View>(R.id.main_layout)
            // Ha kapcsolódva van, fekete háttér és látható vezérlők
           if (state.startsWith("KAPCSOLÓDVA") || state == "READY" || state.startsWith("MEGTALÁLVA")) {
                layout.setBackgroundColor(ContextCompat.getColor(this, android.R.color.black))
                setAllControlsVisible(true)
            } else { // Ha nincs kapcsolat/hiba van, piros háttér és rejtett vezérlők
                layout.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                setAllControlsVisible(false)
            }
        }

        // Kezdeti állapot/konfiguráció fogadása (hangerő beállítások)
        viewModel.initStatus.observe(this) { status ->
            volDallam = status.dVol
            volKontra = status.kVol
            volBordo  = status.bVol
            syncVolumeLabel()
            updateSynthVolumes()
        }

        // Alapfrekvencia figyelése (beolvasás a ViewModel-ből)
        viewModel.frequency.observe(this) { hz ->
            currentBaseFreq = hz.toFloat()
            if (!isDudalasOn) { // Csak akkor frissítjük a kijelzőt, ha nincs élő dudalás (a Live MIDI írja felül)
                tvFrequency.text = getString(R.string.freq_label, currentBaseFreq)
                bagpipeSynth.baseFreq = currentBaseFreq
            }
        }

        // --- ÉLŐ ADATOK (Dudalás közben a síp frekvenciája) ---
        viewModel.liveMidiFreq.observe(this) { liveHz ->
            if (isDudalasOn || isRecording) {
                if (isDudalasOn) {
                    // A synth a Live MIDI frekvenciát játssza le
                    bagpipeSynth.baseFreq = currentBaseFreq // Alapfrekvenciát beállítjuk
                    bagpipeSynth.currentMelodyFreq = liveHz // Az élő frekvencia alapján játszik
                    tvFrequency.text = getString(R.string.freq_label, liveHz) // Kijelző frissítése
                }

                if (liveHz > 20.0f) {
                    val convertedhznote = convertHzToNote(liveHz)
                    tvCurrentNote.text = convertedhznote // Hz -> Hangnév (pl. A4) konverzió
                    MqttService.sendNote(convertedhznote)
                } else {
                    tvCurrentNote.text = "-"
                }

                if (isRecording) {
                    processMidiRecording(liveHz) // MIDI rögzítés logikája
                }
            }
        }

        // Kontra állapot figyelése (pl. hüvelykujj lenyomva)
        viewModel.liveKontraState.observe(this) { isPressed ->
            bagpipeSynth.isKontraModulated = isPressed // Kontra moduláció a synth-ben
            val currentText = tvCurrentNote.text.toString().split(" ")[0]
            if (currentText != "-") {
                val kontraStatus = if (isPressed) "[E]" else "[A]" // [E]rős vagy [A]tlagos kontra
                tvCurrentNote.text = "$currentText $kontraStatus" // Megjelenítés frissítése
                MqttService.sendNote("$currentText $kontraStatus")
            }
        }

        // Akkumulátor töltöttségi szint figyelése
        viewModel.liveBattery.observe(this) { battery ->
            tvBattery.text = "Akku: $battery%"
            val color = when { // Szín beállítása a töltöttség alapján
                battery > 50 -> 0xFF00FF00.toInt() // Zöld
                battery > 20 -> 0xFFFFFF00.toInt() // Sárga
                else -> 0xFFFF0000.toInt() // Piros
            }
            tvBattery.setTextColor(color)
        }
    }

    /**
     * Amikor az Activity újra aktívvá válik (pl. a felhasználó visszatér az APP-ba).
     */
    override fun onResume() {
        super.onResume()
        checkPermissions() // Engedélyek ellenőrzése és kérése
        bleManager.startScanAndConnect() // BLE keresés és csatlakozás indítása
        handler.post(heartbeatRunnable) // Heartbeat indítása
    }

    /**
     * Amikor az Activity leáll (pl. a felhasználó elhagyja az APP-ot).
     */
    override fun onStop() {
        super.onStop()
        if (isRecording) stopRecording() // Felvétel leállítása, ha fut
        bagpipeSynth.stop() // Synth leállítása
        handler.removeCallbacks(heartbeatRunnable) // Heartbeat leállítása
    }

    /**
     * Ellenőrzi és kéri a szükséges Android engedélyeket (Bluetooth, hely, felvétel).
     */
    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.RECORD_AUDIO) // Audio felvételhez
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 (S) felett szükséges BLE engedélyek
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Android 11 (R) alatt Helymeghatározás szükséges a BLE-hez
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        val missing = permissions.filter {
            // Kiszűri azokat az engedélyeket, amik hiányoznak
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            // Kéri a hiányzó engedélyeket
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        }
    }

    /**
     * Csatlakoztatja (hozzárendeli) a UI elemeket a kódban deklarált változókhoz (findViewById).
     */
    private fun bindViews() {
        btnSipDallam = findViewById(R.id.button_sip_dallam)
        btnSipKontra = findViewById(R.id.button_sip_kontra)
        btnSipBordo  = findViewById(R.id.button_sip_bordo)

        tvFrequency     = findViewById(R.id.tvFrequency)
        tvVolume        = findViewById(R.id.tvVolume)

        tvCurrentNote   = findViewById(R.id.tvCurrentNote)
        tvBattery       = findViewById(R.id.tvBattery)

        btnIncreaseFreq = findViewById(R.id.btnIncreaseFreq)
        btnDecreaseFreq = findViewById(R.id.btnDecreaseFreq)
        btnIncreaseVol  = findViewById(R.id.btnIncreaseVol)
        btnDecreaseVol  = findViewById(R.id.btnDecreaseVol)

        btnPlay        = findViewById(R.id.button_play)
        btnRecord      = findViewById(R.id.button_record)
        btnDudalas     = findViewById(R.id.button_dudalas)
    }

    /**
     * Beállítja a gombok kattintáskezelőit (listeners) és a hozzájuk tartozó logikát.
     */
    private fun wireButtons() {
        btnPlay.setOnClickListener { showRecordingsList() } // Felvételek listázása

        btnRecord.setOnClickListener { // Felvétel indítása/leállítása
            if (!isRecording) {
                startRecording()
            } else {
                stopRecording()
            }
        }

        // --- Sípgombok kezelése ---

        btnSipDallam.setOnClickListener {
            isDallamOn = !isDallamOn // Állapot váltása (ki/be)
            updateSipButtonVisuals()
            syncVolumeLabel()
            updateSynthVolumes()
            // Parancs küldése az ESP32-nek
            bleManager.writeCommand(if (isDallamOn) "DALLAM_ON".toByteArray() else "DALLAM_OFF".toByteArray())
        }

        btnSipKontra.setOnClickListener {
            isKontraOn = !isKontraOn
            updateSipButtonVisuals()
            syncVolumeLabel()
            updateSynthVolumes()
            bleManager.writeCommand(if (isKontraOn) "KONTRA_ON".toByteArray() else "KONTRA_OFF".toByteArray())
        }

        btnSipBordo.setOnClickListener {
            isBordoOn = !isBordoOn
            updateSipButtonVisuals()
            syncVolumeLabel()
            updateSynthVolumes()
            bleManager.writeCommand(if (isBordoOn) "BORDO_ON".toByteArray() else "BORDO_OFF".toByteArray())
        }

        // --- Dudalás (Synth) gomb kezelése ---
        btnDudalas.setOnClickListener {
            isDudalasOn = !isDudalasOn // Állapot váltása
            updateDudalasButtonVisual()
            if (isDudalasOn) {
                updateSynthVolumes()

                // Synth indítása (biztosítva, hogy elindul)
                if (!bagpipeSynth.start()) {
                    bagpipeSynth.start()
                }

                bleManager.writeCommand("DUDALAS_ON".toByteArray()) // Jelzés az ESP-nek a dudalás kezdetéről
                // Kicsi késleltetés után küldi a MIDI_ON parancsot, ami elkezdi az adatfolyamot
                Handler(Looper.getMainLooper()).postDelayed({
                    bleManager.writeCommand("MIDI_ON".toByteArray())
                }, 200)
            } else {
                bagpipeSynth.stop() // Synth leállítása
                bleManager.writeCommand("DUDALAS_OFF".toByteArray()) // Jelzés az ESP-nek a dudalás végéről
                // Ha nincs felvétel, kikapcsolja a MIDI adatfolyamot
                if (!isRecording) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        bleManager.writeCommand("MIDI_OFF".toByteArray())
                    }, 200)
                }
                tvCurrentNote.text = "-"
            }
        }

        // --- Frekvencia beállító gombok ---
        btnIncreaseFreq.setOnClickListener {
            currentBaseFreq += FREQ_STEP_SIZE
            updateDisplayAndSynthBase(currentBaseFreq)
            sendFreqToEsp(currentBaseFreq)
        }
        btnDecreaseFreq.setOnClickListener {
            currentBaseFreq -= FREQ_STEP_SIZE
            updateDisplayAndSynthBase(currentBaseFreq)
            sendFreqToEsp(currentBaseFreq)
        }

        // --- Hangerő beállító gombok ---
        btnIncreaseVol.setOnClickListener { changeActiveVolumes(1) }
        btnDecreaseVol.setOnClickListener { changeActiveVolumes(-1) }
    }

    /**
     * Feldolgozza az élő frekvencia adatokat (liveHz) MIDI rögzítés céljából.
     * Csak akkor rögzít, ha a frekvencia jelentősen változott az előzőhez képest.
     */
    private fun processMidiRecording(newHz: Float) {
        // Frekvencia változás érzékelése (5 Hz felett rögzít)
        if (abs(newHz - lastPlayedHz) > 5.0f) {
            if (lastPlayedHz > 50.0f) {
                // Előző hang leállítása (Note Off)
                val midiNote = convertHzToMidiNum(lastPlayedHz)
                midiWriter?.addNoteOff(midiNote)
            }
            if (newHz > 50.0f) {
                // Új hang indítása (Note On)
                val midiNote = convertHzToMidiNum(newHz)
                midiWriter?.addNoteOn(midiNote, 100) // 100-as velocity
            }
            lastPlayedHz = newHz
        }
    }

    /**
     * Frekvencia (Hz) konvertálása olvasható hangnévre (pl. "A4").
     */
    private fun convertHzToNote(hz: Float): String {
        if (hz < 20.0f) return ""
        val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val midiNum = convertHzToMidiNum(hz) // Hz -> MIDI szám (0-127)
        val noteIndex = midiNum % 12 // Hangnév indexe (0=C, 1=C#...)
        val octave = (midiNum / 12) - 1 // Oktáv számítása
        if (noteIndex < 0 || noteIndex >= noteNames.size) return "?"
        return "${noteNames[noteIndex]}$octave" // Hangnév és oktáv összeállítása
    }

    /**
     * Frekvencia (Hz) konvertálása MIDI hangszámra (pl. A4=69).
     * Használt képlet: MIDI = 12 * log2(Hz / 440) + 69
     */
    private fun convertHzToMidiNum(hz: Float): Int {
        if (hz < 20.0f) return 0
        // A logaritmus képlet implementációja
        return (12 * (ln(hz.toDouble() / 440.0) / ln(2.0)) + 69).roundToInt()
    }

    /**
     * Változtatja az aktív (BEkapcsolt) sípok hangerőit.
     * @param delta +1 a növeléshez, -1 a csökkentéshez.
     */
    private fun changeActiveVolumes(delta: Int) {
        var changed = false
        // Dallamsíp hangerő állítása, ha be van kapcsolva
        if (isDallamOn) {
            volDallam = (volDallam + delta).coerceIn(0, 10) // Korlátozás 0 és 10 közé
            bleManager.writeCommand("VOL_DALLAM:$volDallam".toByteArray()) // Parancs küldése
            changed = true
        }
        // Kontrasíp hangerő állítása, ha be van kapcsolva
        if (isKontraOn) {
            volKontra = (volKontra + delta).coerceIn(0, 10)
            bleManager.writeCommand("VOL_KONTRA:$volKontra".toByteArray())
            changed = true
        }
        // Bordósíp hangerő állítása, ha be van kapcsolva
        if (isBordoOn) {
            volBordo = (volBordo + delta).coerceIn(0, 10)
            bleManager.writeCommand("VOL_BORDO:$volBordo".toByteArray())
            changed = true
        }
        if (changed) {
            syncVolumeLabel() // UI frissítése
            updateSynthVolumes() // Synth hangerő frissítése
        }
    }

    /**
     * Frissíti az alapfrekvencia kijelzését és beállítja a Synth-ben.
     */
    private fun updateDisplayAndSynthBase(hz: Float) {
        tvFrequency.text = getString(R.string.freq_label, hz)
        bagpipeSynth.baseFreq = hz
    }

    /**
     * Elküldi az ESP32-nek a beállított A4 alapfrekvenciát a BLE-n keresztül.
     */
    private fun sendFreqToEsp(hz: Float) {
        val cmd = "A4:${String.format("%.1f", hz)}"
        bleManager.writeCommand(cmd.toByteArray())
    }

    /**
     * Frissíti a BagpipeSynth belső hangerő beállításait az aktuális állapotok alapján.
     * A hangerő értékek normalizálva (0.0f és 0.3f közé) vannak a kimeneti hangerő szabályozásához.
     */
    private fun updateSynthVolumes() {
        // Ha nincs bekapcsolva a dudalás, minden hangerő 0
        if (!isDudalasOn) {
            bagpipeSynth.volDallam = 0f
            bagpipeSynth.volKontra = 0f
            bagpipeSynth.volBordo  = 0f
            return
        }
        // Csak akkor állítja be a hangerőt, ha a síp be van kapcsolva (isXOn)
        bagpipeSynth.volDallam = if (isDallamOn) (volDallam / 10.0f) * 0.3f else 0.0f
        bagpipeSynth.volKontra = if (isKontraOn) (volKontra / 10.0f) * 0.3f else 0.0f
        bagpipeSynth.volBordo  = if (isBordoOn)  (volBordo  / 10.0f) * 0.3f else 0.0f
    }

    // --- FELVÉTEL KEZELÉS ---

    /**
     * Elindítja a WAV felvételt a BagpipeSynth segítségével.
     */
    private fun startRecording() {
        // Egyedi fájlnév generálása időbélyeggel
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "REC_$timeStamp.wav"
        // A standard zenei könyvtárban tárolja a fájlt (ExternalFilesDir)
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        val file = File(storageDir, fileName)

        if (!bagpipeSynth.start()) bagpipeSynth.start() // Synth indítása
        bagpipeSynth.startRecording(file) // Felvétel indítása a megadott fájlba

        // MIDI író inicializálása a rögzítéshez
        midiWriter = SimpleMidiWriter()

        bleManager.writeCommand("MIDI_ON".toByteArray()) // MIDI adatfolyam kérése
        isRecording = true
        btnRecord.text = getString(R.string.stop)
        Toast.makeText(this, "WAV Felvétel...", Toast.LENGTH_SHORT).show()
    }

    /**
     * Leállítja a WAV felvételt.
     */
    private fun stopRecording() {
        if (isRecording) {
            bagpipeSynth.stopRecording() // Felvétel leállítása

            if (!isDudalasOn) {
                // Ha a dudalás nincs bekapcsolva, leállítja a MIDI adatfolyamot és a synth-et
                bleManager.writeCommand("MIDI_OFF".toByteArray())
                bagpipeSynth.stop()
            }

            midiWriter = null // MIDI író lezárása
            isRecording = false
            btnRecord.text = getString(R.string.record)
            Toast.makeText(this, "Mentve!", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Megjeleníti a rögzített WAV fájlok listáját egy párbeszédablakban.
     */
    private fun showRecordingsList() {
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        // Kilistázza a .wav kiterjesztésű fájlokat
        val files = storageDir?.listFiles { _, name -> name.endsWith(".wav") }
        if (files.isNullOrEmpty()) {
            Toast.makeText(this, "Nincsenek felvételek.", Toast.LENGTH_SHORT).show()
            return
        }
        val fileNames = files.map { it.name }.toTypedArray()

        // Párbeszédablak felvételekkel
        AlertDialog.Builder(this)
            .setTitle("Lejátszás")
            .setItems(fileNames) { _, which ->
                playWav(files[which]) // Lejátszás indítása
            }
            .setNegativeButton("Mégse", null)
            .show()
    }

    /**
     * Lejátszik egy kiválasztott WAV fájlt a MediaPlayer segítségével.
     */
    private fun playWav(file: File) {
        try {
            mediaPlayer?.release() // Előző lejátszó felszabadítása
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start() // Lejátszás indítása
            }
            Toast.makeText(this, "Lejátszás: ${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Hiba a lejátszáskor.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Beállítja az összes vezérlő UI elem láthatóságát (VISIBLE/GONE).
     * @param visible Igaz, ha láthatóvá tesszük, hamis ha elrejtjük.
     */
    private fun setAllControlsVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        listOf(
            btnSipDallam, btnSipKontra, btnSipBordo,
            tvFrequency, tvVolume, tvCurrentNote, tvBattery,
            btnIncreaseFreq, btnDecreaseFreq, btnIncreaseVol, btnDecreaseVol,
            btnPlay, btnRecord, btnDudalas
        ).forEach { it.visibility = v }
    }

    /**
     * Frissíti a sípgombok vizuális megjelenését (átlátszóság) a ki/be állapot alapján.
     */
    private fun updateSipButtonVisuals() {
        btnSipDallam.alpha = if (isDallamOn) 1.0f else 0.5f
        btnSipKontra.alpha = if (isKontraOn) 1.0f else 0.5f
        btnSipBordo.alpha  = if (isBordoOn)  1.0f else 0.5f
    }

    /**
     * Frissíti a Dudalás gomb vizuális megjelenését.
     */
    private fun updateDudalasButtonVisual() {
        btnDudalas.alpha = if (isDudalasOn) 1.0f else 0.5f
    }

    /**
     * Szinkronizálja a hangerő kijelzést (`tvVolume`) az aktuálisan bekapcsolt sípok közül
     * a legmagasabb prioritású (Dallam -> Kontra -> Bordó) hangerő értékével.
     */
    private fun syncVolumeLabel() {
        val volToShow = when {
            isDallamOn -> volDallam   // Dallamsíp
            isKontraOn -> volKontra   // Kontrasíp
            isBordoOn  -> volBordo    // Bordósíp
            else -> 0                 // Ha egyik sincs bekapcsolva
        }
        tvVolume.text = "Vol: $volToShow"
    }

    // A getActiveVol függvény jelenleg nem használt.
    private fun getActiveVol(): Int {
        return when (activePipe) {
            1 -> volDallam
            2 -> volKontra
            3 -> volBordo
            else -> volDallam
        }
    }

    // Segédfüggvény a Synth indítás ellenőrzésére (a hívási helyek egyszerűsítéséhez)
    private fun BagpipeSynth.start(): Boolean {
        // Feltételezve, hogy a BagpipeSynth.start() ténylegesen elindítja a lejátszást
        this.start()
        return true
    }

    // Hogy ne legyen hiba a midiWriter változónál, egy stub osztály a MIDI rögzítéshez
    class SimpleMidiWriter {
        fun addNoteOn(n:Int, v:Int) {} // Hang indítása
        fun addNoteOff(n:Int) {} // Hang leállítása
    }
}