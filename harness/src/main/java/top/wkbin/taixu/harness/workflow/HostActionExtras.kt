package top.wkbin.taixu.harness.workflow

import top.wkbin.taixu.core.common.shell.ShellQuote

/** 纯 shell 参数拼装：从 HostActionNodeExecutor 抽离以符合架构棘轮；行为不变。 */
internal fun parseBool(raw: String): Boolean =
    raw.equals("true", true) || raw == "1" || raw.equals("on", true) || raw.equals("yes", true)

internal fun appendExtras(extras: Map<String, String>): StringBuilder = StringBuilder().apply {
    extras.forEach { (rawKey, value) ->
        val parts = rawKey.split(':', limit = 2)
        val (key, type) = when {
            parts.size == 2 && parts[1] in setOf("int", "long", "bool", "boolean", "float", "uri", "string") ->
                parts[0] to parts[1]
            parts.size == 2 && parts[0] in setOf("int", "long", "bool", "boolean", "float", "uri", "string") ->
                parts[1] to parts[0]
            else -> rawKey to "string"
        }
        when (type) {
            "int" -> append(" --ei ${ShellQuote.of(key)} ${value.toInt()}")
            "long" -> append(" --el ${ShellQuote.of(key)} ${value.toLong()}")
            "bool", "boolean" -> append(" --ez ${ShellQuote.of(key)} ${parseBool(value)}")
            "float" -> append(" --ef ${ShellQuote.of(key)} ${value.toFloat()}")
            "uri" -> append(" --eu ${ShellQuote.of(key)} ${ShellQuote.of(value)}")
            else -> append(" --es ${ShellQuote.of(key)} ${ShellQuote.of(value)}")
        }
    }
}
