package br.com.qrcodeterminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class QrcodePrintService : PrintService() {
    private fun setDiagnostic(message: String) {
        getSharedPreferences("printer", MODE_PRIVATE).edit()
            .putString("last_print_diagnostic", message)
            .putLong("last_print_diagnostic_time", System.currentTimeMillis())
            .apply()
        android.util.Log.i("QrcodeTerminal", message)
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = Discovery()

    override fun onPrintJobQueued(job: PrintJob) {
        setDiagnostic("ETAPA 1/5: trabalho recebido pelo serviço.")
        if (!job.isQueued) {
            setDiagnostic("ERRO: o trabalho não está na fila (queued).")
            return
        }

        val source = job.document.data
        if (source == null) {
            setDiagnostic("ERRO: Android entregou documento vazio.")
            job.fail("Documento de impressão vazio.")
            return
        }

        val sourceCopy = try {
            ParcelFileDescriptor.dup(source.fileDescriptor)
        } catch (e: Exception) {
            job.fail("Não foi possível acessar o documento: ${e.message ?: "erro"}")
            return
        }

        if (!job.start()) {
            setDiagnostic("ERRO: Android não permitiu iniciar o trabalho.")
            sourceCopy.close()
            // Não deixe um trabalho preso na fila se o Android não aceitar a transição.
            if (job.isQueued || job.isBlocked) {
                job.fail("O Android não conseguiu iniciar o trabalho de impressão.")
            }
            return
        }

        worker.execute {
            var tempPdf: File? = null
            val result = runCatching {
                tempPdf = File.createTempFile("qrcode-print-", ".pdf", cacheDir)

                // O Android pode entregar o documento por um pipe não-seekable.
                // Copiamos para arquivo local e fechamos o fluxo antes de renderizar.
                setDiagnostic("ETAPA 2/5: copiando PDF recebido.")
                ParcelFileDescriptor.AutoCloseInputStream(sourceCopy).use { input ->
                    FileOutputStream(tempPdf!!).use { output -> input.copyTo(output) }
                }
                setDiagnostic("ETAPA 3/5: PDF copiado (${tempPdf!!.length()} bytes). Iniciando conversão.")

                if (tempPdf!!.length() == 0L) {
                    throw IllegalStateException("O Android enviou um documento vazio.")
                }

                val prefs = getSharedPreferences("printer", MODE_PRIVATE)
                val host = prefs.getString("ip", "192.168.0.131") ?: "192.168.0.131"
                val port = prefs.getInt("port", 9100)
                setDiagnostic("ETAPA 4/5: conversão concluída. Conectando a $host:$port.")
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 8000)
                    socket.soTimeout = 30000
                    setDiagnostic("ETAPA 5/5: TCP conectado. Enviando dados para a impressora.")
                    socket.getOutputStream().buffered(16 * 1024).use { out ->
                        renderPdf(tempPdf!!, out)
                        out.flush()
                    }
                }
                setDiagnostic("CONCLUÍDO: dados enviados à impressora.")
            }
            tempPdf?.delete()
            main.post {
                if (job.isStarted || job.isBlocked || job.isQueued) {
                    if (result.isSuccess) {
                        job.complete()
                    } else {
                        job.fail(result.exceptionOrNull()?.message ?: "Falha ao imprimir")
                    }
                }
            }
        }
    }

    override fun onRequestCancelPrintJob(job: PrintJob) {
        if (job.isQueued || job.isStarted || job.isBlocked) job.cancel()
    }

    private fun renderPdf(file: File, out: java.io.OutputStream) {
        setDiagnostic("CONVERSÃO: abrindo PDF e renderizando páginas.")
        out.write(byteArrayOf(0x1B, 0x40))
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                for (i in 0 until pdf.pageCount) {
                    pdf.openPage(i).use { page ->
                        val width = 576
                        val height = (width.toFloat() * page.height / page.width)
                            .toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            Canvas(bitmap).drawColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                            // Mantém exatamente a conversão de imagem da versão 1.9.
                            // Só muda o envio: cada página segue para a impressora assim
                            // que fica pronta, em vez de esperar o PDF inteiro ser convertido.
                            if (hasPrintableContent(bitmap)) {
                                out.write(toEscPos(bitmap))
                                out.write(byteArrayOf(0x1B, 0x64, 0x03))
                                out.write(byteArrayOf(0x1D, 0x56, 0x00))
                                out.flush()
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
    }

    private fun hasPrintableContent(bitmap: Bitmap): Boolean {
        // Conta pixels realmente escuros. Uma página totalmente branca fica abaixo
        // do limite e é descartada; conteúdo como QR Code/logo ultrapassa facilmente.
        var darkPixels = 0
        val step = 2
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val pixel = bitmap.getPixel(x, y)
                val gray = (Color.red(pixel) * 299 + Color.green(pixel) * 587 +
                    Color.blue(pixel) * 114) / 1000
                if (gray < 220) {
                    darkPixels++
                    if (darkPixels >= 10) return true
                }
            }
        }
        return false
    }

    private fun toEscPos(bitmap: Bitmap): ByteArray {
        val bytesWide = (bitmap.width + 7) / 8
        val out = ByteArrayOutputStream()
        var y = 0
        while (y < bitmap.height) {
            val rows = minOf(128, bitmap.height - y)
            out.write(byteArrayOf(
                0x1D, 0x76, 0x30, 0x00,
                (bytesWide and 255).toByte(), (bytesWide shr 8).toByte(),
                (rows and 255).toByte(), (rows shr 8).toByte()
            ))
            for (row in y until y + rows) {
                for (bx in 0 until bytesWide) {
                    var value = 0
                    for (bit in 0..7) {
                        val x = bx * 8 + bit
                        if (x < bitmap.width) {
                            val c = bitmap.getPixel(x, row)
                            val gray = (Color.red(c) * 299 + Color.green(c) * 587 +
                                Color.blue(c) * 114) / 1000
                            if (gray < 160) value = value or (0x80 shr bit)
                        }
                    }
                    out.write(value)
                }
            }
            y += rows
        }
        return out.toByteArray()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private inner class Discovery : PrinterDiscoverySession() {
        private val id78 by lazy { generatePrinterId("elgin-i8-78mm") }

        private fun publish() {
            // Somente a opção de bobina de 78 mm, mantendo altura de 23 cm.
            val roll78mm = PrintAttributes.MediaSize(
                "ELGIN_78MM", "Bobina térmica 78 mm - 23 cm", 3071, 9055
            )
            val caps78 = PrinterCapabilitiesInfo.Builder(id78)
                .addMediaSize(roll78mm, true)
                .addResolution(PrintAttributes.Resolution("203", "203 dpi", 203, 203), true)
                .setColorModes(
                    PrintAttributes.COLOR_MODE_MONOCHROME,
                    PrintAttributes.COLOR_MODE_MONOCHROME
                )
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()
            val info78 = PrinterInfo.Builder(
                id78, "Elgin i8 - QrcodeTerminal (78 mm)", PrinterInfo.STATUS_IDLE
            ).setCapabilities(caps78).build()

            addPrinters(listOf(info78))
        }

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) = publish()
        override fun onStopPrinterDiscovery() {}
        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) = publish()
        override fun onStartPrinterStateTracking(printerId: PrinterId) = publish()
        override fun onStopPrinterStateTracking(printerId: PrinterId) {}
        override fun onDestroy() {}
    }
}
