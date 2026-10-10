package app.domik.data

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Nie udało się zaszyfrować wpisu (np. klucz w Android Keystore niedostępny). Komunikat nie zawiera danych. */
class SecretStorageException(cause: Throwable) : Exception("secret storage failed: ${cause::class.java.simpleName}", cause)

/**
 * Szyfrowanie drobnych tajemnic (numer seryjny sterownika) przed zapisem na dysk. Implementacja produkcyjna
 * ([AesGcmCipher] + klucz z Android Keystore) jest za interfejsem, żeby testy mogły użyć zwykłego klucza w pamięci.
 *
 * [associatedData] wiąże szyfrogram z miejscem użycia (np. identyfikatorem profilu): szyfrogram skopiowany pod inny
 * identyfikator nie da się odszyfrować.
 */
interface SecretCipher {
    /** @throws SecretStorageException szyfrowanie nie powiodło się. */
    fun encrypt(plain: ByteArray, associatedData: ByteArray): ByteArray

    /** Null, gdy odszyfrowanie się nie udało (inny klucz, uszkodzone dane, inne [associatedData]). */
    fun decrypt(sealed: ByteArray, associatedData: ByteArray): ByteArray?
}

/** Źródło klucza AES; na Androidzie klucz leży w sprzętowym/systemowym magazynie i nie opuszcza go. */
fun interface SecretKeySource {
    fun key(): SecretKey
}

/**
 * AES-256-GCM (bez dopełnienia, znacznik 128 bit). Wektor IV generuje sam `Cipher` (wymóg Android Keystore:
 * losowy IV przy każdym szyfrowaniu), zapis: `[1 bajt długość IV][IV][szyfrogram + znacznik]`.
 * Czyste `javax.crypto` – bez `androidx.security-crypto` (biblioteka jest wycofana).
 */
class AesGcmCipher(private val keySource: SecretKeySource) : SecretCipher {
    override fun encrypt(plain: ByteArray, associatedData: ByteArray): ByteArray =
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keySource.key())
            cipher.updateAAD(associatedData)
            val iv = cipher.iv
            val body = cipher.doFinal(plain)
            ByteArray(1 + iv.size + body.size).also {
                it[0] = iv.size.toByte()
                iv.copyInto(it, 1)
                body.copyInto(it, 1 + iv.size)
            }
        } catch (error: GeneralSecurityException) {
            throw SecretStorageException(error)
        } catch (error: RuntimeException) {
            throw SecretStorageException(error)
        }

    override fun decrypt(sealed: ByteArray, associatedData: ByteArray): ByteArray? {
        if (sealed.isEmpty()) return null
        val ivLength = sealed[0].toInt() and 0xFF
        if (ivLength !in MIN_IV_LENGTH..MAX_IV_LENGTH || sealed.size < 1 + ivLength + TAG_BYTES) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keySource.key(),
                GCMParameterSpec(TAG_BYTES * 8, sealed, 1, ivLength),
            )
            cipher.updateAAD(associatedData)
            cipher.doFinal(sealed, 1 + ivLength, sealed.size - 1 - ivLength)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BYTES = 16
        const val MIN_IV_LENGTH = 12
        const val MAX_IV_LENGTH = 16
    }
}
