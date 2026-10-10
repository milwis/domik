package app.domik.deimic.config.sqlite

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TableDefinitionTest {
    private fun parse(sql: String) = TableDefinition.parse("t", sql)

    private fun names(sql: String) = parse(sql).columns.map { it.name }

    @Test
    fun plainColumnsAndTypes() {
        val definition = parse("CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT, name VARCHAR(30) NOT NULL, price DECIMAL(10, 2), note, flag BOOLEAN DEFAULT 1)")
        assertEquals(listOf("id", "name", "price", "note", "flag"), definition.columns.map { it.name })
        assertEquals(listOf("INTEGER", "VARCHAR(30)", "DECIMAL(10,2)", "", "BOOLEAN"), definition.columns.map { it.declaredType })
        assertEquals(0, definition.rowidAlias)
        assertFalse(definition.withoutRowid)
    }

    @Test
    fun quotedIdentifiersAndComments() {
        assertEquals(
            listOf("a b", "c\"d", "e", "f g", "h"),
            names("""CREATE TABLE "t" ("a b" INTEGER, "c""d" TEXT, `e` INT, [f g] REAL, 'h' /* x, y */ TEXT -- , z
                ) """),
        )
    }

    @Test
    fun commasAndParenthesesInsideConstraintsDoNotSplitColumns() {
        val sql = "CREATE TABLE t (a INTEGER CHECK (a IN (1, 2, 3)), b TEXT DEFAULT (lower('A,B')), c REAL, UNIQUE (a, b), CHECK (c > (1 + 2)))"
        assertEquals(listOf("a", "b", "c"), names(sql))
    }

    @Test
    fun tableConstraintsAreNotColumns() {
        val sql = "CREATE TABLE t (a INT, b INT, CONSTRAINT pk PRIMARY KEY (a, b), FOREIGN KEY (a) REFERENCES o(x), UNIQUE (b))"
        val definition = parse(sql)
        assertEquals(listOf("a", "b"), definition.columns.map { it.name })
        assertEquals(-1, definition.rowidAlias)
    }

    @Test
    fun rowidAliasRules() {
        assertEquals(0, parse("CREATE TABLE t (id INTEGER PRIMARY KEY, v)").rowidAlias)
        assertEquals(0, parse("CREATE TABLE t (id integer primary key asc, v)").rowidAlias)
        assertEquals(0, parse("CREATE TABLE t (id INTEGER NOT NULL PRIMARY KEY, v)").rowidAlias)
        assertEquals(1, parse("CREATE TABLE t (v, id INTEGER PRIMARY KEY)").rowidAlias)
        assertEquals(0, parse("CREATE TABLE t (id INTEGER, v, PRIMARY KEY (id))").rowidAlias)
        assertEquals(0, parse("CREATE TABLE t (id INTEGER, v, CONSTRAINT x PRIMARY KEY (id))").rowidAlias)
        // not aliases
        assertEquals(-1, parse("CREATE TABLE t (id INTEGER PRIMARY KEY DESC, v)").rowidAlias)
        assertEquals(-1, parse("CREATE TABLE t (id INT PRIMARY KEY, v)").rowidAlias)
        assertEquals(-1, parse("CREATE TABLE t (id BIGINT PRIMARY KEY, v)").rowidAlias)
        assertEquals(-1, parse("CREATE TABLE t (id INTEGER UNIQUE, v)").rowidAlias)
        assertEquals(-1, parse("CREATE TABLE t (id INTEGER, v, PRIMARY KEY (id, v))").rowidAlias)
        assertEquals(-1, parse("CREATE TABLE t (id TEXT PRIMARY KEY, v)").rowidAlias)
    }

    @Test
    fun withoutRowidIsRecognized() {
        val definition = parse("CREATE TABLE t (a INTEGER PRIMARY KEY, b) WITHOUT ROWID")
        assertTrue(definition.withoutRowid)
        assertEquals(-1, definition.rowidAlias)
    }

    @Test
    fun realAffinity() {
        val definition = parse("CREATE TABLE t (a REAL, b FLOAT, c DOUBLE PRECISION, d INTEGER, e TEXT, f NUMERIC, g CHARINT, h)")
        assertEquals(listOf(true, true, true, false, false, false, false, false), definition.columns.map { it.isRealAffinity })
    }

    @Test
    fun definitionsWithoutAColumnListAreRejected() {
        assertThrows<SqliteFormatException> { parse("CREATE TABLE t AS SELECT 1") }
        assertThrows<SqliteFormatException> { parse("CREATE TABLE t (a INT") }
    }

    @Test
    fun garbageNeverThrowsAnythingElse() {
        val random = java.util.Random(7)
        val alphabet = "()\"'`[]-/*, ABCDEFINTKRYP\n\té"
        repeat(2000) {
            val sql = String(CharArray(random.nextInt(60)) { alphabet[random.nextInt(alphabet.length)] })
            try {
                parse(sql)
            } catch (expected: SqliteFormatException) {
                // fine
            }
        }
    }
}
