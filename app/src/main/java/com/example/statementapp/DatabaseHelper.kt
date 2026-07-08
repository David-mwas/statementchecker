package com.example.statementapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class RequestLog(
    val id: Int = 0,
    val timestamp: Long,
    val latencyMs: Long,
    val status: String
)

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_VERSION = 1
        private const val DATABASE_NAME = "StatementTracker.db"
        const val TABLE_LOGS = "request_logs"
        const val COLUMN_ID = "_id"
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_LATENCY = "latency"
        const val COLUMN_STATUS = "status"
        const val PAGE_SIZE = 20
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = ("CREATE TABLE " + TABLE_LOGS + "("
                + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + COLUMN_TIMESTAMP + " INTEGER,"
                + COLUMN_LATENCY + " INTEGER,"
                + COLUMN_STATUS + " TEXT" + ")")
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_LOGS")
        onCreate(db)
    }

    fun insertLog(log: RequestLog) {
        val db = this.writableDatabase
        val values = ContentValues()
        values.put(COLUMN_TIMESTAMP, log.timestamp)
        values.put(COLUMN_LATENCY, log.latencyMs)
        values.put(COLUMN_STATUS, log.status)
        db.insert(TABLE_LOGS, null, values)
        // Note: do NOT call db.close() here — SQLiteOpenHelper manages the connection lifecycle
    }

    fun clearAllLogs() {
        val db = this.writableDatabase
        db.execSQL("DELETE FROM $TABLE_LOGS")
    }

    fun getAllLogs(): List<RequestLog> {
        val logList = ArrayList<RequestLog>()
        val selectQuery = "SELECT * FROM $TABLE_LOGS ORDER BY $COLUMN_TIMESTAMP DESC"
        val db = this.readableDatabase
        val cursor = db.rawQuery(selectQuery, null)
        if (cursor.moveToFirst()) {
            do {
                logList.add(RequestLog(
                    id = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
                    latencyMs = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_LATENCY)),
                    status = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_STATUS))
                ))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return logList
    }

    /** Load a page of logs. Pass statusFilter = null for all statuses. */
    fun getLogsPage(offset: Int, limit: Int = PAGE_SIZE, statusFilter: String? = null): List<RequestLog> {
        val logList = ArrayList<RequestLog>()
        val where = if (statusFilter != null) "WHERE $COLUMN_STATUS = ?" else ""
        val args = if (statusFilter != null) arrayOf(statusFilter) else null
        val query = "SELECT * FROM $TABLE_LOGS $where ORDER BY $COLUMN_TIMESTAMP DESC LIMIT $limit OFFSET $offset"
        val db = this.readableDatabase
        val cursor = db.rawQuery(query, args)
        if (cursor.moveToFirst()) {
            do {
                logList.add(RequestLog(
                    id = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
                    latencyMs = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_LATENCY)),
                    status = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_STATUS))
                ))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return logList
    }

    /** Total count, optionally filtered by status. */
    fun getLogCount(statusFilter: String? = null): Int {
        val db = this.readableDatabase
        val where = if (statusFilter != null) "WHERE $COLUMN_STATUS = ?" else ""
        val args = if (statusFilter != null) arrayOf(statusFilter) else null
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_LOGS $where", args)
        val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        return count
    }

    /** Search logs by date string — does a LIKE on a formatted timestamp range. */
    fun searchLogs(query: String, statusFilter: String? = null): List<RequestLog> {
        // Load all logs matching filter then search in Kotlin (fast enough for typical log sizes)
        val source = if (statusFilter != null)
            getLogsPage(0, 5000, statusFilter)
        else
            getAllLogs()
        val q = query.lowercase()
        return source.filter {
            it.status.lowercase().contains(q) ||
            java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(it.timestamp)).lowercase().contains(q)
        }
    }

}
