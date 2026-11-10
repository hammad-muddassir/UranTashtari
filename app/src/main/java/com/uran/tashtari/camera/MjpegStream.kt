package com.uran.tashtari.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

fun mjpegFrames(url: String): Flow<Bitmap> = flow {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 5000
    conn.readTimeout = 5000
    try {
        conn.connect()
        BufferedInputStream(conn.inputStream, 16 * 1024).use { input ->
            val buf = ByteArray(16 * 1024)
            val frame = ByteArrayOutputStream(64 * 1024)
            var inFrame = false
            var prev = 0
            while (currentCoroutineContext().isActive) {
                val n = input.read(buf)
                if (n < 0) break
                for (i in 0 until n) {
                    val b = buf[i].toInt() and 0xFF
                    if (!inFrame) {
                        if (prev == 0xFF && b == 0xD8) {          // JPEG start
                            inFrame = true
                            frame.reset()
                            frame.write(0xFF); frame.write(0xD8)
                        }
                    } else {
                        frame.write(b)
                        if (prev == 0xFF && b == 0xD9) {          // JPEG end
                            val bytes = frame.toByteArray()
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ?.let { emit(it) }
                            inFrame = false
                        } else if (frame.size() > 500_000) {      // corrupt frame guard
                            inFrame = false
                        }
                    }
                    prev = b
                }
            }
        }
    } finally {
        conn.disconnect()
    }
}.flowOn(Dispatchers.IO)