package com.rapsealk.tensorflow.lite

import java.nio.ByteBuffer

/**
 * Created by rapsealk on 2019-10-19..
 */
fun ByteBuffer.toByteArray(): ByteArray {
    rewind()    // Rewind buffet position to 0
    val data = ByteArray(remaining())
    get(data)   // Copy bytebuffer to byte array
    return data
}