package com.agrios.app.core.sync

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.agrios.app.core.database.AppDatabase
import com.almworks.sqlite4java.SQLiteConnection
import com.almworks.sqlite4java.SQLiteConstants
import com.almworks.sqlite4java.SQLiteException
import com.almworks.sqlite4java.SQLiteStatement
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.util.SQLiteLibraryLoader

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class V10FixtureConstructionHostTest {
    @Test fun createsAndValidatesPinnedV10WithoutMigration() {
        assumeTrue("V10_CONSTRUCTION_NOT_ENABLED",
            System.getenv("OWNERSHIP_V10_CONSTRUCTION") == "APPROVED_SYNTHETIC_V10_CONSTRUCTION")
        withValidatedV10("v10-construction-") { _, _, _, _ -> }
        println("V10_CONSTRUCTION_VERIFIED version=10 entity_tables=12 entity_rows=14 values=172 retained_case_files=0")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class V10ToV11HydrationMigrationHostTest {
    @Test fun upgradesExistingV10AndPreservesAllRows() {
        assumeTrue("V10_V11_MIGRATION_NOT_ENABLED",
            System.getenv("OWNERSHIP_V10_TO_V11_MIGRATION") == "APPROVED_SYNTHETIC_V10_TO_V11_MIGRATION")
        withValidatedV10("v10-to-v11-") { app, file, tables, before ->
            val names = tables.map { it.name }.toSet() + cacheTables
            val upgraded = inspectV11(app, file, upgrade = true) { db ->
                verifyV11(db, tables, before, names)
            }
            val fresh = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
            try {
                val db = fresh.openHelper.writableDatabase
                check(db.version == 11 && v11Shape(db, names) == upgraded) { "V11_FRESH_SCHEMA_MISMATCH" }
            } finally {
                fresh.close()
            }
            inspectV11(app, file, upgrade = false) { db ->
                check(verifyV11(db, tables, before, names) == upgraded) { "V11_REOPEN_SCHEMA_MISMATCH" }
            }
        }
        println("V10_V11_MIGRATION_VERIFIED historical_tables=12 rows=14 values=172 empty_cache_tables=3 reopens=1 retained_case_files=0")
    }
}

private fun withValidatedV10(prefix: String, use: (Application, Path, List<Table>, Snapshot) -> Unit) {
        val app: Application = RuntimeEnvironment.getApplication()
        require10(app.javaClass == Application::class.java)
        val cwd = Paths.get("").toRealPath()
        val module = directory(if (cwd.fileName.toString() == "app") cwd else cwd.resolve("app"))
        val baseline = module.parent.resolve("docs/android-room-schema-baselines/v10")
        val tables = schema(pinned(baseline.resolve("com.agrios.app.core.database.AppDatabase/10.json"), SCHEMA_HASH))
        val inserts = recipe(pinned(baseline.resolve("synthetic-fixture-recipe.md"), RECIPE_HASH))
        checkInputs(tables, inserts)
        checkInputs(tables, V10FixtureExpected.rows)
        // Direct sqlite4java opens bypass the legacy Android SQLite shadow's loader call.
        try {
            SQLiteLibraryLoader.load()
        } catch (_: Exception) {
            error("V10_SQLITE_LIBRARY_INIT_FAILED")
        } catch (_: LinkageError) {
            error("V10_SQLITE_LIBRARY_INIT_FAILED")
        }
        val root = directory(module.resolve("build")).resolve("ownership-room-db")
        require10(Paths.get(System.getProperty("ownership.room.root", "")).toAbsolutePath().normalize() == root)
        if (!Files.exists(root, NOFOLLOW_LINKS)) Files.createDirectory(root)
        directory(root)
        val dir = Files.createTempDirectory(root, prefix)
        val file = dir.resolve(DB)
        try {
            diagnostic("CASE_PREPARE") {
                require10(inventory(root, dir).isEmpty())
                // Neither engine open below can recreate a missing target.
                Files.createFile(file)
            }
            createV10(file, tables, inserts)
            diagnostic("REOPEN_INVENTORY") { require10(file in inventory(root, dir)) }
            val readOnly = SQLiteConnection(file.toFile())
            var before: Snapshot? = null
            withConfirmedClose("REOPEN_CLOSE", readOnly) {
                diagnostic("REOPEN") {
                    readOnly.openReadonly()
                    require10(readOnly.isReadOnly() && file.toRealPath() == file)
                }
                diagnostic("VALIDATE") { validate(readOnly, tables) }
                before = v10Snapshot(tables) { sql ->
                    readOnly.query(sql) { q ->
                        buildList {
                            while (q.step()) add(List(q.columnCount()) { i ->
                                when (q.columnType(i)) {
                                    SQLiteConstants.SQLITE_NULL -> null
                                    SQLiteConstants.SQLITE_INTEGER -> q.columnLong(i)
                                    SQLiteConstants.SQLITE_FLOAT -> q.columnDouble(i).toRawBits()
                                    SQLiteConstants.SQLITE_TEXT -> q.columnString(i)
                                    else -> error("V10_SNAPSHOT_STORAGE_TYPE")
                                }
                            })
                        }
                    }
                }
            }
            diagnostic("HANDOFF") { use(app, file, tables, checkNotNull(before)) }
        } catch (e: Exception) {
            // Keep the case on any construction, inspection or closure failure.
            throw if (e is FixtureDiagnostic) e else FixtureDiagnostic("UNCLASSIFIED", "OTHER")
        }
        try {
            inventory(root, dir).forEach { Files.delete(it) }
            Files.delete(dir)
        } catch (_: Exception) {
            error("V10_CASE_CLEANUP_INCOMPLETE")
        }
}

private const val DB = "synthetic-v10.db"
private typealias Snapshot = Map<String, List<List<Any?>>>
private val cacheTables = setOf("self_profile_cache_farmer", "self_profile_cache_parcel", "self_profile_cache_soil")

private fun v10Snapshot(tables: List<Table>, query: (String) -> List<List<Any?>>): Snapshot =
    tables.associate { table ->
        val fields = table.fields.joinToString { field ->
            val c = quote(field.name)
            "typeof($c), CASE WHEN typeof($c)='text' THEN hex(CAST($c AS BLOB)) ELSE $c END"
        }
        table.name to query("SELECT $fields FROM ${quote(table.name)} ORDER BY ${quote(table.key)} COLLATE BINARY")
    }

private fun SupportSQLiteDatabase.cells(sql: String): List<List<Any?>> = query(sql).use { q ->
    buildList {
        while (q.moveToNext()) add(List(q.columnCount) { i ->
            when (q.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> q.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> q.getDouble(i).toRawBits()
                Cursor.FIELD_TYPE_STRING -> q.getString(i)
                else -> error("V11_SNAPSHOT_STORAGE_TYPE")
            }
        })
    }
}

private fun <T> inspectV11(app: Application, file: Path, upgrade: Boolean, inspect: (SupportSQLiteDatabase) -> T): T {
    val dir = file.parent
    val root = dir.parent
    fun checkExisting() = require10(file in inventory(root, dir))
    checkExisting()
    val context = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun getCacheDir() = directory(dir).toFile()
        override fun getDatabasePath(name: String): java.io.File {
            require10(name == DB)
            checkExisting()
            return file.toFile()
        }
        override fun deleteDatabase(name: String): Boolean = error("V11_DELETE_FORBIDDEN")
    }
    var callbackFailed = false
    var migrated = false
    fun guarded(action: () -> Unit) {
        check(!callbackFailed) { "V11_PRIOR_CALLBACK_FAILURE" }
        try {
            action()
        } catch (e: Exception) {
            callbackFailed = true
            throw e
        }
    }
    val room = Room.databaseBuilder(context, AppDatabase::class.java, DB)
        .addMigrations(AppDatabase.MIGRATION_10_11)
        .openHelperFactory { config ->
            val delegate = config.callback
            require10(config.name == DB && delegate.version == 11)
            val callback = object : SupportSQLiteOpenHelper.Callback(delegate.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = guarded {
                    // FrameworkSQLiteOpenHelper reconfigures after the upgrade
                    // transaction commits and its connection is reopened.
                    val expectedVersion = if (upgrade && !migrated) 10 else 11
                    require10(Paths.get(checkNotNull(db.path)).toRealPath() == file &&
                        db.version == expectedVersion)
                    delegate.onConfigure(db)
                }
                override fun onCreate(db: SupportSQLiteDatabase) = guarded { error("V11_CREATE_FORBIDDEN") }
                override fun onCorruption(db: SupportSQLiteDatabase) = guarded { error("V11_CORRUPTION_FORBIDDEN") }
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    guarded { error("V11_DOWNGRADE_FORBIDDEN") }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = guarded {
                    require10(upgrade && !migrated && oldVersion == 10 && newVersion == 11 && db.inTransaction())
                    delegate.onUpgrade(db, oldVersion, newVersion)
                    migrated = true
                }
                override fun onOpen(db: SupportSQLiteDatabase) = guarded {
                    require10(migrated == upgrade)
                    delegate.onOpen(db)
                }
            }
            FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB).callback(callback).noBackupDirectory(false).allowDataLossOnRecovery(false).build())
        }.build()
    return try {
        val db = room.openHelper.writableDatabase
        require10(!callbackFailed && migrated == upgrade && db.version == 11)
        inspect(db)
    } finally {
        room.close()
        require10(!room.isOpen)
    }
}

private fun verifyV11(db: SupportSQLiteDatabase, tables: List<Table>, before: Snapshot, names: Set<String>): Snapshot {
    check(v10Snapshot(tables, db::cells) == before) { "V11_RETAINED_FIELDS_CHANGED" }
    for (table in cacheTables) {
        check(db.cells("SELECT count(*) FROM ${quote(table)}") == listOf(listOf(0L))) { "V11_CACHE_NOT_EMPTY" }
    }
    return v11Shape(db, names)
}

private fun v11Shape(db: SupportSQLiteDatabase, names: Set<String>): Snapshot {
    val metadata = db.cells("SELECT id, identity_hash FROM room_master_table")
    require10(metadata.size == 1 && metadata[0][0] == 42L && metadata[0][1] is String && metadata[0][1] != IDENTITY)
    val objects = db.cells("SELECT type,name,tbl_name FROM sqlite_master ORDER BY type,name")
        .filterNot { it == listOf("table", "android_metadata", "android_metadata") }
    require10(objects.filter { it[0] == "table" }.map { it[1] }.toSet() == names + "room_master_table" &&
        objects.all { it[0] == "table" || it[0] == "index" })
    return buildMap {
        put("objects", objects)
        put("identity", metadata)
        for (table in names) {
            val name = quote(table)
            put("columns:$table", db.cells("PRAGMA table_info($name)"))
            put("foreign:$table", db.cells("PRAGMA foreign_key_list($name)"))
            val indexes = db.cells("PRAGMA index_list($name)").map { it.drop(1) }.sortedBy { it[0] as String }
            put("indexes:$table", indexes)
            for (index in indexes) {
                val indexName = index[0] as String
                put("index:$indexName", db.cells("PRAGMA index_xinfo(${quote(indexName)})"))
            }
        }
    }
}

private const val SCHEMA_HASH = "EB8C48552F9C665B6B191697E2A6D547BC4C179246CDD3DE1A3864FA0228ACC6"
private const val RECIPE_HASH = "A8A8E523CC34BE105B5F82E411A56A375A934CFD2313E51D7E92E1BCF4A6FEAA"
private const val IDENTITY = "3c4ff69c36c5a4f10fa8050c0c7f8ff3"
private const val TOKEN = "\${TABLE_NAME}"
private const val MASTER_CREATE = "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)"
private fun require10(ok: Boolean) { check(ok) { "V10_FIXTURE_CHECK_FAILED" } }
private class FixtureDiagnostic(stage: String, kind: String) :
    IllegalStateException("V10_${stage}_${kind}_CASE_RETAINED")

private inline fun <T> diagnostic(stage: String, block: () -> T): T = try {
    block()
} catch (e: FixtureDiagnostic) {
    throw e
} catch (e: Exception) {
    val kind = if (e is SQLiteException) when (e.baseErrorCode) {
        SQLiteConstants.SQLITE_CANTOPEN -> "SQLITE_CANTOPEN"
        SQLiteConstants.SQLITE_CONSTRAINT -> "SQLITE_CONSTRAINT"
        SQLiteConstants.SQLITE_ERROR -> "SQLITE_ERROR"
        SQLiteConstants.SQLITE_IOERR -> "SQLITE_IOERR"
        SQLiteConstants.SQLITE_CORRUPT -> "SQLITE_CORRUPT"
        else -> "SQLITE_OTHER"
    } else if (e is IllegalStateException) "CHECK_FAILED" else "OTHER"
    // Deliberately do not attach the exception: it may contain paths or SQL.
    throw FixtureDiagnostic(stage, kind)
}

private inline fun withConfirmedClose(stage: String, db: SQLiteConnection, block: () -> Unit) {
    var primary: FixtureDiagnostic? = null
    try {
        block()
    } catch (e: Exception) {
        val safe = if (e is FixtureDiagnostic) e else FixtureDiagnostic("UNCLASSIFIED", "OTHER")
        primary = safe
        throw safe
    } finally {
        try {
            diagnostic(stage) {
                db.dispose()
                require10(db.isDisposed && !db.isOpen)
            }
        } catch (close: FixtureDiagnostic) {
            val first = primary ?: throw close
            first.addSuppressed(close)
        }
    }
}
private fun directory(path: Path): Path {
    require10(Files.isDirectory(path, NOFOLLOW_LINKS) && path.toRealPath() == path)
    return path
}
private fun inventory(root: Path, dir: Path): List<Path> {
    require10(directory(dir).parent == directory(root))
    val allowed = setOf(DB, "$DB-journal", "$DB-wal", "$DB-shm", "$DB.lck")
    return Files.newDirectoryStream(dir).use { it.toList() }.onEach {
        require10(it.fileName.toString() in allowed && Files.isRegularFile(it, NOFOLLOW_LINKS) && it.toRealPath() == it)
    }
}
private fun hex10(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it.toInt() and 255) }
private fun pinned(path: Path, hash: String): String {
    require10(Files.isRegularFile(path, NOFOLLOW_LINKS) && path.toRealPath() == path && Files.size(path) in 1L..65536L)
    val bytes = Files.newInputStream(path).use { it.readNBytes(65537) }
    require10(bytes.size <= 65536)
    // Recover the committed LF bytes on Windows checkouts with Git CRLF conversion.
    val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().replace("\r\n", "\n")
    require10('\r' !in text && hex10(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))) == hash)
    return text
}
private fun quote(name: String): String {
    require10(name.matches(Regex("[a-z][a-z0-9_]*")))
    return "`$name`"
}
private fun JSONArray.items() = (0 until length()).map { get(it) }
private data class Field(val name: String, val type: String, val required: Boolean, val default: String?)
private data class Index(val name: String, val unique: Boolean, val keys: List<String>, val sql: String)
private data class Table(val name: String, val fields: List<Field>, val key: String, val indexes: List<Index>, val sql: String)

private fun schema(source: String): List<Table> {
    val artifact = JSONObject(source)
    val db = artifact.getJSONObject("database")
    require10(artifact.getInt("formatVersion") == 1 && db.getInt("version") == 10 &&
        db.getString("identityHash") == IDENTITY && db.getJSONArray("views").length() == 0 &&
        db.getJSONArray("setupQueries").items() == listOf(MASTER_CREATE,
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$IDENTITY')"))
    val tables = db.getJSONArray("entities").items().map {
        val table = it as JSONObject
        val name = table.getString("tableName")
        quote(name)
        val fields = table.getJSONArray("fields").items().map { f ->
            val field = f as JSONObject
            val column = field.getString("columnName")
            val default = field.optString("defaultValue").takeIf { field.has("defaultValue") }
            val type = field.getString("affinity")
            require10(type in setOf("TEXT", "INTEGER", "REAL") && default ==
                if ((name == "auth_state" && column == "reauth_required") ||
                    (name == "sync_queue" && column == "dispatch_reserved")) "0" else null)
            Field(column, type, field.getBoolean("notNull"), default)
        }
        val primary = table.getJSONObject("primaryKey")
        val key = primary.getJSONArray("columnNames").items().single() as String
        require10(!primary.getBoolean("autoGenerate") && fields.count { it.name == key && it.required } == 1 &&
            table.getJSONArray("foreignKeys").length() == 0 && fields.map { it.name }.toSet().size == fields.size)
        val columns = fields.joinToString { "${quote(it.name)} ${it.type}" +
            (if (it.required) " NOT NULL" else "") + (it.default?.let { d -> " DEFAULT $d" } ?: "") }
        val ddl = "CREATE TABLE IF NOT EXISTS `$TOKEN` ($columns, PRIMARY KEY(${quote(key)}))"
        require10(ddl == table.getString("createSql"))
        val indexes = table.getJSONArray("indices").items().map { i ->
            val index = i as JSONObject
            val indexName = index.getString("name")
            val unique = index.getBoolean("unique")
            val keys = index.getJSONArray("columnNames").items().map { it as String }
            require10(keys.isNotEmpty() && keys.all { k -> fields.any { it.name == k } } &&
                index.getJSONArray("orders").length() == 0)
            val sql = "CREATE ${if (unique) "UNIQUE " else ""}INDEX IF NOT EXISTS ${quote(indexName)} ON `$TOKEN` (${keys.joinToString(transform = ::quote)})"
            require10(sql == index.getString("createSql"))
            Index(indexName, unique, keys, sql.replace(TOKEN, name))
        }
        Table(name, fields, key, indexes, ddl.replace(TOKEN, name))
    }
    require10(tables.size == 12 && tables.map { it.name }.toSet() == V10FixtureExpected.rows.keys &&
        tables.sumOf { it.fields.size } == 136 && tables.flatMap { it.indexes }.let {
            it.size == 20 && it.map { index -> index.name }.toSet().size == 20
        })
    return tables
}

private fun literal(source: String): Any? {
    val s = source.trim()
    return when {
        s == "NULL" -> null
        s.startsWith('"') -> (JSONTokener(s).nextValue() as String)
        s.matches(Regex("-?[0-9]+")) -> s.toLong()
        s.matches(Regex("-?[0-9]+\\.[0-9]+")) -> s.toDouble()
        else -> error("V10_BAD_LITERAL")
    }
}
private fun recipe(text: String): Map<String, List<Map<String, Any?>>> {
    val result = linkedMapOf<String, List<Map<String, Any?>>>()
    val rows = text.lineSequence().filter { it.startsWith("| ") }.map { line ->
        line.trim().removePrefix("|").removeSuffix("|").split('|').map(String::trim)
    }.toList()
    for ((name, values) in rows.filter { it.size == 2 && it[0] in V10FixtureExpected.rows.keys }) {
        val fields = values.removeSuffix(".").split("; ").map { it.split('=', limit = 2) }
        require10(fields.map { it[0] }.toSet().size == fields.size && name !in result)
        result[name] = listOf(fields.associate { it[0] to literal(it[1]) })
    }
    val headers = rows.single { it.first() == "event_id" }
    val common = Regex("""All three queue rows have entity_type=(.*), entity_id=(.*),\s+operation=(.*), max_retries=([0-9]+) and priority=(.*)\.""")
        .find(text)?.groupValues ?: error("V10_QUEUE_COMMON_MISSING")
    result["sync_queue"] = rows.filter { it.first().startsWith("\"fixture-") }.map { fields ->
        require10(fields.size == headers.size)
        headers.zip(fields.map(::literal)).toMap() + listOf("entity_type", "entity_id", "operation", "max_retries", "priority")
            .zip(common.drop(1).map(::literal)).toMap()
    }
    return result
}

private fun checkInputs(tables: List<Table>, rows: Map<String, List<Map<String, Any?>>>) {
    require10(rows.keys == tables.map { it.name }.toSet() &&
        tables.sumOf { it.fields.size * rows.getValue(it.name).size } == 172)
    for (table in tables) {
        val list = rows.getValue(table.name)
        require10(list.size == if (table.name == "sync_queue") 3 else 1)
        require10(list.map { it.getValue(table.key) }.toSet().size == list.size)
        for (row in list) {
            require10(row.keys.all { k -> table.fields.any { it.name == k } })
            for (field in table.fields) require10(when (val value = row[field.name]) {
                null -> !field.required
                is String -> field.type == "TEXT"
                is Long -> field.type == "INTEGER"
                is Double -> field.type == "REAL" && value.isFinite()
                else -> false
            })
        }
    }
}

private inline fun <T> SQLiteConnection.query(sql: String, read: (SQLiteStatement) -> T): T {
    val q = prepare(sql)
    return try { read(q) } finally { q.dispose() }
}

private fun createV10(file: Path, tables: List<Table>, rows: Map<String, List<Map<String, Any?>>>) {
    diagnostic("CREATE_TARGET") { require10(Files.isRegularFile(file, NOFOLLOW_LINKS) && file.toRealPath() == file) }
    val db = SQLiteConnection(file.toFile())
    withConfirmedClose("CREATE_CLOSE", db) {
        diagnostic("CREATE_OPEN") {
            db.open(false)
            require10(db.isOpen && !db.isReadOnly() && file.toRealPath() == file)
        }
        diagnostic("CREATE_BEGIN") { db.exec("BEGIN IMMEDIATE") }
        for (table in tables) diagnostic("CREATE_TABLE_${table.name.uppercase(java.util.Locale.ROOT)}") {
            db.exec(table.sql)
            table.indexes.forEach { db.exec(it.sql) }
        }
        for (table in tables) diagnostic("INSERT_${table.name.uppercase(java.util.Locale.ROOT)}") {
            val columns = table.fields.joinToString { quote(it.name) }
            val placeholders = table.fields.joinToString { if (it.type == "TEXT") "CAST(? AS TEXT)" else "?" }
            for (row in rows.getValue(table.name)) {
                db.query("INSERT INTO ${quote(table.name)} ($columns) VALUES ($placeholders)") { stmt ->
                    table.fields.forEachIndexed { i, field ->
                        when (val value = row[field.name]) {
                            null -> stmt.bindNull(i + 1)
                            // Byte binding plus SQL CAST preserves embedded NUL and exact UTF-8.
                            is String -> stmt.bind(i + 1, value.toByteArray(Charsets.UTF_8))
                            is Long -> stmt.bind(i + 1, value)
                            is Double -> stmt.bind(i + 1, value)
                            else -> error("V10_BAD_INSERT_VALUE")
                        }
                    }
                    require10(!stmt.step())
                }
            }
        }
        diagnostic("CREATE_METADATA") {
            db.exec(MASTER_CREATE)
            db.query("INSERT INTO room_master_table (id,identity_hash) VALUES (?,?)") {
                it.bind(1, 42L)
                it.bind(2, IDENTITY)
                require10(!it.step())
            }
            db.exec("PRAGMA user_version = 10")
        }
        diagnostic("CREATE_COMMIT") { db.exec("COMMIT") }
    }
}

private fun validate(db: SQLiteConnection, tables: List<Table>) {
    db.query("PRAGMA user_version") { require10(it.step() && it.columnLong(0) == 10L && !it.step()) }
    db.query("SELECT id, identity_hash FROM room_master_table") {
        require10(it.step() && it.columnLong(0) == 42L && it.columnString(1) == IDENTITY && !it.step())
    }
    val objects = mutableMapOf<String, Pair<String, String?>>()
    objects["room_master_table"] = "table" to MASTER_CREATE
    for (table in tables) {
        objects[table.name] = "table" to table.sql
        for (index in table.indexes) objects[index.name] = "index" to index.sql
        if (table.fields.single { it.name == table.key }.type == "TEXT") {
            objects["sqlite_autoindex_${table.name}_1"] = "index" to null
        }
    }
    db.query("SELECT name,type,sql FROM sqlite_master") { q ->
        val seen = mutableSetOf<String>()
        while (q.step()) {
            val name = q.columnString(0)
            val actual = q.columnString(1) to if (q.columnType(2) == SQLiteConstants.SQLITE_NULL) null else q.columnString(2)
            if (name == "android_metadata") {
                require10(actual == ("table" to "CREATE TABLE android_metadata (locale TEXT)"))
            } else {
                val expected = checkNotNull(objects[name]) { "V10_UNEXPECTED_SCHEMA_OBJECT" }
                require10(seen.add(name) && actual == (expected.first to expected.second?.replaceFirst(" IF NOT EXISTS", "")))
            }
        }
        require10(seen == objects.keys)
    }
    for (table in tables) validateTable(db, table)
}

private fun validateTable(db: SQLiteConnection, table: Table) {
    val name = quote(table.name)
    db.query("PRAGMA table_info($name)") { q ->
        table.fields.forEachIndexed { i, f ->
            require10(q.step() && q.columnInt(0) == i && q.columnString(1) == f.name &&
                q.columnString(2) == f.type && q.columnInt(3) == (if (f.required) 1 else 0) &&
                (if (f.default == null) q.columnType(4) == SQLiteConstants.SQLITE_NULL else q.columnString(4) == f.default) &&
                q.columnInt(5) == (if (f.name == table.key) 1 else 0))
        }
        require10(!q.step())
    }
    db.query("PRAGMA foreign_key_list($name)") { require10(!it.step()) }
    val indexes = table.indexes.associate { it.name to it.unique }.toMutableMap()
    if (table.fields.single { it.name == table.key }.type == "TEXT") indexes["sqlite_autoindex_${table.name}_1"] = true
    db.query("PRAGMA index_list($name)") { q ->
        val found = mutableMapOf<String, Boolean>()
        while (q.step()) require10(found.put(q.columnString(1), q.columnInt(2) == 1) == null)
        require10(found == indexes)
    }
    for (index in table.indexes) db.query("PRAGMA index_info(${quote(index.name)})") { q ->
        index.keys.forEachIndexed { i, key -> require10(q.step() && q.columnInt(0) == i && q.columnString(2) == key) }
        require10(!q.step())
    }
    val expected = V10FixtureExpected.rows.getValue(table.name)
    val projections = table.fields.joinToString { f ->
        val c = quote(f.name)
        "typeof($c),CASE WHEN typeof($c)='text' THEN hex(CAST($c AS BLOB)) ELSE $c END"
    }
    db.query("SELECT COUNT(*) FROM $name") {
        require10(it.step() && it.columnLong(0) == expected.size.toLong() && !it.step())
    }
    for (row in expected) db.query("SELECT $projections FROM $name WHERE ${quote(table.key)}=?") { q ->
        when (val key = row.getValue(table.key)) {
            is String -> q.bind(1, key)
            is Long -> q.bind(1, key)
            else -> error("V10_BAD_KEY")
        }
        require10(q.step())
        table.fields.forEachIndexed { i, f ->
            val value = 2 * i + 1
            val type = q.columnString(value - 1)
            require10(when (val want = row[f.name]) {
                null -> type == "null" && q.columnType(value) == SQLiteConstants.SQLITE_NULL
                is String -> type == "text" && q.columnString(value) == hex10(want.toByteArray(Charsets.UTF_8))
                is Long -> type == "integer" && q.columnLong(value) == want
                is Double -> type == "real" && q.columnDouble(value).toRawBits() == want.toRawBits()
                else -> false
            })
        }
        require10(!q.step())
    }
}
