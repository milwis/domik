package app.domik.deimic.config.sqlite

/** One column of a table as declared in its `CREATE TABLE` statement. */
internal data class ColumnDefinition(
    val name: String,
    /** Declared type words joined by a space, e.g. "INTEGER", "VARCHAR(10)"; empty when none was declared. */
    val declaredType: String,
    /**
     * Value of rows whose record is shorter than the column list (rows written before `ALTER TABLE ADD COLUMN`):
     * the column's `DEFAULT` converted to the column's affinity, NULL when it has none.
     */
    val defaultValue: SqliteValue = SqliteValue.Null,
) {
    val affinity: Affinity get() = Affinity.of(declaredType)

    /** Columns with REAL affinity read integers as floating point values (SQLite stores whole numbers compactly). */
    val isRealAffinity: Boolean get() = affinity == Affinity.REAL
}

/** What the reader needs to know about a table, extracted from its `CREATE TABLE` statement. */
internal data class TableDefinition(
    val columns: List<ColumnDefinition>,
    /** Index of the column that is an alias of the rowid (`INTEGER PRIMARY KEY`), or -1. */
    val rowidAlias: Int,
    val withoutRowid: Boolean,
) {
    companion object {
        /** Parses [sql] (the `sql` column of `sqlite_schema`); throws [SqliteFormatException] when it has no column list. */
        fun parse(tableName: String, sql: String): TableDefinition = TableDefinitionParser(tableName, sql).parse()
    }
}

/**
 * Small, tolerant parser of `CREATE TABLE` statements. It understands quoted identifiers (`"x"`, `` `x` ``, `[x]`,
 * `'x'`), comments, nested parentheses (types, DEFAULT/CHECK expressions) and skips table constraints
 * (`CONSTRAINT`, `PRIMARY KEY`, `UNIQUE`, `CHECK`, `FOREIGN KEY`). Generated columns and column defaults are ignored.
 */
private class TableDefinitionParser(private val tableName: String, private val sql: String) {
    private enum class Kind { WORD, QUOTED, STRING, SYMBOL }

    private class Token(val kind: Kind, val text: String) {
        fun isWord(vararg words: String): Boolean = kind == Kind.WORD && words.any { it.equals(text, ignoreCase = true) }

        fun isSymbol(char: Char): Boolean = kind == Kind.SYMBOL && text[0] == char
    }

    fun parse(): TableDefinition {
        val tokens = tokenize()
        val open = tokens.indexOfFirst { it.isSymbol('(') }
        if (open < 0) throw SqliteFormatException("cannot find the column list in the definition of table '$tableName'")

        // Split the parenthesized list at top-level commas.
        val definitions = ArrayList<List<Token>>()
        var current = ArrayList<Token>()
        var depth = 1
        var index = open + 1
        while (index < tokens.size) {
            val token = tokens[index]
            if (token.isSymbol('(')) {
                depth++
            } else if (token.isSymbol(')')) {
                depth--
                if (depth == 0) break
            } else if (depth == 1 && token.isSymbol(',')) {
                definitions += current
                current = ArrayList()
                index++
                continue
            }
            current += token
            index++
        }
        if (depth != 0) throw SqliteFormatException("unbalanced parentheses in the definition of table '$tableName'")
        if (current.isNotEmpty()) definitions += current
        val trailing = tokens.subList(index + 1, tokens.size)
        val withoutRowid = trailing.any { it.isWord("WITHOUT") }

        val columns = ArrayList<ColumnDefinition>()
        var aliasColumn = -1
        var tablePrimaryKey: List<Token>? = null
        for (definition in definitions) {
            val first = definition.firstOrNull() ?: continue
            if (first.isWord("CONSTRAINT", "PRIMARY", "UNIQUE", "CHECK", "FOREIGN")) {
                if (tablePrimaryKey == null) tablePrimaryKey = primaryKeyConstraint(definition)
                continue
            }
            val type = ArrayList<String>()
            var position = 1
            while (position < definition.size) {
                val token = definition[position]
                if (token.kind == Kind.WORD && token.text.uppercase() !in CONSTRAINT_START) {
                    type += token.text
                    position++
                } else if (token.isSymbol('(') && type.isNotEmpty()) {
                    position = skipGroup(definition, position, type)
                } else {
                    break
                }
            }
            val declaredType = type.joinToString(" ")
            val constraints = definition.subList(position, definition.size)
            if (aliasColumn < 0 && type.size == 1 && type[0].equals("INTEGER", ignoreCase = true) &&
                isPrimaryKeyNotDescending(constraints)
            ) {
                aliasColumn = columns.size
            }
            if (isGenerated(constraints)) {
                throw SqliteFormatException("table '$tableName' has generated columns, which are not supported")
            }
            val affinity = Affinity.of(declaredType)
            columns += ColumnDefinition(first.text, declaredType, defaultValue(constraints, affinity))
        }
        if (aliasColumn < 0 && tablePrimaryKey != null) {
            val key = tablePrimaryKey
            // PRIMARY KEY ( name [COLLATE x] [ASC|DESC] ) with exactly one column.
            if (key.size >= 1 && key.none { it.isSymbol(',') } && key.none { it.isWord("DESC") }) {
                val target = key[0].text
                val found = columns.indexOfFirst { it.name.equals(target, ignoreCase = true) }
                if (found >= 0 && columns[found].declaredType.equals("INTEGER", ignoreCase = true)) aliasColumn = found
            }
        }
        return TableDefinition(columns, if (withoutRowid) -1 else aliasColumn, withoutRowid)
    }

    /** `GENERATED ALWAYS AS (…)` / `AS (…)`: such columns may not occupy a slot in the stored record. */
    private fun isGenerated(constraints: List<Token>): Boolean = constraints.indices.any { index ->
        val token = constraints[index]
        token.isWord("GENERATED") || (token.isWord("AS") && constraints.getOrNull(index + 1)?.isSymbol('(') == true)
    }

    /** The value after `DEFAULT` (outside parentheses), converted to [affinity]; NULL when absent or not a literal. */
    private fun defaultValue(constraints: List<Token>, affinity: Affinity): SqliteValue {
        var depth = 0
        for (index in constraints.indices) {
            val token = constraints[index]
            if (token.isSymbol('(')) depth++
            if (token.isSymbol(')')) depth--
            if (depth == 0 && token.isWord("DEFAULT")) {
                val literal = literal(constraints.subList(index + 1, constraints.size)) ?: return SqliteValue.Null
                return affinity.apply(literal.value)
            }
        }
        return SqliteValue.Null
    }

    /** A literal and the number of tokens it occupies. */
    private class Literal(val value: SqliteValue, val length: Int)

    /**
     * A constant at the start of [tokens]: number (optionally signed, decimal or hex), string, blob `x'…'`, NULL, TRUE,
     * FALSE, a bare word (SQLite takes it as a string) or one of these in parentheses; null for anything else
     * (expressions, `CURRENT_TIMESTAMP`, …).
     */
    private fun literal(tokens: List<Token>): Literal? {
        val first = tokens.firstOrNull() ?: return null
        if (first.isSymbol('(')) {
            var depth = 0
            for (index in tokens.indices) {
                if (tokens[index].isSymbol('(')) depth++
                if (tokens[index].isSymbol(')')) depth--
                if (depth == 0) {
                    val inner = literal(tokens.subList(1, index)) ?: return null
                    return if (inner.length == index - 1) Literal(inner.value, index + 1) else null // `(1 + 2)` is an expression
                }
            }
            return null
        }
        if (first.isSymbol('-') || first.isSymbol('+')) {
            tokens.getOrNull(1)?.takeIf { it.kind == Kind.WORD && (it.text[0].isDigit() || it.text[0] == '.') } ?: return null
            val (text, count) = withExponent(tokens, 1)
            return number(text, negative = first.isSymbol('-'))?.let { Literal(it, 1 + count) }
        }
        return when (first.kind) {
            Kind.STRING, Kind.QUOTED -> Literal(SqliteValue.Text(first.text), 1)
            Kind.SYMBOL -> null
            Kind.WORD -> when {
                first.isWord("NULL") -> Literal(SqliteValue.Null, 1)
                first.isWord("TRUE") -> Literal(SqliteValue.Integer(1), 1)
                first.isWord("FALSE") -> Literal(SqliteValue.Integer(0), 1)
                first.isWord("CURRENT_TIME", "CURRENT_DATE", "CURRENT_TIMESTAMP") -> null
                first.text.equals("x", ignoreCase = true) && tokens.getOrNull(1)?.kind == Kind.STRING ->
                    blob(tokens[1].text)?.let { Literal(it, 2) }
                first.text[0].isDigit() || first.text[0] == '.' -> {
                    val (text, count) = withExponent(tokens, 0)
                    number(text, negative = false)?.let { Literal(it, count) }
                }
                else -> Literal(SqliteValue.Text(first.text), 1)
            }
        }
    }

    /**
     * The number word at [index] and the number of tokens it takes: `1e+5` is tokenized as WORD `1e`, SYMBOL `+`,
     * WORD `5`, which is joined again.
     */
    private fun withExponent(tokens: List<Token>, index: Int): Pair<String, Int> {
        val word = tokens[index].text
        val sign = tokens.getOrNull(index + 1)
        val digits = tokens.getOrNull(index + 2)
        val endsWithExponent = word.length > 1 && word.last().let { it == 'e' || it == 'E' } && !word.startsWith("0x") && !word.startsWith("0X")
        return if (endsWithExponent && sign != null && (sign.isSymbol('-') || sign.isSymbol('+')) && digits?.kind == Kind.WORD) {
            (word + sign.text + digits.text) to 3
        } else {
            word to 1
        }
    }

    private fun number(text: String, negative: Boolean): SqliteValue? {
        if (text.startsWith("0x") || text.startsWith("0X")) {
            val digits = text.substring(2)
            if (digits.isEmpty() || digits.length > 16 || !digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
            val value = java.lang.Long.parseUnsignedLong(digits, 16)
            return SqliteValue.Integer(if (negative) -value else value)
        }
        return Affinity.parseNumber(if (negative) "-$text" else text)
    }

    private fun blob(hex: String): SqliteValue? {
        if (hex.length % 2 != 0 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return SqliteValue.Blob(ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() })
    }

    /** Index after the balanced `( … )` group starting at [start]; its text is appended to the last type word. */
    private fun skipGroup(tokens: List<Token>, start: Int, type: MutableList<String>): Int {
        var depth = 0
        var position = start
        val text = StringBuilder()
        while (position < tokens.size) {
            val token = tokens[position]
            if (token.isSymbol('(')) depth++
            if (token.isSymbol(')')) depth--
            text.append(token.text)
            position++
            if (depth == 0) break
        }
        type[type.size - 1] = type.last() + text
        return position
    }

    /** True when the column constraints contain `PRIMARY KEY` that is not followed by DESC (outside parentheses). */
    private fun isPrimaryKeyNotDescending(constraints: List<Token>): Boolean {
        var depth = 0
        for (index in constraints.indices) {
            val token = constraints[index]
            if (token.isSymbol('(')) depth++
            if (token.isSymbol(')')) depth--
            if (depth == 0 && token.isWord("PRIMARY") && constraints.getOrNull(index + 1)?.isWord("KEY") == true) {
                return constraints.getOrNull(index + 2)?.isWord("DESC") != true
            }
        }
        return false
    }

    /** Column list tokens of a table constraint `[CONSTRAINT x] PRIMARY KEY ( … )`, or null for other constraints. */
    private fun primaryKeyConstraint(definition: List<Token>): List<Token>? {
        var index = 0
        if (definition[0].isWord("CONSTRAINT")) index = 2 // CONSTRAINT <name>
        if (definition.getOrNull(index)?.isWord("PRIMARY") != true || definition.getOrNull(index + 1)?.isWord("KEY") != true) {
            return null
        }
        val open = index + 2
        if (definition.getOrNull(open)?.isSymbol('(') != true) return null
        val close = definition.indexOfLast { it.isSymbol(')') }
        if (close <= open) return null
        return definition.subList(open + 1, close)
    }

    private fun tokenize(): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c.isWhitespace() -> i++
                c == '-' && sql.startsWith("--", i) -> {
                    val end = sql.indexOf('\n', i)
                    i = if (end < 0) sql.length else end + 1
                }
                c == '/' && sql.startsWith("/*", i) -> {
                    val end = sql.indexOf("*/", i + 2)
                    i = if (end < 0) sql.length else end + 2
                }
                c == '"' || c == '`' -> i = quoted(tokens, i, c, Kind.QUOTED)
                c == '\'' -> i = quoted(tokens, i, '\'', Kind.STRING)
                c == '[' -> {
                    val end = sql.indexOf(']', i + 1)
                    val stop = if (end < 0) sql.length else end
                    tokens += Token(Kind.QUOTED, sql.substring(i + 1, stop))
                    i = stop + 1
                }
                c.isLetterOrDigit() || c == '_' || c == '$' || c == '.' || c.code > 127 -> {
                    var end = i + 1
                    while (end < sql.length && (sql[end].isLetterOrDigit() || sql[end] == '_' || sql[end] == '$' ||
                            sql[end] == '.' || sql[end].code > 127)
                    ) {
                        end++
                    }
                    tokens += Token(Kind.WORD, sql.substring(i, end))
                    i = end
                }
                else -> {
                    tokens += Token(Kind.SYMBOL, c.toString())
                    i++
                }
            }
        }
        return tokens
    }

    /** Reads a quoted token starting at [start] (doubled quote characters are escapes); returns the next index. */
    private fun quoted(tokens: MutableList<Token>, start: Int, quote: Char, kind: Kind): Int {
        val text = StringBuilder()
        var i = start + 1
        while (i < sql.length) {
            if (sql[i] == quote) {
                if (i + 1 < sql.length && sql[i + 1] == quote) {
                    text.append(quote)
                    i += 2
                    continue
                }
                i++
                break
            }
            text.append(sql[i])
            i++
        }
        tokens += Token(kind, text.toString())
        return i
    }

    private companion object {
        /** Keywords that end the declared type of a column and start its constraints. */
        val CONSTRAINT_START = setOf(
            "CONSTRAINT", "PRIMARY", "NOT", "NULL", "UNIQUE", "CHECK", "DEFAULT", "COLLATE", "REFERENCES", "GENERATED", "AS",
        )
    }
}
