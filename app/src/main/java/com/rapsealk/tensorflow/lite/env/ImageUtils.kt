package com.rapsealk.tensorflow.lite.env

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ImageUtils {

    companion object {
        private val TAG = ImageUtils::class.java.simpleName

        const val kMaxChannelValue = 262143

        fun getYUVByteSize(width: Int, height: Int): Int {
            val ySize = width * height
            val uvSize = ((width + 1) / 2) * ((height + 1) / 2) * 2
            return ySize + uvSize
        }

        fun saveBitmap(bitmap: Bitmap, filename: String = "preview.png") {
            val root = Environment.getExternalStorageDirectory().absolutePath + File.separator + "tensorflow"
            Log.i(TAG, "Saving ${bitmap.width}x${bitmap.height} to ${root}.")
            val myDir = File(root)

            if (!myDir.mkdirs()) {
                Log.e(TAG, "Make dir failed!")
            }

            val file = File(myDir, filename)
            if (file.exists()) {
                file.delete()
            }

            try {
                val out = FileOutputStream(file)
                bitmap.compress(Bitmap.CompressFormat.PNG, 99, out)
                out.flush()
                out.close()
            } catch (e: Exception) {
                Log.e(TAG, "Exception!", e)
            }
        }

        fun convertYUV420SPToARGB8888(input: ByteArray, width: Int, height: Int, output: IntArray) {
            val frameSize = width * height
            var yp = 0
            for (j in 0 until height) {
                var uvp = frameSize + j.shr(1) * width
                var u = 0
                var v = 0

                for (i in 0 until width) {
                    val y: Int = input[yp].toInt() and 0xFF
                    if ((i and 1) == 0) {
                        v = input[uvp++].toInt() and 0xFF
                        u = input[uvp++].toInt() and 0xFF
                    }
                    output[yp++] = YUV2RGB(y, u, v)
                }
            }
        }

        private fun YUV2RGB(_y: Int, _u: Int, _v: Int): Int {
            // Adjust and check YUV values
            val y = max(_y - 16, 0)
            val u = _u - 128
            val v = _v - 128

            // This is the floating point equivalent. We do the conversion in integer
            // because some Android devices do not have floating point in hardware.
            // nR = (int)(1.164 * nY + 2.018 * nU);
            // nG = (int)(1.164 * nY - 0.813 * nV - 0.391 * nU);
            // nB = (int)(1.164 * nY + 1.596 * nV);
            val y1192 = y * 1192
            var r: Int = (y1192 + 1634 * v)
            var g: Int = (y1192 - 833 * v - 400 * u)
            var b: Int = (y1192 + 2066 * u)

            // Clipping RGB values to be inside boundaries [ 0, kMaxChannelValue ]
            r = min(kMaxChannelValue, max(r, 0))
            g = min(kMaxChannelValue, max(g, 0))
            b = min(kMaxChannelValue, max(b, 0))

            return 0xFF000000
                .or(r.toLong().shl(6).and(0xFF0000))
                .or(g.toLong().shr(2).and(0xFF00))
                .or(b.toLong().shr(10).and(0xFF))
                .toInt()
        }

        fun convertYUV420ToARGB8888(
            yData: ByteArray,
            uData: ByteArray,
            vData: ByteArray,
            width: Int,
            height: Int,
            yRowStride: Int,
            uvRowStride: Int,
            uvPixelStride: Int,
            out: IntArray
        ) {
            var yp = 0
            for (j in 0 until height) {
                val pY = yRowStride * j
                val pUV = uvRowStride * j.shr(1)

                for (i in 0 until width) {
                    val uvOffset = pUV + i.shr(1) * uvPixelStride
                    out[yp++] = YUV2RGB(0xFF and yData[pY+i].toInt(), 0xFF and uData[uvOffset].toInt(), 0xFF and vData[uvOffset].toInt())
                }
            }
        }

        /**
         * Returns a transformation matrix from one reference frame into another. Handles cropping (if
         * maintaining aspect ratio is desired) and rotation.
         *
         * @param srcWidth Width of source frame.
         * @param srcHeight Height of source frame.
         * @param dstWidth Width of destination frame.
         * @param dstHeight Height of destination frame.
         * @param applyRotation Amount of rotation to apply from one frame to another. Must be a multiple
         *     of 90.
         * @param maintainAspectRatio If true, will ensure that scaling in x and y remains constant,
         *     cropping the image if necessary.
         * @return The transformation fulfilling the desired requirements.
         */
        fun getTransformationMatrix(srcWidth: Int,
                                    srcHeight: Int,
                                    dstWidth: Int,
                                    dstHeight: Int,
                                    applyRotation: Int,
                                    maintainAspectRatio: Boolean): Matrix {
            val matrix = Matrix()

            if (applyRotation != 0) {
                if (applyRotation % 90 != 0) {
                    Log.w(TAG, "Rotation of ${applyRotation} %% 90 != 0")
                }

                // Translate so center of image is at origin.
                matrix.postTranslate(-srcWidth / 2.0f, -srcHeight / 2.0f)

                // Rotate around origin.
                matrix.postRotate(applyRotation.toFloat())
            }

            // Account for the already applied rotation, if any, and then determine how
            // much scaling is needed for each axis.
            val transpose = (abs(applyRotation) + 90) % 180 == 0

            val inWidth = if (transpose) srcHeight else srcWidth
            val inHeight = if (transpose) srcWidth else srcHeight

            // Apply scaling if necessary.
            if (inWidth != dstWidth || inHeight != dstHeight) {
                val scaleFactorX: Float = dstWidth / inWidth.toFloat()
                val scaleFactorY: Float = dstHeight / inHeight.toFloat()

                if (maintainAspectRatio) {
                    // Scale by minimum factor so that dst is filled completely while
                    // maintaining the aspect ratio. Some image may fall off the edge.
                    val scaleFactor = max(scaleFactorX, scaleFactorY)
                    matrix.postScale(scaleFactor, scaleFactor)
                } else {
                    // Scale exactly to fill dst from src.
                    matrix.postScale(scaleFactorX, scaleFactorY)
                }
            }

            if (applyRotation != 0) {
                // Translate back from origin centered reference to destination frame.
                matrix.postTranslate(dstWidth / 2.0f, dstHeight / 2.0f)
            }

            return matrix
        }
    }
}