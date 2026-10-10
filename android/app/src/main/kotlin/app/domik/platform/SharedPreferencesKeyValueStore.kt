package app.domik.platform

import android.content.SharedPreferences
import app.domik.data.KeyValueStore

/** [KeyValueStore] na `SharedPreferences` (plik poza kopią zapasową: `allowBackup=false`). */
class SharedPreferencesKeyValueStore(private val preferences: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun getInt(key: String, default: Int): Int = preferences.getInt(key, default)

    override fun edit(block: KeyValueStore.Editor.() -> Unit) {
        val editor = preferences.edit()
        object : KeyValueStore.Editor {
            override fun putString(key: String, value: String) {
                editor.putString(key, value)
            }

            override fun putInt(key: String, value: Int) {
                editor.putInt(key, value)
            }

            override fun remove(key: String) {
                editor.remove(key)
            }
        }.block()
        editor.apply()
    }
}
