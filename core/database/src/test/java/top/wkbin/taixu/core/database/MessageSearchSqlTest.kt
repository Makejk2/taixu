package top.wkbin.taixu.core.database

import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨会话消息全文搜索的 SQL 契约。与 [HarnessDailyAggregateSqlTest] 同因：
 * Robolectric 自带 SQLite 未编 JSON1，无法执行 Room 的 json_extract 查询，
 * 这里用带 JSON1 的 sqlite-jdbc 执行与 `HarnessBranchQueries.searchMessagesAcrossSessions`
 * 相同的 SQL 文本（改 DAO 的 SQL 时必须同步本文件，反之亦然）。
 */
class MessageSearchSqlTest {

    @Test
    fun `hits user and assistant text across sessions and excludes tool payloads`() {
        connection().use { conn ->
            insert(conn, "m1", "sessionA", "user", """{"type":"user","text":"太墟支持 PRoot 沙箱"}""", 10, sessionTitle = "会话甲")
            insert(conn, "m2", "sessionB", "assistant", """{"type":"assistant","text":"这里是关于 PRoot 的说明"}""", 20, sessionTitle = "会话乙")
            insert(conn, "m3", "sessionA", "tool_call", """{"type":"tool_call","args":{"cmd":"PRoot 出现在工具参数里"}}""", 30)
            val hits = search(conn, "PRoot")
            assertEquals(2, hits.size)
            // 最新优先
            assertEquals("sessionB", hits[0][0])
            assertEquals(0, (hits[0][3] as Number).toInt())
            assertEquals("会话甲", hits[1][1])
            assertEquals(1, (hits[1][3] as Number).toInt())
            // excerpt 窗口包含命中词
            assertTrue(hits.all { (it[5] as String).contains("PRoot") })
        }
    }

    @Test
    fun `like wildcards must be escaped by the caller`() {
        connection().use { conn ->
            insert(conn, "m1", "s", "user", """{"type":"user","text":"一百分之百匹配任意词"}""", 1)
            // 转义约定与 RoomHarnessRuntimeRepository.searchMessages 相同
            assertEquals(0, search(conn, ESCAPE("%")).size)
            assertEquals(0, search(conn, ESCAPE("_")).size)
            assertEquals(1, search(conn, ESCAPE("百分之百")).size)
        }
    }

    @Test
    fun `long text excerpt windows around the match`() {
        connection().use { conn ->
            val text = "前".repeat(500) + "关键词出现" + "后".repeat(500)
            insert(conn, "m1", "s", "user", """{"type":"user","text":"$text"}""", 1)
            val hits = search(conn, "关键词")
            assertEquals(1, hits.size)
            val excerpt = hits[0][5] as String
            assertTrue(excerpt.contains("关键词"))
            assertTrue(excerpt.length <= 240)
        }
    }

    @Test
    fun `limit keeps newest first`() {
        connection().use { conn ->
            (1..10).forEach { i -> insert(conn, "m$i", "s", "user", """{"type":"user","text":"正文命中$i"}""", i.toLong()) }
            val hits = search(conn, "正文", limit = 3)
            assertEquals(3, hits.size)
            assertEquals(10L, (hits[0][4] as Number).toLong())
            assertEquals(8L, (hits[2][4] as Number).toLong())
        }
    }

    private fun connection() = DriverManager.getConnection("jdbc:sqlite::memory:").also { conn ->
        conn.createStatement().use { stmt ->
            SCHEMA.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach(stmt::execute)
        }
    }

    private fun insert(
        conn: java.sql.Connection,
        id: String,
        sessionId: String,
        customType: String,
        payload: String,
        createdAt: Long,
        sessionTitle: String? = null,
    ) {
        if (sessionTitle != null) {
            conn.prepareStatement("INSERT INTO harness_sessions(id, title) VALUES (?,?)").use {
                it.setString(1, sessionId); it.setString(2, sessionTitle); it.executeUpdate()
            }
        }
        conn.prepareStatement(
            "INSERT INTO harness_entries(id, sessionId, createdAt, entryType, customType, payloadJson) VALUES (?,?,?,?,?,?)",
        ).use { stmt ->
            stmt.setString(1, id); stmt.setString(2, sessionId); stmt.setLong(3, createdAt)
            stmt.setString(4, "message"); stmt.setString(5, customType); stmt.setString(6, payload)
            stmt.executeUpdate()
        }
    }

    private fun search(conn: java.sql.Connection, query: String, limit: Int = 30): List<List<Any?>> =
        conn.prepareStatement(SEARCH_SQL).use { stmt ->
            stmt.setString(1, query); stmt.setString(2, query); stmt.setInt(3, limit)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add((1..6).map { rs.getObject(it) })
                }
            }
        }

    private companion object {
        /** LIKE 转义约定与 RoomHarnessRuntimeRepository.searchMessages 逐字一致。 */
        private val ESCAPE: (String) -> String = { q ->
            q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        }

        private val SCHEMA = """
            CREATE TABLE harness_entries (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                id TEXT NOT NULL,
                sessionId TEXT NOT NULL,
                parentId TEXT,
                createdAt INTEGER NOT NULL,
                entryType TEXT NOT NULL,
                customType TEXT,
                payloadJson TEXT NOT NULL
            );
            CREATE TABLE harness_sessions (id TEXT PRIMARY KEY, title TEXT);
        """.trimIndent()

        /** 与 HarnessBranchQueries.searchMessagesAcrossSessions 的 @Query 保持逐字一致。 */
        private val SEARCH_SQL = """
            SELECT sessionId, sessionTitle, messageId, isUser, createdAt,
                   substr(fullText, MAX(1, instr(fullText, ?) - 48), 220) AS excerpt
            FROM (
                SELECT e.sessionId AS sessionId, s.title AS sessionTitle, e.id AS messageId,
                       e.customType = 'user' AS isUser, e.createdAt AS createdAt,
                       json_extract(e.payloadJson, '${'$'}.text') AS fullText
                FROM harness_entries e
                LEFT JOIN harness_sessions s ON s.id = e.sessionId
                WHERE e.entryType = 'message' AND e.customType IN ('user', 'assistant')
                  AND json_extract(e.payloadJson, '${'$'}.text') LIKE '%' || ? || '%' ESCAPE '\'
                ORDER BY e.createdAt DESC LIMIT ?
            )
        """.trimIndent()
    }
}
