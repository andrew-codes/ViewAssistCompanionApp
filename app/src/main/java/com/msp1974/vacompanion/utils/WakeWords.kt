package com.msp1974.vacompanion.utils

import android.os.Environment
import com.msp1974.vacompanion.utils.AuthUtils.Companion.log
import kotlin.io.path.Path
import kotlin.io.path.forEachDirectoryEntry
import kotlin.io.path.isDirectory

data class WakeWord(val name: String, val fileName: String, val custom: Boolean = false)

class WakeWords {
    var availableWakeWords = mapOf(
        "alexa" to WakeWord("Alexa", "alexa.onnx"),
        "hey_jarvis" to WakeWord("Hey Jarvis", "hey_jarvis.onnx"),
        "hey_mycroft" to WakeWord("Hey Mycroft", "hey_mycroft.onnx"),
        "hey_raspy" to WakeWord("Hey Rhasspy", "hey_rhasspy.onnx"),
        "ok_nabu" to WakeWord("Ok Nabu", "ok_nabu.onnx"),
        "ok_computer" to WakeWord("Ok Computer", "ok_computer.onnx")
    )

    fun getCustomWakeWords(path: String): Map<String, WakeWord> {
        val customWakeWords = mutableMapOf<String, WakeWord>()
        val downloadPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val vacaDir = Path(downloadPath.toString(), path)

        if (vacaDir.isDirectory()) {
            log.d("Custom wake words directory found")
            vacaDir.forEachDirectoryEntry( "*.onnx", { entry ->
                log.d("Found custom wake word: ${entry.fileName}")
                val key = entry.fileName.toString().replace(".onnx", "").lowercase()
                val name = key.replace("_", " ")

                customWakeWords[key] = WakeWord(name.capitalizeWords(), entry.fileName.toString(), true)
            })
        }
        return customWakeWords
    }

    fun String.capitalizeWords(delimiter: String = " ") =
        split(delimiter).joinToString(delimiter) { word ->

            val smallCaseWord = word.lowercase()
            smallCaseWord.replaceFirstChar(Char::titlecaseChar)

        }

    fun getWakeWords(): Map<String, WakeWord> {
        return  mutableMapOf<String, WakeWord>().apply {
            putAll(availableWakeWords)
            putAll(getCustomWakeWords("vaca"))
        }
    }
}