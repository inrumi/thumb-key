package com.dessalines.thumbkey.db

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

object DatabaseBackupHelper {
    private val SQLITE_MAGIC_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun export(
        ctx: Context,
        db: AppDB,
        backupFileUri: Uri,
    ): Result<Unit> =
        runCatching {
            val wdb = db.openHelper.writableDatabase
            // Flush WAL frames into the main DB file before reading it.
            wdb.query("PRAGMA wal_checkpoint(FULL);").use { it.moveToFirst() }
            wdb.query("PRAGMA wal_checkpoint(TRUNCATE);").use { it.moveToFirst() }

            val dbPath = wdb.path ?: error("Database path unavailable")
            val dbFile = File(dbPath)
            require(dbFile.isFile) { "Database file does not exist: $dbPath" }

            val filesToBackup =
                listOf(dbFile, File("$dbPath-wal"), File("$dbPath-shm")).filter { it.isFile }

            val tempZip = File.createTempFile("thumbkey-backup-", ".zip", ctx.cacheDir)
            try {
                ZipOutputStream(FileOutputStream(tempZip).buffered()).use { zos ->
                    filesToBackup.forEach { file ->
                        zos.putNextEntry(ZipEntry(file.name))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }

                val out =
                    ctx.contentResolver.openOutputStream(backupFileUri, "wt")
                        ?: ctx.contentResolver.openOutputStream(backupFileUri)
                        ?: error("Could not open backup destination")
                out.use { output ->
                    tempZip.inputStream().use { it.copyTo(output) }
                    output.flush()
                }
            } finally {
                tempZip.delete()
            }
        }.onFailure {
            runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, backupFileUri) }
        }

    fun import(
        ctx: Context,
        db: AppDB,
        backupFileUri: Uri,
    ): Result<Unit> =
        runCatching {
            val dbPath = db.openHelper.writableDatabase.path ?: error("Database path unavailable")
            val dbFile = File(dbPath)
            val dbDir = dbFile.parentFile ?: error("Database directory unavailable")
            val dbName = dbFile.name
            val allowedNames = setOf(dbName, "$dbName-wal", "$dbName-shm")

            val stagingDir = File(ctx.cacheDir, "thumbkey-restore-${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                val inputStream =
                    ctx.contentResolver.openInputStream(backupFileUri)
                        ?: error("Could not open backup file")
                inputStream.use { rawInput ->
                    ZipInputStream(rawInput.buffered()).use { zis ->
                        for (entry in generateSequence { zis.nextEntry }) {
                            require(!entry.isDirectory && entry.name in allowedNames) {
                                "Invalid backup archive entry: ${entry.name}"
                            }
                            File(stagingDir, entry.name).outputStream().use { zis.copyTo(it) }
                            zis.closeEntry()
                        }
                    }
                }

                val stagedDb = File(stagingDir, dbName)
                require(stagedDb.isFile && stagedDb.length() >= SQLITE_MAGIC_HEADER.size) {
                    "Invalid Thumb-Key backup file"
                }
                val header = ByteArray(SQLITE_MAGIC_HEADER.size)
                FileInputStream(stagedDb).use { input ->
                    require(input.read(header) == SQLITE_MAGIC_HEADER.size && header.contentEquals(SQLITE_MAGIC_HEADER)) {
                        "Invalid Thumb-Key backup file"
                    }
                }

                AppDB.closeAndClearInstance()

                dbFile.delete()
                File("$dbPath-wal").delete()
                File("$dbPath-shm").delete()
                File("$dbPath-journal").delete()

                dbDir.mkdirs()
                allowedNames.forEach { name ->
                    val staged = File(stagingDir, name)
                    if (staged.isFile) {
                        staged.copyTo(File(dbDir, name), overwrite = true)
                    }
                }
            } finally {
                stagingDir.deleteRecursively()
            }

            val launchIntent =
                ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                    ?: error("Unable to obtain launch intent")
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ctx.startActivity(launchIntent)
            exitProcess(0)
        }
}
