package com.ejemplo.ledcontroller

import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLedStatus: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnAllOn: Button
    private lateinit var btnAllOff: Button

    private lateinit var ledSwitches: Array<Switch>
    private lateinit var btnToggles: Array<Button>
    private lateinit var etIPAddress: EditText

    private var arduinoIP = ""
    private var isConnected = false
    private var isUpdatingUI = false

    // Job para polling automático
    private var pollingJob: Job? = null
    private val pollingInterval = 2000L // 2 segundos

    companion object {
        private const val TAG = "LEDController"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupClickListeners()
        etIPAddress.setText("192.168.90.200")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
    }

    private fun initializeViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvLedStatus = findViewById(R.id.tvLedStatus)
        btnConnect = findViewById(R.id.btnConnect)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnAllOn = findViewById(R.id.btnAllOn)
        btnAllOff = findViewById(R.id.btnAllOff)
        etIPAddress = findViewById(R.id.etIPAddress)

        ledSwitches = arrayOf(
            findViewById(R.id.switchLed1),
            findViewById(R.id.switchLed2),
            findViewById(R.id.switchLed3),
            findViewById(R.id.switchLed4),
            findViewById(R.id.switchLed5),
            findViewById(R.id.switchLed6)
        )

        btnToggles = arrayOf(
            findViewById(R.id.btnToggle1),
            findViewById(R.id.btnToggle2),
            findViewById(R.id.btnToggle3),
            findViewById(R.id.btnToggle4),
            findViewById(R.id.btnToggle5),
            findViewById(R.id.btnToggle6)
        )
    }

    private fun setupClickListeners() {
        btnConnect.setOnClickListener {
            if (isConnected) {
                disconnect()
            } else {
                connectToArduino()
            }
        }

        btnRefresh.setOnClickListener { refreshLEDStatus() }
        btnAllOn.setOnClickListener { setAllLEDs(true) }
        btnAllOff.setOnClickListener { setAllLEDs(false) }

        // ⭐ CLAVE: Detectar SOLO interacción manual del usuario
        ledSwitches.forEachIndexed { index, switch ->
            switch.setOnCheckedChangeListener { buttonView, isChecked ->
                // Solo actuar si el usuario presionó físicamente el switch
                // y NO estamos actualizando desde el servidor
                if (!isUpdatingUI && buttonView.isPressed) {
                    Log.d(TAG, "Usuario cambió LED ${index + 1} a: $isChecked")
                    setLEDState(index + 1, isChecked)
                }
            }
        }

        btnToggles.forEachIndexed { index, button ->
            button.setOnClickListener {
                if (isConnected) {
                    toggleLED(index + 1)
                }
            }
        }
    }

    private fun connectToArduino() {
        arduinoIP = etIPAddress.text.toString().trim()

        if (arduinoIP.isEmpty()) {
            Toast.makeText(this, "⚠️ Ingresa una dirección IP válida", Toast.LENGTH_SHORT).show()
            return
        }

        tvStatus.text = "⏳ Conectando..."
        btnConnect.isEnabled = false

        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val url = URL("http://$arduinoIP")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.setRequestProperty("Connection", "close")

                    val responseCode = connection.responseCode

                    if (responseCode == 200) {
                        val reader = BufferedReader(InputStreamReader(connection.inputStream))
                        val response = reader.readText()
                        reader.close()

                        val json = JSONObject(response)
                        json.has("leds")
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error: ${e.message}")
                    false
                }
            }

            btnConnect.isEnabled = true
            updateConnectionStatus(result)

            if (result) {
                Toast.makeText(this@MainActivity, "✅ Conectado exitosamente", Toast.LENGTH_SHORT).show()
                delay(200)
                refreshLEDStatus()
                // ⭐ Iniciar polling automático
                startPolling()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "❌ Error de conexión\nVerifica:\n1. IP: $arduinoIP\n2. Arduino encendido\n3. Misma red WiFi",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun disconnect() {
        stopPolling()
        isConnected = false
        arduinoIP = ""
        updateConnectionStatus(false)
        Toast.makeText(this, "Desconectado", Toast.LENGTH_SHORT).show()
    }

    // ⭐ POLLING AUTOMÁTICO: Actualiza el estado cada 2 segundos
    private fun startPolling() {
        stopPolling() // Detener cualquier polling anterior

        pollingJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive && isConnected) {
                refreshLEDStatus()
                delay(pollingInterval)
            }
        }
        Log.d(TAG, "✅ Polling automático iniciado")
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        Log.d(TAG, "⏹️ Polling automático detenido")
    }

    private fun refreshLEDStatus() {
        if (!isConnected) return

        CoroutineScope(Dispatchers.Main).launch {
            val states = withContext(Dispatchers.IO) {
                try {
                    val url = URL("http://$arduinoIP")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 3000
                    connection.readTimeout = 3000
                    connection.setRequestProperty("Connection", "close")

                    if (connection.responseCode == 200) {
                        val reader = BufferedReader(InputStreamReader(connection.inputStream))
                        val response = reader.readText()
                        reader.close()

                        val jsonResponse = JSONObject(response)

                        if (!jsonResponse.has("leds")) {
                            return@withContext null
                        }

                        val ledsArray = jsonResponse.getJSONArray("leds")
                        BooleanArray(ledsArray.length()) { i -> ledsArray.getBoolean(i) }
                    } else {
                        null
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    Log.w(TAG, "Timeout al consultar estado")
                    null
                } catch (e: java.net.ConnectException) {
                    // Conexión perdida
                    CoroutineScope(Dispatchers.Main).launch {
                        disconnect()
                        Toast.makeText(this@MainActivity, "🔌 Conexión perdida", Toast.LENGTH_LONG).show()
                    }
                    null
                } catch (e: Exception) {
                    Log.e(TAG, "Error: ${e.message}")
                    null
                }
            }

            if (states != null) {
                updateLEDStatus(states)
            }
        }
    }

    private fun setLEDState(ledNumber: Int, state: Boolean) {
        if (!isConnected) return

        val command = if (state) "on" else "off"

        CoroutineScope(Dispatchers.Main).launch {
            val (success, newState) = withContext(Dispatchers.IO) {
                try {
                    val urlString = "http://$arduinoIP/led/$ledNumber/$command"
                    val url = URL(urlString)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 3000
                    connection.readTimeout = 3000
                    connection.setRequestProperty("Connection", "close")

                    if (connection.responseCode == 200) {
                        try {
                            val reader = BufferedReader(InputStreamReader(connection.inputStream))
                            val response = reader.readText()
                            reader.close()

                            val json = JSONObject(response)
                            if (json.has("state")) {
                                return@withContext Pair(true, json.getBoolean("state"))
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Sin respuesta JSON: ${e.message}")
                        }
                    }

                    Pair(connection.responseCode == 200, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Error al controlar LED $ledNumber: ${e.message}")
                    Pair(false, null)
                }
            }

            if (success) {
                // ⭐ NO llamar a refreshLEDStatus() aquí
                // El polling automático lo hará pronto
                Log.d(TAG, "✅ LED $ledNumber controlado correctamente")
            } else {
                // Si falló, revertir el switch
                isUpdatingUI = true
                ledSwitches[ledNumber - 1].isChecked = !state
                isUpdatingUI = false

                Toast.makeText(
                    this@MainActivity,
                    "❌ Error al controlar LED $ledNumber",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun toggleLED(ledNumber: Int) {
        if (!isConnected) return

        CoroutineScope(Dispatchers.Main).launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val url = URL("http://$arduinoIP/led/$ledNumber/toggle")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 3000
                    connection.readTimeout = 3000
                    connection.setRequestProperty("Connection", "close")
                    connection.responseCode == 200
                } catch (e: Exception) {
                    Log.e(TAG, "Error toggle LED $ledNumber: ${e.message}")
                    false
                }
            }

            if (!success) {
                Toast.makeText(
                    this@MainActivity,
                    "❌ Error al hacer toggle LED $ledNumber",
                    Toast.LENGTH_SHORT
                ).show()
            }
            // El polling actualizará el estado automáticamente
        }
    }

    private fun setAllLEDs(state: Boolean) {
        if (!isConnected) return

        CoroutineScope(Dispatchers.Main).launch {
            var success = 0
            var failed = 0

            for (i in 1..6) {
                val command = if (state) "on" else "off"
                val result = withContext(Dispatchers.IO) {
                    try {
                        val url = URL("http://$arduinoIP/led/$i/$command")
                        val connection = url.openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 2000
                        connection.readTimeout = 2000
                        connection.setRequestProperty("Connection", "close")

                        connection.responseCode == 200
                    } catch (e: Exception) {
                        Log.e(TAG, "Error LED $i: ${e.message}")
                        false
                    }
                }

                if (result) success++ else failed++
                delay(100)
            }

            if (failed > 0) {
                Toast.makeText(
                    this@MainActivity,
                    "⚠️ Algunos LEDs no respondieron ($failed fallos)",
                    Toast.LENGTH_SHORT
                ).show()
            }

            // El polling actualizará el estado
        }
    }

    private fun updateConnectionStatus(connected: Boolean) {
        isConnected = connected
        runOnUiThread {
            if (connected) {
                tvStatus.text = "🟢 CONECTADO"
                tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                btnConnect.text = "🔌 DESCONECTAR"
            } else {
                tvStatus.text = "🔴 DESCONECTADO"
                tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                btnConnect.text = "🔗 CONECTAR"
            }
            enableControls(connected)
        }
    }

    private fun enableControls(enabled: Boolean) {
        btnRefresh.isEnabled = enabled
        btnAllOn.isEnabled = enabled
        btnAllOff.isEnabled = enabled
        ledSwitches.forEach { it.isEnabled = enabled }
        btnToggles.forEach { it.isEnabled = enabled }
    }

    // ⭐ Actualizar UI sin disparar listeners
    private fun updateLEDStatus(states: BooleanArray) {
        runOnUiThread {
            isUpdatingUI = true

            val statusText = StringBuilder("Estado: ")
            states.forEachIndexed { index, state ->
                if (index < ledSwitches.size) {
                    // Solo actualizar si el estado cambió
                    if (ledSwitches[index].isChecked != state) {
                        ledSwitches[index].isChecked = state
                    }

                    statusText.append("LED${index + 1}: ")
                        .append(if (state) "✅ ON" else "❌ OFF")
                    if (index < states.size - 1) statusText.append(", ")
                }
            }

            tvLedStatus.text = statusText.toString()
            isUpdatingUI = false
        }
    }
}

// ========== GUÍA DE PRUEBA ==========
// 1. Arduino debe estar ejecutando el servidor LED
// 2. Conecta la app con la IP correcta
// 3. El polling sincronizará automáticamente cada 2 segundos
// 4. Puedes cambiar LEDs desde:
//    - Los switches en la app
//    - Los botones de toggle
//    - Otra app conectada al mismo Arduino
//    - Navegador web: http://IP_ARDUINO/led/1/on