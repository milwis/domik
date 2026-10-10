package app.domik.data

/**
 * Małe ustawienia aplikacji (klucz → tekst lub liczba). Interfejs zamiast bezpośrednio `SharedPreferences`,
 * żeby logikę ustawień dało się testować w JVM. Odczyt jest synchroniczny (kilka kluczy – start aplikacji
 * nie czeka na dysk dłużej niż zwykłe `SharedPreferences`).
 */
interface KeyValueStore {
    fun getString(key: String): String?

    fun getInt(key: String, default: Int): Int

    /** Zapisuje wszystkie zmiany z [block] naraz (jedna transakcja). */
    fun edit(block: Editor.() -> Unit)

    interface Editor {
        fun putString(key: String, value: String)

        fun putInt(key: String, value: Int)

        fun remove(key: String)
    }
}

/** Magazyn w pamięci – do testów. */
class InMemoryKeyValueStore : KeyValueStore {
    val values = LinkedHashMap<String, Any>()

    override fun getString(key: String): String? = values[key] as? String

    override fun getInt(key: String, default: Int): Int = values[key] as? Int ?: default

    override fun edit(block: KeyValueStore.Editor.() -> Unit) {
        val pending = LinkedHashMap(values)
        object : KeyValueStore.Editor {
            override fun putString(key: String, value: String) {
                pending[key] = value
            }

            override fun putInt(key: String, value: Int) {
                pending[key] = value
            }

            override fun remove(key: String) {
                pending.remove(key)
            }
        }.block()
        values.clear()
        values.putAll(pending)
    }
}
