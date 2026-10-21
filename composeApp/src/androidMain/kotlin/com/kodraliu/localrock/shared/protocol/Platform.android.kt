package com.kodraliu.localrock.shared.protocol

import android.content.Context
import java.io.File
import java.security.SecureRandom

internal actual fun secureRandomBytes(size: Int): ByteArray {
    val buf = ByteArray(size)
    SecureRandom().nextBytes(buf)
    return buf
}

@Volatile
private var appContext: Context? = null

fun initVacLocalAndroidContext(context: Context) {
    appContext = context.applicationContext
}

fun appContextOrNull(): Context? = appContext

actual fun saveDebugBlob(name: String, bytes: ByteArray): String? {
    val ctx = appContext ?: return null
    val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
    // Older builds saved every copy as "<stem>-<timestamp>.<ext>" and never deleted any.
    val stem = name.substringBeforeLast('.')
    val ext = name.substringAfterLast('.')
    dir.listFiles { f -> f.name.startsWith("$stem-") && f.name.endsWith(".$ext") }?.forEach { it.delete() }
    val file = File(dir, name)
    file.writeBytes(bytes)
    return file.absolutePath
}
