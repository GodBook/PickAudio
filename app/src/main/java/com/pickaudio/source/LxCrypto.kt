package com.pickaudio.source

import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object LxCrypto {
    fun aesEncrypt(data: ByteArray, mode: String, key: ByteArray, iv: ByteArray): ByteArray {
        require(data.size <= 1024 * 1024) { "AES input exceeds 1 MiB" }
        val normalized = mode.lowercase(Locale.ROOT)
        val match = Regex("(?:aes-(128|192|256)-)?(ecb|cbc)").matchEntire(normalized)
            ?: throw IllegalArgumentException("Unsupported AES mode: $mode")
        require(key.size in listOf(16, 24, 32)) { "Invalid AES key length" }
        match.groupValues[1].toIntOrNull()?.let { bits ->
            require(key.size * 8 == bits) { "AES mode and key length do not match" }
        }
        val blockMode = match.groupValues[2].uppercase(Locale.ROOT)
        val cipher = Cipher.getInstance("AES/$blockMode/PKCS5Padding")
        val secret = SecretKeySpec(key, "AES")
        if (blockMode == "CBC") {
            require(iv.size == 16) { "AES-CBC requires a 16-byte IV" }
            cipher.init(Cipher.ENCRYPT_MODE, secret, IvParameterSpec(iv))
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, secret)
        }
        return cipher.doFinal(data)
    }
}
