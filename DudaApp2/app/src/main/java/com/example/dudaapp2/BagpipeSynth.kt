package com.example.dudaapp2

// Android Audio és I/O importok
import android.media.AudioFormat // Audio adat formátumának beállításához
import android.media.AudioManager // Hangkezelő szolgáltatások
import android.media.AudioTrack // Alacsony szintű audió lejátszáshoz
import java.io.File // Fájlkezeléshez
import java.io.FileOutputStream // Fájlba íráshoz (felvétel)
import java.io.RandomAccessFile // Fájlba történő véletlen hozzáféréshez (WAV header frissítése)
import java.nio.ByteBuffer // Bájtok puffereléséhez
import java.nio.ByteOrder // Bájt sorrend (endianness) beállításához (Little-Endian a WAV-hoz)
import kotlin.math.PI // Pi konstans használata
import kotlin.math.abs // Abszolút érték

/**
 * A duda hangjának (oszcillátorok, drone) szintetizálásáért felelős osztály,
 * valamint kezeli a hangkimenetet (AudioTrack) és a WAV felvételt.
 */
class BagpipeSynth {

    private val sampleRate = 44100 // Mintavételezési frekvencia (Audio CD minőség)
    private var isRunning = false // Igaz, ha az audió szál aktív
    private var audioThread: Thread? = null // A háttérszál, ami a hangot generálja
    private var audioTrack: AudioTrack? = null // Az Android audió kimeneti puffer kezelője

    // --- Szintetizátor Állapotok (Volatile: szálbiztos írás/olvasás) ---
    @Volatile var baseFreq = 440.0f // Alap A4 frekvencia (hangolás)
    @Volatile var currentMelodyFreq = 440.0f // Az aktuálisan játszott dallamsíp frekvenciája (BLE-ből jön)
    @Volatile var isKontraModulated = false // Kontrasíp moduláció (pl. ujjnyomás hatására)

    @Volatile var volDallam = 0.0f // Dallamsíp relatív hangerő (0.0 - 1.0)
    @Volatile var volKontra = 0.0f // Kontrasíp relatív hangerő (0.0 - 1.0)
    @Volatile var volBordo = 0.0f // Bordósíp relatív hangerő (0.0 - 1.0)
    var masterVolume = 0.8f // Fő kimeneti hangerő (csökkenti a túlvezérlés esélyét)

    private var isRecording = false // Igaz, ha WAV felvétel zajlik
    private var recordingFile: File? = null // A felvételi fájl
    private var recordingStream: FileOutputStream? = null // A nyers audió adatot író stream

    // --- Hangkimenet és Szintézis Szál Vezérlés ---

    /**
     * Elindítja a Bagpipe Szintetizátort és az audió generáló szálat.
     * @return true, ha sikeres az indítás, false hiba esetén.
     */
    fun start(): Boolean {
        if (isRunning) return true // Már fut, minden oké

        // A minimális puffer méretének lekérése
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            // AudioTrack inicializálása
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC, sampleRate, // Média stream, mintavételezés
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, // Formátum: mono, 16 bit PCM
                bufferSize, AudioTrack.MODE_STREAM // Puffer méret, stream módban
            )
            audioTrack?.play() // Lejátszás indítása
            isRunning = true
        } catch (e: Exception) {
            e.printStackTrace()
            return false // Hiba történt, nem tudjuk elindítani a hanglejátszást
        }

        // --- Az audió generáló háttérszál ---
        audioThread = Thread {
            val buffer = ShortArray(bufferSize) // A kimeneti audió mintákat tároló puffer
            // Fázis akkumulátorok a 3 síphoz (0 és 2*PI között futnak)
            var pDallam = 0.0; var pKontra = 0.0; var pBordo = 0.0

            while (isRunning) { // A szál addig fut, amíg az isRunning igaz

                // --- 1. Frekvencia és Inkrementum Számítás (Drone Sípok) ---

                // Bordo (Drone): Két oktávval lejjebb (alapfrekvencia / 4)
                val fBordo = baseFreq / 4.0
                // Fázis inkrementum: f * 2*PI / sampleRate (standard digitális oszcillátor formula)
                val incBordo = (fBordo * 2.0 * PI / sampleRate)

                // Kontra (Drone): Egy oktávval lejjebb (alapfrekvencia / 2)
                // Moduláció: ha a kontra aktív, kissé lejjebb hangoljuk (0.749 * alap)
                val fKontra = if (isKontraModulated) (baseFreq / 2.0 * 0.749) else (baseFreq / 2.0)
                val incKontra = (fKontra * 2.0 * PI / sampleRate)

                // Dallam: Az élő MIDI frekvencia, ha van (különben az alap frekvencia)
                val fDallam = if (currentMelodyFreq > 20) currentMelodyFreq else baseFreq
                val incDallam = (fDallam * 2.0 * PI / sampleRate)

                // --- 2. Minták generálása a pufferbe (Szintézis Loop) ---
                for (i in 0 until bufferSize) {
                    // Rampe/Fűrészfog hullám generálása a fázisból (pX / PI ad 0-2, ebből -1.0 a -1-től 1-ig)
                    var sDallam = if (volDallam > 0) ((pDallam / PI) - 1.0) * volDallam else 0.0
                    var sKontra = if (volKontra > 0) ((pKontra / PI) - 1.0) * volKontra else 0.0
                    var sBordo = if (volBordo > 0) ((pBordo / PI) - 1.0) * volBordo else 0.0

                    // Összekeverés és Fő Hangerő alkalmazása
                    val mixed = (sDallam + sKontra + sBordo) * masterVolume
                    // Clipping/Klappolás: biztosítja, hogy a hangerő -1.0 és 1.0 között maradjon
                    val clamped = if (mixed > 1.0) 1.0 else if (mixed < -1.0) -1.0 else mixed
                    // Konverzió 16-bites PCM értékre (Short.MAX_VALUE = 32767)
                    buffer[i] = (clamped * Short.MAX_VALUE).toInt().toShort()

                    // Fázis léptetése és körbefordulás (modulo 2*PI)
                    pDallam = (pDallam + incDallam) % (2.0 * PI)
                    pKontra = (pKontra + incKontra) % (2.0 * PI)
                    pBordo = (pBordo + incBordo) % (2.0 * PI)
                }

                // --- 3. Audió Kimentése ---
                try { audioTrack?.write(buffer, 0, bufferSize) } catch (e: Exception) { break }

                // --- 4. Felvétel (WAV) ---
                if (isRecording && recordingStream != null) {
                    // Puffer konvertálása bájttömbökre 16-bites Short-okból
                    val byteBuffer = ByteBuffer.allocate(buffer.size * 2)
                    byteBuffer.order(ByteOrder.LITTLE_ENDIAN) // Little-endian bájtsorrend (WAV szabvány)
                    for (s in buffer) byteBuffer.putShort(s)
                    try { recordingStream?.write(byteBuffer.array()) } catch (e: Exception) { }
                }
            }
        }
        audioThread?.start()
        return true // Sikeres indítás
    }

    /**
     * Leállítja az audió szálat és felszabadítja az AudioTrack erőforrásokat.
     */
    fun stop() {
        isRunning = false // Szál leállítása
        try { audioThread?.join(100) } catch (e: Exception) {} // Vár a szál befejezésére
        // AudioTrack leállítása és felszabadítása
        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) {}
        audioTrack = null
    }

    // --- WAV Felvétel Kezelés ---

    /**
     * Elindítja a WAV fájl felvételét.
     * @param file A célfájl, ahová az adatok mentésre kerülnek.
     */
    fun startRecording(file: File) {
        recordingFile = file
        try {
            recordingStream = FileOutputStream(file)
            // Header írása: 44 bájtos helyfoglaló (placeholder) írása, mivel a fájl méretét még nem tudjuk
            writeWavHeader(recordingStream!!, sampleRate.toInt(), 16, 0)
            isRecording = true
        } catch (e: Exception) { e.printStackTrace() }
    }

    /**
     * Leállítja a felvételt, lezárja a streamet és frissíti a WAV header-t.
     */
    fun stopRecording() {
        isRecording = false
        try {
            recordingStream?.close() // Stream lezárása (a PCM adatok véglegesítése)
            recordingFile?.let { updateWavHeader(it) } // A header frissítése a valós méretekkel
        } catch (e: Exception) { e.printStackTrace() }
        recordingStream = null
    }

    /**
     * Üres WAV header írása a fájl elejére.
     * Ez a 44 bájt később kerül felülírásra a valós méretadatokkal.
     */
    private fun writeWavHeader(out: FileOutputStream, sampleRate: Int, bitDepth: Short, totalDataLen: Int) {
        val header = ByteArray(44)
        out.write(header) // 44 nulla bájt írása
    }

    /**
     * Frissíti a felvett WAV fájl header-ét a rögzített adatmennyiség alapján (RandomAccessFile használatával).
     * Ez kulcsfontosságú, hogy a WAV fájl lejátszható legyen.
     */
    private fun updateWavHeader(file: File) {
        try {
            val fileSize = file.length() // Teljes fájlméret (header + adat)
            val totalDataLen = fileSize - 8 // RIFF Chunk mérete (fájlméret - 'RIFF' 4bájt - méret 4bájt)
            val totalAudioLen = fileSize - 44 // Adat Chunk mérete (fájlméret - header 44bájt)
            // Byte rate: Mintavételezés * Bitmélység / 8 * Csatornák száma (44100 * 16 / 8 * 1)
            val byteRate = sampleRate * 16 * 1 / 8

            val randomAccessFile = RandomAccessFile(file, "rw") // Olvasási/írási hozzáférés a fájlhoz
            randomAccessFile.seek(0) // Visszaugrás a fájl elejére

            // RIFF Chunk (0-11 bájt)
            randomAccessFile.writeBytes("RIFF") // 0: RIFF azonosító
            randomAccessFile.writeInt(Integer.reverseBytes(totalDataLen.toInt())) // 4: RIFF mérete (Little-endian)
            randomAccessFile.writeBytes("WAVE") // 8: WAVE formátum

            // FMT Chunk (12-35 bájt)
            randomAccessFile.writeBytes("fmt ") // 12: 'fmt ' azonosító
            randomAccessFile.writeInt(Integer.reverseBytes(16)) // 16: Méret (mindig 16)
            randomAccessFile.writeShort(java.lang.Short.reverseBytes(1).toInt()) // 20: Típus (1=PCM)
            randomAccessFile.writeShort(java.lang.Short.reverseBytes(1).toInt()) // 22: Csatornák száma (1=Mono)
            randomAccessFile.writeInt(Integer.reverseBytes(sampleRate)) // 24: Mintavételezési ráta
            randomAccessFile.writeInt(Integer.reverseBytes(byteRate)) // 28: Byte rate
            randomAccessFile.writeShort(java.lang.Short.reverseBytes(2).toInt()) // 32: Block align (2 = 16 bit * 1 csatorna / 8)
            randomAccessFile.writeShort(java.lang.Short.reverseBytes(16).toInt()) // 34: Bit mélység (16)

            // DATA Chunk (36-43 bájt)
            randomAccessFile.writeBytes("data") // 36: 'data' azonosító
            randomAccessFile.writeInt(Integer.reverseBytes(totalAudioLen.toInt())) // 40: Adat (PCM) mérete

            randomAccessFile.close() // Fájl lezárása
        } catch (e: Exception) { e.printStackTrace() }
    }
}