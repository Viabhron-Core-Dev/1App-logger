package com.aistudio.launcherlogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LoggerService : Service() {

    private val channelId = "LoggerServiceChannel"
    private var isRunning = false
    private var logThread: Thread? = null
    
    // Only capture lines matching:
    private val targetString = "com.aistudio.mylauncher.abcd"
    private val fatalString = "FATAL EXCEPTION"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, createNotification())
            }
            startLogging()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                channelId,
                "Launcher Logger Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val downloadsIntent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
        downloadsIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            downloadsIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Launcher Logger Active")
            .setContentText("Logging in background...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(android.R.drawable.ic_menu_view, "Open Downloads", pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startLogging() {
        logThread = Thread {
            var process: Process? = null
            try {
                // Clear logcat (optional but safe)
                Runtime.getRuntime().exec("logcat -c").waitFor()
                
                // Start listening to logcat
                process = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "time", "*:E"))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                
                val runningLoggerFile = File(downloadsDir, "launcher_log_running.txt")
                var runningWriter = FileOutputStream(runningLoggerFile, true).bufferedWriter()
                
                // Buffer to keep some context for a crash snapshot
                val recentLines = mutableListOf<String>()

                while (isRunning) {
                    val line = reader.readLine() ?: break
                    
                    if (line.contains(targetString) || line.contains(fatalString)) {
                        runningWriter.appendLine(line)
                        runningWriter.flush()
                        
                        recentLines.add(line)
                        if (recentLines.size > 100) {
                            recentLines.removeAt(0)
                        }
                        
                        // Snapshot on crash
                        if (line.contains(fatalString)) {
                            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                            val snapshotFile = File(downloadsDir, "launcher_crash_$timestamp.txt")
                            snapshotFile.writeText(recentLines.joinToString("\n"))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("LoggerService", "Error in logging thread", e)
            } finally {
                process?.destroy()
            }
        }
        logThread?.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        logThread?.interrupt()
    }
}
