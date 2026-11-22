package com.walltrace.ar.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

class ImageImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Decodes user images safely: EXIF orientation, HEIC/HEIF (Android 9+), WEBP, PNG transparency,
 * and memory-bounded downscaling for very large photos.
 */
object ImageLoader {

    /** Largest texture side we upload. 2048 is guaranteed by OpenGL ES 3.0 on every device. */
    const val MAX_TEXTURE_SIDE = 2048
    const val THUMBNAIL_SIDE = 320
    private const val MAX_FILE_BYTES = 150L * 1024 * 1024

    /**
     * Copies the picked document into app storage so the project keeps working even after the
     * temporary picker permission expires.
     */
    @Throws(ImageImportException::class)
    fun copyToFile(resolver: ContentResolver, uri: Uri, destination: File) {
        val type = resolver.getType(uri)
        if (type != null && !type.startsWith("image/")) {
            throw ImageImportException("That file isn't an image.")
        }
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, destination.name + ".part")
        try {
            val input = resolver.openInputStream(uri)
                ?: throw ImageImportException("Couldn't open the selected image.")
            input.use { ins ->
                FileOutputStream(temp).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = ins.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_FILE_BYTES) {
                            throw ImageImportException("This image is too large (over 150 MB).")
                        }
                        out.write(buffer, 0, read)
                    }
                    if (total == 0L) throw ImageImportException("The selected file is empty.")
                }
            }
            if (destination.exists() && !destination.delete()) {
                throw ImageImportException("Couldn't replace the existing image.")
            }
            if (!temp.renameTo(destination)) {
                throw ImageImportException("Couldn't save the image.")
            }
        } catch (e: IOException) {
            throw ImageImportException("Couldn't read the selected image.", e)
        } catch (e: SecurityException) {
            throw ImageImportException("Permission to read this image was denied.", e)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /**
     * Decodes [file] into an ARGB_8888 software bitmap whose longest side is at most [maxSide],
     * with EXIF orientation applied and aspect ratio preserved.
     */
    @Throws(ImageImportException::class)
    fun decode(file: File, maxSide: Int = MAX_TEXTURE_SIDE): Bitmap {
        if (!file.exists() || file.length() == 0L) throw ImageImportException("Image file is missing.")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) decodeWithImageDecoder(file, maxSide)
            else decodeLegacy(file, maxSide)
        } catch (e: ImageImportException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw ImageImportException("Not enough memory to open this image.", e)
        } catch (e: IOException) {
            throw ImageImportException("This image format isn't supported on this device.", e)
        } catch (e: IllegalArgumentException) {
            throw ImageImportException("This image appears to be corrupted.", e)
        }
    }

    private fun decodeWithImageDecoder(file: File, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(file)
        // ImageDecoder applies EXIF orientation automatically.
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val (w, h) = targetSize(info.size.width, info.size.height, maxSide)
            if (w != info.size.width || h != info.size.height) decoder.setTargetSize(w, h)
        }.let { ensureArgb(it) }
    }

    private fun decodeLegacy(file: File, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ImageImportException("This image format isn't supported on this Android version.")
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = inSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw ImageImportException("Couldn't decode this image.")

        val rotation = exifRotationMatrix(file)
        val (tw, th) = targetSize(decoded.width, decoded.height, maxSide)
        val scale = tw.toFloat() / decoded.width
        if (scale != 1f) rotation.postScale(scale, scale)
        val result = if (rotation.isIdentity) decoded else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, rotation, true).also {
                if (it !== decoded) decoded.recycle()
            }
        }
        return ensureArgb(result)
    }

    private fun exifRotationMatrix(file: File): Matrix {
        val matrix = Matrix()
        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (e: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }
        }
        return matrix
    }

    private fun ensureArgb(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw ImageImportException("Couldn't convert this image.")
        bitmap.recycle()
        return copy
    }

    /** Target size whose longest side is at most [maxSide], preserving aspect ratio. */
    fun targetSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && maxSide > 0) { "Invalid image size" }
        val longest = max(width, height)
        if (longest <= maxSide) return width to height
        val scale = maxSide.toDouble() / longest
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** Largest power-of-two sample size that keeps the decoded image at least [maxSide] on its longest side. */
    fun inSampleSize(width: Int, height: Int, maxSide: Int): Int {
        require(width > 0 && height > 0 && maxSide > 0) { "Invalid image size" }
        var sample = 1
        val longest = max(width, height)
        while (longest / (sample * 2) >= maxSide) sample *= 2
        return sample
    }
}
