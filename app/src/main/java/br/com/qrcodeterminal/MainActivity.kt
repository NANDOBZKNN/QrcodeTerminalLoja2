package br.com.qrcodeterminal

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("printer", MODE_PRIVATE) }
    private lateinit var box: LinearLayout
    private lateinit var status: TextView
    private var adminMode = false
    // Hash SHA-256 com salt; o PIN não fica armazenado em texto puro.
    private val adminPinHash = "73dc09ce97cb736da393a41da5286b5932faeb25bf153607d50d20a1179bc3d4"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOperatorScreen()
    }

    private fun baseScreen() {
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 28, 32, 32)
        }
        box.addView(PrinterLogo(this), LinearLayout.LayoutParams(88, 72).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = 8
        })
        box.addView(TextView(this).apply {
            text = "QrcodeTerminal"
            textSize = 26f
            gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = if (adminMode) "Versão 2.7 • Administrador" else "Versão 2.7 • Operador"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
        })
        status = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 16)
        }
    }

    private fun configuredPrinter(): String {
        val ip = prefs.getString("ip", "")?.takeIf { it.isNotBlank() } ?: "Não configurado"
        return "$ip:${prefs.getInt("port", 9100)}"
    }

    private fun showOperatorScreen() {
        adminMode = false
        baseScreen()
        box.addView(TextView(this).apply {
            text = "IMPRESSORA CONFIGURADA"
            textSize = 13f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = configuredPrinter()
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 8)
        })
        box.addView(TextView(this).apply {
            text = "As configurações de rede são controladas pelo administrador."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setPadding(0, 4, 0, 16)
        })
        box.addView(Button(this).apply {
            text = "Testar conexão e imprimir comprovante"
            setOnClickListener { testAndPrintConnectionReceipt() }
        })
        box.addView(Button(this).apply {
            text = "Configurar serviço de impressão Android"
            setOnClickListener {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_PRINT_SETTINGS))
            }
        })
        box.addView(status)
        box.addView(Button(this).apply {
            text = "Acesso do administrador"
            setOnClickListener { requestAdminPin() }
        })
        setContentView(ScrollView(this).apply { addView(box) })
    }


    private fun testAndPrintConnectionReceipt() {
        val host = prefs.getString("ip", "")?.trim().orEmpty()
        val port = prefs.getInt("port", 9100)
        if (host.isBlank()) {
            status.text = "Impressora não configurada. Solicite ao administrador."
            return
        }
        status.text = "Verificando conexão com $host:$port..."
        Thread {
            val result = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 5000)
                    socket.soTimeout = 5000
                    socket.getOutputStream().apply {
                        write(connectionReceiptBytes(host, port))
                        flush()
                    }
                }
                "CONEXÃO OK: comprovante enviado para $host:$port. Confira a impressão."
            }.getOrElse {
                "FALHA DE CONEXÃO com $host:$port. ${it.message ?: "Verifique a rede e a impressora."}"
            }
            prefs.edit()
                .putString("last_print_diagnostic", result)
                .putLong("last_print_diagnostic_time", System.currentTimeMillis())
                .apply()
            runOnUiThread { status.text = result }
        }.start()
    }

    private fun connectionReceiptBytes(host: String, port: Int): ByteArray {
        val now = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale("pt", "BR"))
            .format(java.util.Date())
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x1B, 0x40)) // Inicializa ESC/POS
        out.write(byteArrayOf(0x1B, 0x61, 0x01)) // Centralizado
        out.write(byteArrayOf(0x1B, 0x45, 0x01))
        out.write("QrcodeTerminal\\n".toByteArray(Charsets.UTF_8))
        out.write(byteArrayOf(0x1B, 0x45, 0x00))
        out.write("COMPROVANTE DE CONEXAO\\n".toByteArray(Charsets.UTF_8))
        out.write("------------------------------\\n".toByteArray(Charsets.US_ASCII))
        out.write("STATUS: CONEXAO OK\\n".toByteArray(Charsets.UTF_8))
        out.write(byteArrayOf(0x1B, 0x61, 0x00)) // Alinhado à esquerda
        out.write("Impressora: $host\\n".toByteArray(Charsets.UTF_8))
        out.write("Porta TCP: $port\\n".toByteArray(Charsets.UTF_8))
        out.write("Data/hora: $now\\n".toByteArray(Charsets.UTF_8))
        out.write("------------------------------\\n".toByteArray(Charsets.US_ASCII))
        out.write("Teste de comunicacao concluido.\\n".toByteArray(Charsets.UTF_8))
        out.write(byteArrayOf(0x1B, 0x64, 0x04)) // Avanco
        out.write(byteArrayOf(0x1D, 0x56, 0x00)) // Corte total
        return out.toByteArray()
    }

    private fun requestAdminPin() {
        val field = EditText(this).apply {
            hint = "PIN do administrador"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Acesso administrativo")
            .setMessage("Informe o PIN para alterar as configurações.")
            .setView(field)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Entrar") { _, _ ->
                if (isValidPin(field.text.toString())) showAdminScreen()
                else Toast.makeText(this, "PIN incorreto", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun isValidPin(pin: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("QrcodeTerminal-admin-v27-" + pin).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } == adminPinHash
    }

    private fun showAdminScreen() {
        adminMode = true
        baseScreen()
        box.addView(TextView(this).apply {
            text = "CONFIGURAÇÃO DA IMPRESSORA"
            textSize = 18f
            setPadding(0, 18, 0, 8)
        })
        val ip = EditText(this).apply {
            hint = "IP da impressora"
            setSingleLine()
            setText(prefs.getString("ip", "192.168.0.131"))
        }
        val port = EditText(this).apply {
            hint = "Porta TCP"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setText(prefs.getInt("port", 9100).toString())
        }
        box.addView(ip)
        box.addView(port)
        box.addView(Button(this).apply {
            text = "Salvar configuração"
            setOnClickListener {
                val host = ip.text.toString().trim()
                val p = port.text.toString().toIntOrNull()
                if (host.isBlank() || p == null || p !in 1..65535) {
                    Toast.makeText(this@MainActivity, "Confira IP e porta", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit().putString("ip", host).putInt("port", p).apply()
                    status.text = "Configuração salva: $host:$p"
                    Toast.makeText(this@MainActivity, "Configuração salva neste tablet", Toast.LENGTH_SHORT).show()
                }
            }
        })
        box.addView(Button(this).apply {
            text = "Testar conexão"
            setOnClickListener {
                val host = ip.text.toString().trim()
                val p = port.text.toString().toIntOrNull() ?: 9100
                if (host.isBlank() || p !in 1..65535) status.text = "Confira o IP e a porta."
                else {
                    status.text = "Testando conexão..."
                    Thread {
                        val msg = runCatching {
                            Socket().use { it.connect(InetSocketAddress(host, p), 4000) }
                            "Conexão TCP estabelecida: $host:$p"
                        }.getOrElse { "Falha na conexão: ${it.message}" }
                        runOnUiThread { status.text = msg }
                    }.start()
                }
            }
        })
        box.addView(Button(this).apply {
            text = "Imprimir teste"
            setOnClickListener {
                val host = ip.text.toString().trim()
                val p = port.text.toString().toIntOrNull() ?: 9100
                if (host.isBlank() || p !in 1..65535) status.text = "Confira o IP e a porta."
                else {
                    prefs.edit().putString("ip", host).putInt("port", p).apply()
                    status.text = "Enviando teste..."
                    Thread {
                        val msg = runCatching {
                            Socket().use { socket ->
                                socket.connect(InetSocketAddress(host, p), 5000)
                                socket.getOutputStream().apply { write(testPrintBytes()); flush() }
                            }
                            "Comando enviado para $host:$p. Verifique a impressão."
                        }.getOrElse { "Falha ao enviar: ${it.message}" }
                        runOnUiThread { status.text = msg }
                    }.start()
                }
            }
        })
        box.addView(Button(this).apply {
            text = "Ver diagnóstico da impressão"
            setOnClickListener {
                val msg = prefs.getString("last_print_diagnostic", "Nenhuma impressão registrada.")
                val time = prefs.getLong("last_print_diagnostic_time", 0L)
                val whenText = if (time > 0L) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(time)) else "—"
                status.text = "Último registro ($whenText):\n$msg"
            }
        })
        box.addView(Button(this).apply {
            text = "Voltar ao modo operador"
            setOnClickListener { showOperatorScreen() }
        })
        box.addView(status)
        setContentView(ScrollView(this).apply { addView(box) })
    }

    private fun testPrintBytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x1B, 0x40))
        out.write(byteArrayOf(0x1B, 0x61, 0x01))
        out.write(byteArrayOf(0x1B, 0x45, 0x01))
        out.write("TESTE DE IMPRESSAO\n".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x1B, 0x45, 0x00))
        out.write("QrcodeTerminal\n".toByteArray(Charsets.US_ASCII))
        out.write("------------------------------\n".toByteArray(Charsets.US_ASCII))
        out.write("Conexao TCP 9100 OK\n".toByteArray(Charsets.US_ASCII))
        out.write("Comando enviado com sucesso.\n".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x1B, 0x64, 0x04))
        out.write(byteArrayOf(0x1D, 0x56, 0x00))
        return out.toByteArray()
    }

    private class PrinterLogo(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 105, 160)
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRoundRect(w * .16f, h * .28f, w * .84f, h * .78f, 9f, 9f, paint)
            canvas.drawRect(w * .28f, h * .08f, w * .72f, h * .34f, paint)
            canvas.drawRect(w * .28f, h * .62f, w * .72f, h * .94f, paint)
            canvas.drawLine(w * .34f, h * .73f, w * .66f, h * .73f, paint)
            canvas.drawLine(w * .34f, h * .83f, w * .62f, h * .83f, paint)
            canvas.drawCircle(w * .70f, h * .45f, 2.5f, paint)
        }
    }
}
