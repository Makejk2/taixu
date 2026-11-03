package top.wkbin.taixu.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 83→84：MCP OAuth 凭据与事务表增加 client_secret 密文列（RFC 7591 动态注册可返回机密客户端凭据）。 */
val MIGRATION_83_84 = object : Migration(83, 84) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE mcp_oauth_credentials ADD COLUMN clientSecretCiphertext TEXT")
        db.execSQL("ALTER TABLE mcp_oauth_transactions ADD COLUMN clientSecretCiphertext TEXT")
    }
}
