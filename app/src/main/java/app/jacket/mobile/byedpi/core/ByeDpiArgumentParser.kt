package app.jacket.mobile.byedpi.core

internal object ByeDpiArgumentParser {
    private val blockedOptions = setOf(
        "-h", "--help", "-v", "--version",
        "-D", "--daemon", "-w", "--pidfile",
        "-E", "--transparent", "-i", "--ip", "-p", "--port"
    )

    fun build(strategy: String): Array<String> {
        val tokens = shellSplit(strategy)
        require(tokens.size <= 128) { "Слишком много параметров стратегии" }
        require(tokens.none { it in blockedOptions ||
            it.startsWith("--ip=") || it.startsWith("--port=") ||
            it.startsWith("-i") || it.startsWith("-p") && it.length > 2
        }) {
            "IP и порт прокси задаются приложением; параметры -i/-p, --daemon и --transparent запрещены."
        }
        require(tokens.all { token ->
            token.isNotEmpty() &&
                token.length <= 4096 &&
                '\u0000' !in token
        }) {
            "Параметр стратегии пустой, слишком длинный или содержит недопустимый символ"
        }

        return (listOf("ciadpi", "--ip", "127.0.0.1", "--port", "1080") + tokens).toTypedArray()
    }

    private fun shellSplit(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false

        input.forEach { char ->
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }
                char == '\\' && quote != null -> escaped = true
                quote != null && char == quote -> quote = null
                quote == null && (char == '"' || char == '\'') -> quote = char
                quote == null && char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(char)
            }
        }

        require(quote == null) { "Не закрыты кавычки в стратегии" }
        require(!escaped) { "Незавершённое экранирование в стратегии" }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }
}
