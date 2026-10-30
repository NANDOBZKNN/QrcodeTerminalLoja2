package br.com.qrcodeterminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Tenta abrir a tela do QrcodeTerminal após a inicialização do tablet.
 * O Android e algumas versões de HyperOS podem bloquear a abertura automática.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("printer", Context.MODE_PRIVATE)
        val ip = prefs.getString("ip", null)
        val port = prefs.getInt("port", 9100)
        Log.i("QrcodeTerminal", "Inicialização concluída. Impressora salva: ${ip ?: "não configurada"}:$port")

        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(launchIntent)
            }
        } catch (error: Exception) {
            Log.w("QrcodeTerminal", "O sistema bloqueou a abertura automática do aplicativo.", error)
        }
    }
}
