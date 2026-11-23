package app.airmusic.modern.ui.components

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.rememberModalBottomSheetState
import android.content.Intent
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Phase 4 QR Code Share Sheet for Instant Low-Latency Browser Listening (<50 ms WebSocket PCM).
 * Generates the QR Bitmap locally on-device using ZXing QRCodeWriter with zero external network calls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrStreamShareSheet(
    listenUrl: String,
    isCapturing: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val qrBitmap = remember(listenUrl) { generateQrCodeBitmap(listenUrl, 512) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Open fully so the address, warning and Share button are visible without dragging.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Listen in a browser",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Scan this code with any phone, tablet or computer on the same Wi-Fi, or open the address below in a browser.",
                style = MaterialTheme.typography.bodySmall
            )
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 4.dp
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "QR code for the listening page",
                    modifier = Modifier
                        .size(220.dp)
                        .padding(12.dp)
                )
            }
            Text(
                text = listenUrl,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Page won't open on the other phone (\"Site can't be reached\")? On newer Android phones, " +
                    "allow the browser's Nearby devices permission: Settings → Apps → Chrome → Permissions → " +
                    "Nearby devices. Both devices must be on the same Wi-Fi (not a guest network).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!isCapturing) {
                Text(
                    text = "Audio capture is off. Start capturing on the App Isolation tab so listeners can hear something.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, listenUrl)
                }
                context.startActivity(Intent.createChooser(send, "Share listening link"))
            }) {
                Text("Share link")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun generateQrCodeBitmap(content: String, sizePx: Int): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        }
    }
    return bmp
}
