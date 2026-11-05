package io.github.baeseungwon1010.officecanvas.pdf

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The standard security handler (7.6.3), with the empty user password: PDFs that
 * only restrict printing or copying (an owner password) open in any viewer, and
 * are read here the same way. RC4 (revisions 2-4), AES-128 (4) and AES-256 (5, 6).
 * A file that needs a user password to open can't be read.
 */
internal class PdfCrypt private constructor(
    private val key: ByteArray,
    /** Per method: "RC4", "AESV2", "AESV3" or "Identity", for streams and strings. */
    private val streamMethod: String,
    private val stringMethod: String
) {
    class PasswordException : Exception("the PDF needs a password to open")

    fun decryptStream(data: ByteArray, ref: PdfRef): ByteArray = decrypt(data, ref, streamMethod)

    fun decryptString(data: ByteArray, ref: PdfRef): ByteArray = decrypt(data, ref, stringMethod)

    private fun decrypt(data: ByteArray, ref: PdfRef, method: String): ByteArray {
        if (method == "Identity" || data.isEmpty()) return data
        if (method == "AESV3") return aes(key, data)
        val aes = method == "AESV2"
        val md5 = MessageDigest.getInstance("MD5")
        md5.update(key)
        md5.update(byteArrayOf(ref.num.toByte(), (ref.num shr 8).toByte(), (ref.num shr 16).toByte(), ref.gen.toByte(), (ref.gen shr 8).toByte()))
        if (aes) md5.update(byteArrayOf(0x73, 0x41, 0x6C, 0x54))
        val objKey = md5.digest().copyOf(minOf(key.size + 5, 16))
        return if (aes) aes(objKey, data) else rc4(objKey, data)
    }

    companion object {
        private val PAD = intArrayOf(
            0x28, 0xBF, 0x4E, 0x5E, 0x4E, 0x75, 0x8A, 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF, 0xFA, 0x01, 0x08,
            0x2E, 0x2E, 0x00, 0xB6, 0xD0, 0x68, 0x3E, 0x80, 0x2F, 0x0C, 0xA9, 0xFE, 0x64, 0x53, 0x69, 0x7A
        ).map { it.toByte() }.toByteArray()

        /** The handler for [enc] (the trailer's /Encrypt), or an exception when it can't be opened. */
        fun open(enc: PdfDict, fileId: ByteArray, resolve: (PdfObj?) -> PdfObj?): PdfCrypt {
            val filter = enc.name("Filter")
            if (filter != null && filter != "Standard") throw PasswordException()
            val v = enc.int("V") ?: 0
            val r = enc.int("R") ?: 2
            fun bytes(k: String) = (resolve(enc[k]) as? PdfString)?.bytes ?: ByteArray(0)
            val o = bytes("O"); val u = bytes("U")
            val p = enc.int("P") ?: 0
            var streamMethod = "RC4"; var stringMethod = "RC4"
            var keyLen = ((enc.int("Length") ?: 40) / 8).coerceIn(5, 16)
            if (v >= 4) {
                val cf = resolve(enc["CF"]) as? PdfDict
                fun method(name: String?): String {
                    if (name == null || name == "Identity") return "Identity"
                    val f = resolve(cf?.get(name)) as? PdfDict ?: return "Identity"
                    return when (f.name("CFM")) { "AESV2" -> "AESV2"; "AESV3" -> "AESV3"; "V2" -> "RC4"; else -> "Identity" }
                }
                streamMethod = method(enc.name("StmF")); stringMethod = method(enc.name("StrF"))
                if (v == 4) keyLen = 16
            }
            if (r >= 5) {
                val key = key256(r, o, u, bytes("OE"), bytes("UE")) ?: throw PasswordException()
                return PdfCrypt(key, streamMethod, stringMethod)
            }
            val encryptMetadata = enc.bool("EncryptMetadata") ?: true
            val md5 = MessageDigest.getInstance("MD5")
            md5.update(PAD)
            md5.update(o.copyOf(32))
            md5.update(byteArrayOf(p.toByte(), (p shr 8).toByte(), (p shr 16).toByte(), (p shr 24).toByte()))
            md5.update(fileId)
            if (r >= 4 && !encryptMetadata) md5.update(byteArrayOf(-1, -1, -1, -1))
            var key = md5.digest()
            if (r >= 3) repeat(50) { key = MessageDigest.getInstance("MD5").digest(key.copyOf(keyLen)) }
            key = key.copyOf(if (r == 2) 5 else keyLen)
            // Check the user password (empty) against /U.
            val ok = if (r == 2) rc4(key, PAD).contentEquals(u.copyOf(32)) else {
                val m = MessageDigest.getInstance("MD5"); m.update(PAD); m.update(fileId)
                var x = rc4(key, m.digest())
                for (i in 1..19) x = rc4(ByteArray(key.size) { (key[it].toInt() xor i).toByte() }, x)
                u.size >= 16 && x.contentEquals(u.copyOf(16))
            }
            if (!ok) throw PasswordException()
            return PdfCrypt(key, streamMethod, stringMethod)
        }

        /** The file key of revision 5 or 6 from /U and /UE, with the empty password. */
        private fun key256(r: Int, o: ByteArray, u: ByteArray, oe: ByteArray, ue: ByteArray): ByteArray? {
            if (u.size < 48 || ue.size < 32) return null
            val pw = ByteArray(0)
            val check = hash(r, pw, u.copyOfRange(32, 40), ByteArray(0))
            if (!check.contentEquals(u.copyOf(32))) return null
            val inter = hash(r, pw, u.copyOfRange(40, 48), ByteArray(0))
            return try {
                val c = Cipher.getInstance("AES/CBC/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(inter, "AES"), IvParameterSpec(ByteArray(16)))
                c.doFinal(ue.copyOf(32))
            } catch (e: Exception) { null }
        }

        /** Algorithm 2.A / 2.B: SHA-256 (revision 5), or the iterated hash of revision 6. */
        private fun hash(r: Int, pw: ByteArray, salt: ByteArray, extra: ByteArray): ByteArray {
            var k = MessageDigest.getInstance("SHA-256").digest(pw + salt + extra)
            if (r == 5) return k
            var round = 0
            while (true) {
                val k1 = ByteArray(0).let { var b = it; repeat(64) { b += pw + k + extra }; b }
                val c = Cipher.getInstance("AES/CBC/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k.copyOf(16), "AES"), IvParameterSpec(k.copyOfRange(16, 32)))
                val e = c.doFinal(k1)
                var mod = 0
                for (i in 0 until 16) mod += e[i].toInt() and 0xFF
                k = MessageDigest.getInstance(when (mod % 3) { 0 -> "SHA-256"; 1 -> "SHA-384"; else -> "SHA-512" }).digest(e)
                round++
                if (round >= 64 && (e.last().toInt() and 0xFF) <= round - 32) break
                if (round > 1000) break
            }
            return k.copyOf(32)
        }

        fun rc4(key: ByteArray, data: ByteArray): ByteArray {
            val s = IntArray(256) { it }
            var j = 0
            for (i in 0 until 256) {
                j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
                val t = s[i]; s[i] = s[j]; s[j] = t
            }
            val out = ByteArray(data.size)
            var i = 0; j = 0
            for (k in data.indices) {
                i = (i + 1) and 0xFF
                j = (j + s[i]) and 0xFF
                val t = s[i]; s[i] = s[j]; s[j] = t
                out[k] = (data[k].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
            }
            return out
        }

        /** AES-CBC with the IV in the first 16 bytes; bad padding is left as it is. */
        fun aes(key: ByteArray, data: ByteArray): ByteArray {
            if (data.size < 32 || data.size % 16 != 0) return if (data.size == 16) ByteArray(0) else data
            return try {
                val c = Cipher.getInstance("AES/CBC/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(data.copyOf(16)))
                val plain = c.doFinal(data, 16, data.size - 16)
                val pad = plain.last().toInt() and 0xFF
                if (pad in 1..16 && plain.size >= pad && (plain.size - pad until plain.size).all { (plain[it].toInt() and 0xFF) == pad }) plain.copyOf(plain.size - pad) else plain
            } catch (e: Exception) { data }
        }
    }
}
