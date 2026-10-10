package app.domik.data

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AesGcmCipherTest {
    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = newKey()
    private val cipher = AesGcmCipher { key }
    private val aad = "profil-1".toByteArray()

    @Test
    fun `szyfrowanie i odszyfrowanie zwraca te same bajty`() {
        val plain = "12AB34CD".toByteArray()
        val sealed = cipher.encrypt(plain, aad)
        assertArrayEquals(plain, cipher.decrypt(sealed, aad))
    }

    @Test
    fun `szyfrogram nie zawiera tekstu jawnego`() {
        val plain = "12AB34CD".toByteArray()
        val sealed = cipher.encrypt(plain, aad)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("12AB34CD"))
    }

    @Test
    fun `dwa szyfrowania tego samego tekstu daja rozne szyfrogramy`() {
        val plain = "12AB34CD".toByteArray()
        val first = cipher.encrypt(plain, aad)
        val second = cipher.encrypt(plain, aad)
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `zmiana jednego bajtu uniemozliwia odszyfrowanie`() {
        val sealed = cipher.encrypt("12AB34CD".toByteArray(), aad)
        for (index in sealed.indices) {
            val tampered = sealed.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertNull(cipher.decrypt(tampered, aad), "bajt $index")
        }
    }

    @Test
    fun `inne dane powiazane uniemozliwiaja odszyfrowanie`() {
        val sealed = cipher.encrypt("12AB34CD".toByteArray(), aad)
        assertNull(cipher.decrypt(sealed, "profil-2".toByteArray()))
    }

    @Test
    fun `inny klucz nie odszyfruje`() {
        val sealed = cipher.encrypt("12AB34CD".toByteArray(), aad)
        assertNull(AesGcmCipher { newKey() }.decrypt(sealed, aad))
    }

    @Test
    fun `obciete lub puste dane daja null a nie wyjatek`() {
        val sealed = cipher.encrypt("12AB34CD".toByteArray(), aad)
        assertNull(cipher.decrypt(ByteArray(0), aad))
        assertNull(cipher.decrypt(byteArrayOf(12), aad))
        assertNull(cipher.decrypt(sealed.copyOf(10), aad))
        assertNull(cipher.decrypt(sealed.copyOf(sealed.size - 1), aad))
        assertNull(cipher.decrypt(ByteArray(40) { 0xFF.toByte() }, aad))
    }

    @Test
    fun `blad klucza przy szyfrowaniu to SecretStorageException`() {
        val broken = AesGcmCipher { throw java.security.KeyStoreException("brak klucza") }
        assertThrows(SecretStorageException::class.java) { broken.encrypt(byteArrayOf(1), aad) }
        assertNull(broken.decrypt(cipher.encrypt(byteArrayOf(1), aad), aad))
    }

    @Test
    fun `odszyfrowany tekst jest poprawny po zaszyfrowaniu pustych danych`() {
        val sealed = cipher.encrypt(ByteArray(0), aad)
        assertNotNull(cipher.decrypt(sealed, aad))
    }
}
