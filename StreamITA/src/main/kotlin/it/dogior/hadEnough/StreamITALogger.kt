package it.dogior.hadEnough

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object StreamITALogger {
    private val logs = mutableListOf<String>()
    private const val MAX_LINES = 500
    
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.ITALY)

    fun log(tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val line = "[$timestamp] $tag: $message"
        
        synchronized(logs) {
            logs.add(line)
            if (logs.size > MAX_LINES) {
                logs.removeAt(0)
            }
        }
    }

    fun getLogs(): String {
        synchronized(logs) {
            return if (logs.isEmpty()) "Nessun log disponibile" 
            else logs.joinToString("\n")
        }
    }

    fun getLogCount(): Int {
        synchronized(logs) {
            return logs.size
        }
    }

    fun clear() {
        synchronized(logs) {
            logs.clear()
        }
    }
}
