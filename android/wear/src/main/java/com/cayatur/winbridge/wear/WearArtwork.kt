package com.cayatur.winbridge.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream

/** Cached cover art shared by the Wear app and tiles. */
object WearArtwork {
    const val DATA_KEY = "artwork"
    private const val FILE_NAME = "wear-artwork.jpg"

    private val _bitmap = MutableStateFlow<Bitmap?>(null)
    val bitmap: StateFlow<Bitmap?> = _bitmap.asStateFlow()

    fun load(context: Context): Bitmap? {
        val image = runCatching { BitmapFactory.decodeFile(File(context.filesDir, FILE_NAME).absolutePath) }.getOrNull()
        _bitmap.value = image
        return image
    }

    suspend fun updateFromAsset(context: Context, asset: Asset?) {
        if (asset == null) {
            clear(context)
            return
        }
        val image = runCatching {
            val response = Wearable.getDataClient(context).getFdForAsset(asset).await()
            response.inputStream?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return
        store(context, image)
    }

    fun current(context: Context): Bitmap? = _bitmap.value ?: load(context)

    private fun store(context: Context, bitmap: Bitmap) {
        runCatching {
            FileOutputStream(File(context.filesDir, FILE_NAME)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
        }
        _bitmap.value = bitmap
    }

    private fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
        _bitmap.value = null
    }
}
