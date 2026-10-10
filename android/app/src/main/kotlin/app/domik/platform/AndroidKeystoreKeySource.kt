package app.domik.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.domik.data.SecretKeySource
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Klucz AES-256 w Android Keystore (alias [alias]): tworzony przy pierwszym użyciu, nieeksportowalny.
 * Szyfrowanie nim numeru seryjnego robi [app.domik.data.AesGcmCipher].
 */
class AndroidKeystoreKeySource(private val alias: String = DEFAULT_ALIAS) : SecretKeySource {
    @Synchronized
    override fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "domik.settings.v1"
        const val KEY_BITS = 256
    }
}
