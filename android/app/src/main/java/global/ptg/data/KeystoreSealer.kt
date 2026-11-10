package global.ptg.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import global.ptg.mobile.Sealer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the credential file with AES-256-GCM. The key lives in the Android Keystore
 * (hardware-backed where the phone has it) and can't be read out, even with root.
 *
 * Stored layout: MAGIC | 12-byte IV | ciphertext+tag. Anything without MAGIC is treated as
 * plaintext from an older version and returned as-is; Go then rewrites it encrypted.
 */
object KeystoreSealer : Sealer {
    private const val ALIAS = "backy-credentials"
    private val MAGIC = "BKY1".toByteArray()
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return MAGIC + c.iv + c.doFinal(plain)
    }

    override fun open(stored: ByteArray): ByteArray {
        if (stored.size < MAGIC.size || !stored.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return stored
        val iv = stored.copyOfRange(MAGIC.size, MAGIC.size + IV_BYTES)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        }
        return c.doFinal(stored, MAGIC.size + IV_BYTES, stored.size - MAGIC.size - IV_BYTES)
    }
}
