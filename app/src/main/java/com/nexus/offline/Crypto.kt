package com.nexus.offline

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private val rnd = SecureRandom()

    fun newKeyPair(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        return g.generateKeyPair()
    }

    fun encodePub(k: PublicKey): String = Base64.encodeToString(k.encoded, Base64.NO_WRAP)
    fun encodePriv(k: PrivateKey): String = Base64.encodeToString(k.encoded, Base64.NO_WRAP)

    fun decodePub(s: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(s, Base64.NO_WRAP)))

    fun decodePriv(s: String): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(s, Base64.NO_WRAP)))

    /** Per-pair end-to-end key (ECDH). Relays cannot derive it. */
    fun pairKey(priv: PrivateKey, peerPub: String): SecretKeySpec {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(decodePub(peerPub), true)
        val secret = ka.generateSecret()
        val h = MessageDigest.getInstance("SHA-256").digest(secret + "nexus-pair-v1".toByteArray())
        return SecretKeySpec(h, "AES")
    }

    /** Key for broadcast / group traffic, derived from the shared network passphrase. */
    fun groupKey(pass: String): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pass.toCharArray(), "nexus-offline-group-v1".toByteArray(), 20000, 256)
        return SecretKeySpec(f.generateSecret(spec).encoded, "AES")
    }

    fun encrypt(key: SecretKeySpec, plain: ByteArray): String {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + c.doFinal(plain), Base64.NO_WRAP)
    }

    fun decrypt(key: SecretKeySpec, b64: String): ByteArray? = try {
        val all = Base64.decode(b64, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, all.copyOfRange(0, 12)))
        c.doFinal(all, 12, all.size - 12)
    } catch (e: Exception) {
        null
    }

    /** Short human-comparable identity fingerprint, e.g. "A1B2 C3D4 E5F6". */
    fun fingerprint(pubB64: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(Base64.decode(pubB64, Base64.NO_WRAP))
        return d.take(6).joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")
    }
}
