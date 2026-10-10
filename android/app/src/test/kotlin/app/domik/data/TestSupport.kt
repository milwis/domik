package app.domik.data

/** Szyfr do testów: XOR z kluczem, ale sprawdza dane powiązane jak prawdziwy GCM. */
internal class FakeCipher : SecretCipher {
    var failEncrypt = false
    var failDecrypt = false

    override fun encrypt(plain: ByteArray, associatedData: ByteArray): ByteArray {
        if (failEncrypt) throw SecretStorageException(IllegalStateException())
        return associatedData + byteArrayOf(0) + plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    override fun decrypt(sealed: ByteArray, associatedData: ByteArray): ByteArray? {
        if (failDecrypt) return null
        val prefix = associatedData + byteArrayOf(0)
        if (sealed.size < prefix.size || !sealed.copyOf(prefix.size).contentEquals(prefix)) return null
        return sealed.copyOfRange(prefix.size, sealed.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}
