package app.domik.data

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pliki projektu czytane w testach (Gradle uruchamia testy w katalogu modułu `android/app`): zasoby tekstowe
 * i katalog wersji bibliotek – testy pilnują spójności tekstów i listy licencji z tym, co faktycznie w nich jest.
 */
internal fun moduleFile(relativePath: String): File {
    val file = File(relativePath)
    check(file.isFile) { "Brak pliku $relativePath względem ${File(".").canonicalPath} – testy uruchamiaj Gradle'em z modułu app" }
    return file
}

/** Wartości zasobów `<string>` z pliku `res/<dir>/strings.xml` (po rozkodowaniu encji XML, bez zmiany `\n` i `\'`). */
internal fun stringResources(valuesDir: String): Map<String, String> {
    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(moduleFile("src/main/res/$valuesDir/strings.xml"))
    val nodes = document.getElementsByTagName("string")
    return (0 until nodes.length).associate { index ->
        val node = nodes.item(index)
        node.attributes.getNamedItem("name").nodeValue to node.textContent
    }
}
