package com.amg.dopplerultrasound

import android.content.Context
import android.media.*
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioDecoder {
    fun decodeToPCM(context: Context, uri: Uri): Pair<FloatArray, Int>? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var sampleRate = 0
        var channelCount = 1

        try {
            extractor.setDataSource(context, uri, null)

            // Buscar pista de audio
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                if (mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                    decoder = MediaCodec.createDecoderByType(mime).apply {
                        configure(format, null, null, 0)
                        start()
                    }
                    break
                }
            }

            decoder ?: return null

            val outputData = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            var outputFormat: MediaFormat? = null

            // Proceso de decodificación
            while (!sawOutputEOS) {
                // Parte 1: Alimentar datos al decodificador
                if (!sawInputEOS) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)

                        if (sampleSize < 0) {
                            sawInputEOS = true
                            decoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                        } else {
                            decoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                // Parte 2: Recoger datos decodificados
                when (val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = decoder.outputFormat
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No hay datos disponibles temporalmente
                    }
                    else -> {
                        if (outputBufferIndex >= 0) {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEOS = true
                            }

                            val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)!!
                            if (bufferInfo.size > 0) {
                                val chunk = ByteArray(bufferInfo.size)
                                outputBuffer.get(chunk)
                                outputData.write(chunk)
                            }
                            decoder.releaseOutputBuffer(outputBufferIndex, false)
                        }
                    }
                }
            }

            // Convertir a FloatArray
            return convertToFloatArray(
                pcmData = outputData.toByteArray(),
                sampleRate = sampleRate,
                channelCount = channelCount
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            extractor.release()
            decoder?.stop()
            decoder?.release()
        }
    }

    private fun convertToFloatArray(
        pcmData: ByteArray,
        sampleRate: Int,
        channelCount: Int
    ): Pair<FloatArray, Int>? {
        if (pcmData.isEmpty()) return null

        return try {
            // Convertir PCM 16-bit a FloatArray
            val shortArray = ShortArray(pcmData.size / 2)
            ByteBuffer.wrap(pcmData)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(shortArray)

            // Convertir a mono si es estéreo
            val floatArray = FloatArray(shortArray.size)
            for (i in shortArray.indices) {
                floatArray[i] = shortArray[i] / 32768f // Normalizar a [-1, 1]
            }

            // Reducir a mono si hay múltiples canales
            val monoArray = if (channelCount > 1) {
                FloatArray(floatArray.size / channelCount).apply {
                    for (i in indices) {
                        var sum = 0f
                        for (j in 0 until channelCount) {
                            sum += floatArray[i * channelCount + j]
                        }
                        this[i] = sum / channelCount
                    }
                }
            } else {
                floatArray
            }

            Pair(monoArray, sampleRate)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}