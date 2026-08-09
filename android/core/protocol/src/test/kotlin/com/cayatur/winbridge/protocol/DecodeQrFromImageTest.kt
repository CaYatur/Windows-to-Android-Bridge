package com.cayatur.winbridge.protocol

import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Reads a pairing QR out of a screenshot.
 *
 * This exists so the Bluetooth path can be exercised on real hardware without a
 * person holding a phone up to a monitor: capture the pairing window, decode the
 * payload here, hand it to the app. Skips itself when no screenshot is present.
 *
 *   WINBRIDGE_QR_IMAGE=C:\path\to\screen.png \
 *   ./gradlew :core:protocol:test --tests '*DecodeQrFromImageTest*' -i
 */
class DecodeQrFromImageTest {

    @Test
    fun `decode pairing payload from a screenshot`() {
        val path = System.getenv("WINBRIDGE_QR_IMAGE")
        if (path.isNullOrBlank()) {
            println("SKIP: set WINBRIDGE_QR_IMAGE to a screenshot containing the pairing code")
            return
        }

        val file = File(path)
        if (!file.exists()) {
            println("SKIP: $path does not exist")
            return
        }

        val image = ImageIO.read(file) ?: run {
            println("FAIL: could not read $path as an image")
            return
        }

        val source = com.google.zxing.client.j2se.BufferedImageLuminanceSource(image)
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))

        val result = try {
            com.google.zxing.MultiFormatReader().decode(
                bitmap,
                mapOf(
                    com.google.zxing.DecodeHintType.TRY_HARDER to true,
                    com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to
                        listOf(com.google.zxing.BarcodeFormat.QR_CODE),
                ),
            )
        } catch (e: Exception) {
            println("FAIL: no QR code found in $path (${e.javaClass.simpleName})")
            return
        }

        println("PAYLOAD_BEGIN")
        println(result.text)
        println("PAYLOAD_END")
    }
}
