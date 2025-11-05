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

    companion object {
        private const val TAG = "LEDController"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupClickListeners()
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

        ledSwitches.forEachIndexed { index, switch ->
            switch.setOnCheckedChangeListener { _, isChecked ->
                if (isConnected) {
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
            Toast.makeText(this, "Ingresa una dirección IP válida", Toast.LENGTH_SHORT).show()
            return
        }

        tvStatus.text = "Conectando..."
        btnConnect.isEnabled = false

        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Intentando conectar a: http://$arduinoIP")

                    val url = URL("http://$arduinoIP")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.setRequestProperty("Connection", "close")

                    val responseCode = connection.responseCode
                    Log.d(TAG, "Código de respuesta: $responseCode")

                    if (responseCode == 200) {
                        val reader = BufferedReader(InputStreamReader(connection.inputStream))
                        val response = reader.readText()
                        reader.close()
                        Log.d(TAG, "Respuesta: $response")

                        // Verificar que sea JSON válido
                        JSONObject(response)
                        true
                    } else {
                        Log.e(TAG, "Código de respuesta inválido: $responseCode")
                        false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error de conexión: ${e.message}", e)
                    false
                }
            }

            btnConnect.isEnabled = true
            updateConnectionStatus(result)

            if (result) {
                Toast.makeText(this@MainActivity, "✅ Conexión exitosa", Toast.LENGTH_SHORT).show()
                refreshLEDStatus()
            } else {
                Toast.makeText(this@MainActivity, "❌ Error de conexión. Verifica la IP", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun disconnect() {
        isConnected = false
        arduinoIP = ""
        updateConnectionStatus(false)
        Toast.makeText(this, "Desconectado", Toast.LENGTH_SHORT).show()
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

                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = reader.readText()
                    reader.close()

                    val jsonResponse = JSONObject(response)
                    val ledsArray = jsonResponse.getJSONArray("leds")

                    BooleanArray(ledsArray.length()) { i -> ledsArray.getBoolean(i) }
                } catch (e: Exception) {
                    Log.e(TAG, "Error al obtener estado: ${e.message}", e)
                    null
                }
            }

            if (states != null) {
                updateLEDStatus(states)
            } else {
                Toast.makeText(this@MainActivity, "Error al obtener estado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setLEDState(ledNumber: Int, state: Boolean) {
        if (!isConnected) return

        val command = if (state) "on" else "off"
        controlLED(ledNumber, command)
    }

    private fun toggleLED(ledNumber: Int) {
        if (!isConnected) return
        controlLED(ledNumber, "toggle")
    }

    private fun setAllLEDs(state: Boolean) {
        if (!isConnected) return

        CoroutineScope(Dispatchers.Main).launch {
            for (i in 1..6) {
                val command = if (state) "on" else "off"
                withContext(Dispatchers.IO) {
                    try {
                        val url = URL("http://$arduinoIP/led/$i/$command")
                        val connection = url.openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 2000
                        connection.readTimeout = 2000
                        connection.responseCode
                    } catch (e: Exception) {
                        Log.e(TAG, "Error en LED $i: ${e.message}")
                    }
                }
                delay(100)
            }

            delay(300)
            refreshLEDStatus()
        }
    }

    private fun controlLED(ledNumber: Int, command: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val url = URL("http://$arduinoIP/led/$ledNumber/$command")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 3000
                    connection.readTimeout = 3000
                    connection.responseCode == 200
                } catch (e: Exception) {
                    Log.e(TAG, "Error al controlar LED $ledNumber: ${e.message}")
                    false
                }
            }

            if (result) {
                delay(150)
                refreshLEDStatus()
            } else {
                Toast.makeText(this@MainActivity, "Error al controlar LED $ledNumber", Toast.LENGTH_SHORT).show()
            }
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

    private fun updateLEDStatus(states: BooleanArray) {
        runOnUiThread {
            val statusText = StringBuilder("Estado: ")
            states.forEachIndexed { index, state ->
                ledSwitches[index].setOnCheckedChangeListener(null)
                ledSwitches[index].isChecked = state
                setupSwitchListener(index)

                statusText.append("LED${index + 1}: ")
                    .append(if (state) "ON" else "OFF")
                if (index < states.size - 1) statusText.append(", ")
            }
            tvLedStatus.text = statusText.toString()
        }
    }

    private fun setupSwitchListener(index: Int) {
        ledSwitches[index].setOnCheckedChangeListener { _, isChecked ->
            if (isConnected) {
                setLEDState(index + 1, isChecked)
            }
        }
    }
}