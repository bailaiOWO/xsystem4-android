package io.github.kichikuou.xsystem4

import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

interface GameListObserver {
    fun onInstallProgress(path: String)
    fun onInstallSuccess()
    fun onInstallFailure(msgId: Int)
}

sealed class InstallState {
    object Idle : InstallState()
    data class Installing(val progress: String?) : InstallState()
    object Succeeded : InstallState()
    data class Failed(val msgId: Int) : InstallState()
}

data class Item(val name: String, val path: File, val homedir: File, val savedir: File?, val icon: File?, val error: String?) {
    companion object {
        fun fromDirectory(dir: File, homedir: File, context: Context): Item {
            var iniFile = File(dir, "System40.ini")
            if (!iniFile.exists())
                iniFile = File(dir, "AliceStart.ini")
            if (!iniFile.exists()) {
                val err = context.getString(R.string.toast_no_ini, dir.path)
                Log.w("GameList", err)
                return Item(dir.name, dir, homedir, null, null, err)
            }
            val icon = findIcon(dir)
            val ini = System40Ini.parse(iniFile)
            val savedir = File(dir, ini.SaveFolder ?: "SaveData")
            // CN: display the folder name so users can freely rename games
            // (ini GameName encoding varies between CN patches).
            return Item(dir.name, dir, homedir, savedir, icon, null)
        }

        private fun findIcon(dir: File): File? {
            // Prefer the icon embedded in the game exe.
            try {
                dir.listFiles { file ->
                    file.extension.equals("exe", ignoreCase = true) &&
                            file.name != "OpenSaveFolder.exe" &&
                            file.name != "ResetConfig.exe" &&
                            file.name != "Uninstaller.exe"
                }?.forEach { exeFile ->
                    PEResourceExtractor.create(exeFile)?.extractIcon()?.let { bytes ->
                        val f = File(dir, ".xsystem4.ico")
                        f.writeBytes(bytes)
                        return f
                    }
                }
            } catch (e: Exception) {
                Log.e("GameList", "Failed to extract or write icon", e)
            }
            // Fall back to a standalone .ico shipped with the game
            // (skip our own cache file).
            dir.listFiles()?.forEach {
                if (it.extension.equals("ico", ignoreCase = true) && !it.name.startsWith(".")) {
                    return it
                }
            }
            return null
        }

        // BitmapFactory doesn't support the ICO container format, so icons
        // extracted from game exes never decoded. Parse ICO manually instead:
        // each entry is either an embedded PNG or a bottom-up BMP.
        private fun decodeIco(bytes: ByteArray): Bitmap? {
            if (bytes.size < 22) return null
            val header = ByteBuffer.wrap(bytes)
            header.order(ByteOrder.LITTLE_ENDIAN)
            if (header.short.toInt() != 0 || header.short.toInt() != 1) return null
            val count = header.short.toInt()
            var best: Bitmap? = null
            var bestWidth = -1
            for (i in 0 until count) {
                header.position(6 + i * 16)
                val wb = header.get().toInt()
                header.get() // height
                header.get() // color count
                header.get() // reserved
                header.short  // planes
                header.short  // bpp
                val size = header.int
                val offset = header.int
                if (size <= 0 || offset < 0 || offset + size > bytes.size) continue
                val width = if (wb == 0) 256 else wb
                val isPng = bytes[offset] == 0x89.toByte() && bytes[offset + 1] == 0x50.toByte() &&
                        bytes[offset + 2] == 0x4E.toByte() && bytes[offset + 3] == 0x47.toByte()
                val bmp = if (isPng) {
                    BitmapFactory.decodeByteArray(bytes, offset, size)
                } else {
                    decodeIcoBmpEntry(bytes, offset)
                }
                if (bmp != null && width > bestWidth) {
                    bestWidth = width
                    best = bmp
                }
            }
            return best
        }

        private fun decodeIcoBmpEntry(bytes: ByteArray, offset: Int): Bitmap? {
            val buf = ByteBuffer.wrap(bytes, offset, bytes.size - offset)
            buf.order(ByteOrder.LITTLE_ENDIAN)
            val headerSize = buf.int
            if (headerSize < 40 || headerSize > bytes.size - offset) return null
            val w = buf.int
            val h2 = buf.int
            val height = h2 / 2
            if (w <= 0 || height <= 0) return null
            buf.short // planes
            val bpp = buf.short.toInt()
            val compression = buf.int
            if (compression != 0) return null
            if (bpp != 1 && bpp != 4 && bpp != 8 && bpp != 24 && bpp != 32) return null

            // Palette for indexed icons (bpp <= 8): RGBQUAD table after the header.
            var palette: IntArray? = null
            if (bpp <= 8) {
                val numColors = 1 shl bpp
                val paletteStart = offset + headerSize
                if (paletteStart + numColors * 4 > bytes.size) return null
                palette = IntArray(numColors) { i ->
                    val p = paletteStart + i * 4
                    val b = bytes[p].toInt() and 0xff
                    val g = bytes[p + 1].toInt() and 0xff
                    val r = bytes[p + 2].toInt() and 0xff
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            val bytesPerRow = ((bpp * w + 31) / 32) * 4
            val pixelsStart = offset + headerSize + (if (bpp <= 8) (1 shl bpp) * 4 else 0)
            if (pixelsStart + height * bytesPerRow > bytes.size) return null

            // Optional AND mask (1bpp) after the pixel data: set bit = transparent.
            val andRowBytes = (w + 31) / 32 * 4
            val andStart = pixelsStart + height * bytesPerRow
            val hasAndMask = andStart + height * andRowBytes <= bytes.size

            val pixels = IntArray(w * height)
            for (y in 0 until height) {
                val rowStart = pixelsStart + (height - 1 - y) * bytesPerRow
                for (x in 0 until w) {
                    pixels[y * w + x] = when (bpp) {
                        32 -> {
                            val p = rowStart + x * 4
                            val b = bytes[p].toInt() and 0xff
                            val g = bytes[p + 1].toInt() and 0xff
                            val r = bytes[p + 2].toInt() and 0xff
                            val a = bytes[p + 3].toInt() and 0xff
                            (a shl 24) or (r shl 16) or (g shl 8) or b
                        }
                        24 -> {
                            val p = rowStart + x * 3
                            val b = bytes[p].toInt() and 0xff
                            val g = bytes[p + 1].toInt() and 0xff
                            val r = bytes[p + 2].toInt() and 0xff
                            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                        8 -> palette!![bytes[rowStart + x].toInt() and 0xff]
                        4 -> {
                            val v = bytes[rowStart + x / 2].toInt()
                            val nibble = if (x % 2 == 0) (v shr 4) and 0x0f else v and 0x0f
                            palette!![nibble]
                        }
                        else -> { // 1
                            val v = bytes[rowStart + x / 8].toInt()
                            palette!![(v shr (7 - x % 8)) and 1]
                        }
                    }
                }
                if (hasAndMask && bpp != 32) {
                    val andRow = andStart + (height - 1 - y) * andRowBytes
                    for (x in 0 until w) {
                        val v = bytes[andRow + x / 8].toInt()
                        if (((v shr (7 - x % 8)) and 1) == 1) {
                            pixels[y * w + x] = 0
                        }
                    }
                }
            }
            return Bitmap.createBitmap(pixels, w, height, Bitmap.Config.ARGB_8888)
        }
    }

    fun getIconBitmap(reqSize: Int): Bitmap? {
        val bytes = icon?.readBytes() ?: return null
        return decodeIco(bytes)
    }
}

data class System40Ini(val gameName: String?, val SaveFolder: String?) {
    companion object {
        fun parse(file: File): System40Ini {
            val regex = Regex("""(\w+)\s*=\s*"(.*)"""")
            var gameName: String? = null
            var SaveFolder: String? = null
            for (line in decodeIniBytes(file.readBytes()).lineSequence()) {
                regex.matchEntire(line)?.let {
                    when (it.groupValues[1]) {
                        "GameName" -> gameName = it.groupValues[2]
                        "SaveFolder" -> SaveFolder = it.groupValues[2]
                    }
                }
            }
            return System40Ini(gameName, SaveFolder)
        }

        // CN games ship GBK-encoded ini files (the JP comments are re-encoded
        // to GBK too, so SJIS parsing mojibakes the Chinese game name).
        // Decode GBK first; if the result contains several chars from the
        // SJIS-kana-misread-through-GBK mojibake set, it's actually a JP file.
        private val SJIS_KANA_MOJIBAKE =
            "丄丅丒丠両丣並丱丵丷丼乀乁乆乊乑乕乣乽乿亀亁偀偁偂偄偅偆偉偊偋偍偐偑偒偓偔偖偗偘偙偛偝偞偟偠偡偢偣偤偦偧偨偩偪偫偭偮偯偰偱偲偳側偵偸偹偺偼偽傁傂傃傄傆傇傉傊傋傌傎傏傐傑傒傓傔傕傖傗傘備傚傛傜傝傞傟傠傡傢傤傦傪傫傽傾傿僀僁僂僃僄僅僆僇僈僉僊僋僌働僎僐僑僒僓僔僕僗僘僙僛僜僝僞僟僠僡僢僣僤僥僨僩僪僫僯僰僱僲僴僶僷僸價僺僼僽僾僿儀儁儂儃億儅儈儉儊儌儍儎儏儐儑儓儔儕儖儗儘儙儚儛儜儝儞償儠儢"

        private fun decodeIniBytes(bytes: ByteArray): String {
            val sjis = Charset.forName("Shift_JIS")
            val gbkCs = Charset.forName("GBK")
            fun decodeStrict(cs: Charset, b: ByteArray): String? = try {
                cs.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString()
            } catch (e: CharacterCodingException) {
                null
            }
            // CN ini files mix encodings: GBK game name lines + possibly
            // SJIS JP comment lines (older CN patches kept JP comments in
            // SJIS, which decode through GBK into kana-mojibake). Decide the
            // encoding PER LINE so both decode correctly.
            return String(bytes, Charsets.ISO_8859_1).split('\n').joinToString("\n") { isoLine ->
                var line = isoLine.toByteArray(Charsets.ISO_8859_1)
                if (line.isNotEmpty() && line.last() == 0x0d.toByte()) {
                    line = line.copyOf(line.size - 1)
                }
                if (line.isEmpty()) {
                    return@joinToString ""
                }
                val gbk = decodeStrict(gbkCs, line)
                if (gbk != null && gbk.count { SJIS_KANA_MOJIBAKE.contains(it) } < 2) {
                    gbk
                } else {
                    decodeStrict(sjis, line) ?: String(line, sjis)
                }
            }
        }
    }
}

class GameList(activity: Activity) {
    private val context: Context = activity.applicationContext
    private val items: ArrayList<Item> = arrayListOf()
    private val storageDirs: ArrayList<File> = arrayListOf()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var installJob: Job? = null
    operator fun get(index: Int): Item = items[index]
    val size: Int get() = items.size
    var observer: GameListObserver? = null
    var installState: InstallState = InstallState.Idle
        private set

    class InstallFailureException(val msgId: Int) : Exception()

    init {
        for (storagePath in activity.getExternalFilesDirs(null).filterNotNull()) {
            val state = Environment.getExternalStorageState(storagePath)
            Log.i("GameList", "${storagePath}: $state")
            if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) {
                continue
            }
            storageDirs.add(storagePath)
            val homedir = File(storagePath, ".xsystem4")
            val files = storagePath.listFiles() ?: continue
            for (path in files) {
                if (!path.name.startsWith(".") && path.isDirectory) {
                    items.add(Item.fromDirectory(path, homedir, context))
                }
            }
            if (state == Environment.MEDIA_MOUNTED && files.isEmpty()) {
                // Create a dummy file and do a media scan so that `storagePath` is discoverable via MTP.
                // https://issuetracker.google.com/issues/37071807
                val dummyFile = File(storagePath, "dummy.txt")
                dummyFile.writeText("Create a subfolder here that stores game files.")
                MediaScannerConnection.scanFile(
                    activity,
                    arrayOf(dummyFile.absolutePath),
                    null,
                    null
                )
            }
        }
        moveSaveDirectories()
    }

    fun install(input: InputStream) {
        if (installJob?.isActive == true) {
            closeRejectedInput(input)
            return
        }
        installState = InstallState.Installing(null)
        installJob = scope.launch {
            try {
                input.use { installInput ->
                    val storagePath = storageDirs[0]
                    val path = withContext(Dispatchers.IO) {
                        doInstall(installInput, storagePath) { msg ->
                            withContext(Dispatchers.Main) {
                                setInstallProgress(msg)
                            }
                        }
                    }
                    val homedir = File(storagePath, ".xsystem4")
                    items.add(Item.fromDirectory(path, homedir, context))
                }
                setInstallSucceeded()
            } catch (e: InstallFailureException) {
                setInstallFailed(e.msgId)
            } catch (e: Exception) {
                Log.e("GameList", "Failed to extract ZIP", e)
                setInstallFailed(R.string.zip_extraction_error)
            }
        }
    }

    fun consumeInstallResult() {
        if (installState is InstallState.Succeeded || installState is InstallState.Failed) {
            installState = InstallState.Idle
        }
    }

    private fun setInstallProgress(progress: String) {
        installState = InstallState.Installing(progress)
        observer?.onInstallProgress(progress)
    }

    private fun setInstallSucceeded() {
        installState = InstallState.Succeeded
        observer?.onInstallSuccess()
    }

    private fun setInstallFailed(msgId: Int) {
        installState = InstallState.Failed(msgId)
        observer?.onInstallFailure(msgId)
    }

    private fun closeRejectedInput(input: InputStream) {
        try {
            input.close()
        } catch (e: IOException) {
            Log.w("GameList", "Failed to close rejected install input", e)
        }
    }

    @TargetApi(Build.VERSION_CODES.N)
    private suspend fun doInstall(
        input: InputStream,
        storagePath: File,
        progressCallback: suspend (String) -> Unit
    ): File {
        val tempDir = File(storagePath, ".install_temp")
        tempDir.deleteRecursively()
        val tempDirCanonical = tempDir.canonicalFile
        val zip = ZipInputStream(input.buffered(), Charset.forName("Shift_JIS"))
        var gameRoot: File? = null
        try {
            zip.use {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        continue
                    }
                    val path = resolveZipEntryPath(tempDir, tempDirCanonical, entry.name)
                    Log.i("GameList", "Extracting ${entry.name}")
                    progressCallback(entry.name)
                    path.parentFile?.mkdirs()
                    path.outputStream().buffered().use { zip.copyTo(it) }

                    if (path.name == "System40.ini" || path.name == "AliceStart.ini") {
                        gameRoot = path.parentFile
                    }
                }
            }
            val srcDir = gameRoot ?: throw InstallFailureException(R.string.zip_no_ini)
            var dstDir = File(storagePath, srcDir.name)
            var i = 1
            while (dstDir.exists()) {
                dstDir = File(storagePath, "${srcDir.name} (${i++})")
            }
            if (!srcDir.renameTo(dstDir)) {
                throw IOException("Failed to move installed game: ${srcDir} -> ${dstDir}")
            }
            return dstDir
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun resolveZipEntryPath(tempDir: File, tempDirCanonical: File, entryName: String): File {
        val path = File(tempDir, entryName.replace('\\', File.separatorChar)).canonicalFile
        if (path == tempDirCanonical || !path.path.startsWith(tempDirCanonical.path + File.separator)) {
            throw IOException("ZIP entry escapes install directory: ${entryName}")
        }
        return path
    }

    fun uninstall(item: Item) {
        item.path.deleteRecursively()
        items.remove(item)
    }

    // Move save directories to the new location.
    // TODO: Remove this after some transition period.
    private fun moveSaveDirectories() {
        for (item in items) {
            if (item.error != null) continue
            val oldPath = File(item.homedir, item.name)
            val files = oldPath.listFiles() ?: continue
            for (file in files) {
                val newPath = File(item.path, file.name)
                Log.i("GameList", "Moving save directory: ${file} -> ${newPath}")
                if (newPath.exists()) {
                    Log.w("GameList", "Removing existing save directory: ${newPath}")
                    newPath.deleteRecursively()
                }
                file.renameTo(newPath)
            }
            oldPath.deleteRecursively()
        }
    }
}
