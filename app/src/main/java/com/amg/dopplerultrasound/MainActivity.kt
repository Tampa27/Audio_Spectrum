package com.amg.dopplerultrasound

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.ContentValues.TAG
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.log
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.properties.Delegates
import androidx.core.graphics.createBitmap
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.collections.copyOf
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    companion object {
        var MAX_ARRAY_SIZE = 110//200
    }


    private lateinit var data: Array<DoubleArray>
    private var currentIndex = 0
    private var frequenciesCount by Delegates.notNull<Int>()
    private var ip = 0.00
    private var qm = 0.00
    private var maxDb = -20
    private var gain = 1.0
    private val maxFactor = 3.0
    private var Fs = 11025
    private var grayScale:Boolean = true
    private val f0 = 8000000.0 // Frecuencia de operacion del transductor
     // Velocidad del ultrasonido en sangre cm/s
    private val theta = 60.0 //Angulo de inclinacion entre el haz ultrasonico y la direccion del flujo sanguineo

    private val textHeight = 30
    private lateinit var imageView: ImageView
    private lateinit var seekdB:SeekBar
    private lateinit var checkEnvol:CheckBox
    private lateinit var dBText: TextView

    private lateinit var unit:String
    private var windowSize = 256//512
    private var samplesBetweenWindows = 0
    private val textWidth = 60
    private val c = 154000.0
    private val K = 0.9071
    private var isRunning:Boolean = false
    private var isLoaded:Boolean = false
    val overlap = 0.5 // 50%
    private lateinit var bmp: Bitmap
    private var ipSize:Int=0
    private val fmList = mutableListOf<Double>()
    private val fmaxList = mutableListOf<Double>()

    private var yAnt = 0.0f



    private lateinit var sharedPreferences: SharedPreferences

    val shapeType = "rect"  // "rect", "triangle", "wave"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else
        {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }


        imageView = findViewById(R.id.imageView)
        dBText = findViewById(R.id.dbText)
        seekdB = findViewById(R.id.umbralSeek)

        checkEnvol = findViewById(R.id.checkEnvol)


        seekdB.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                maxDb = -progress
                dBText.text = "${getString(R.string.umbral)} $maxDb dB"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}

        })

        imageView.setOnClickListener {
            if (!isRunning) {
                isRunning = true
                recordAudioWithPermissions()
            } else {
                isRunning = false
            }
        }
        imageView.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (imageView.width > 0 && imageView.height > 0) {
                        imageView.viewTreeObserver.removeOnGlobalLayoutListener(this)

                        // Las dimensiones ya están disponibles (>0)
                        Log.d("ImageView", "Ancho: ${imageView.width}, Alto: ${imageView.height}")
                        bmp = createBitmap(imageView.width, imageView.height)
                        bmp.eraseColor(Color.BLACK)
                    }
                }
            }
        )
        loadPreferences()

        Toast.makeText(
            applicationContext,
            "Toca la pantalla para comenzar o detener el procesamiento",
            //"Click on screen to start/stop audio capture",
            Toast.LENGTH_LONG
        ).show()

        Handler(Looper.getMainLooper()).postDelayed({
            isRunning = true
            recordAudioWithPermissions()
        }, 1000)
    }

    private fun loadPreferences() {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        ipSize = (1f/(windowSize.toFloat()/Fs)).toInt()
        unit = sharedPreferences.getString("units","kHz")!!
        Fs = sharedPreferences.getString("fs","11025")!!.toInt()
        windowSize = sharedPreferences.getString("window","512")!!.toInt()
        //val samplesBetweenWindows = (windowSize * overlap).toInt()
        val time = sharedPreferences.getString("time","4")!!.toInt()+1
        val ms = (windowSize.toFloat()/Fs)
        grayScale = sharedPreferences.getBoolean("gray",false)
        //MAX_ARRAY_SIZE = ((time.toFloat()/ms)).toInt()
        samplesBetweenWindows = (windowSize * overlap).toInt()
    //cargarPacientes()
    }

    override fun onResume() {
        super.onResume()
        // Registrar listener para cambios en preferencias
        loadPreferences()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

    }

    @SuppressLint("MissingPermission")
    fun recordAudio() {
        val RECORDER_SAMPLERATE = Fs//8000
        val RECORDER_CHANNELS: Int = AudioFormat.CHANNEL_IN_MONO
        val RECORDER_AUDIO_ENCODING: Int = AudioFormat.ENCODING_PCM_16BIT
        val butterSize = max(AudioRecord.getMinBufferSize(
            RECORDER_SAMPLERATE,
            RECORDER_CHANNELS, RECORDER_AUDIO_ENCODING
        ),windowSize)

        var currentPosition = 0
        val buffer = ShortArray(butterSize)
        frequenciesCount = windowSize//getMinimalPowerOf2(butterSize)
        data = Array(MAX_ARRAY_SIZE) {
            DoubleArray(frequenciesCount) { 0.0 }
        }
        val x = DoubleArray(frequenciesCount)
        var y = DoubleArray(frequenciesCount)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            RECORDER_SAMPLERATE, RECORDER_CHANNELS,
            RECORDER_AUDIO_ENCODING, frequenciesCount
        )
        val fft = FFT(frequenciesCount)
        lifecycleScope.launch (Dispatchers.Default){
        //thread {
            recorder.startRecording()
            //audioTrack.play()
            //audioTrack.setVolume(AudioTrack.getMaxVolume())
            while (isRunning) {
                // gets the voice output from microphone to byte format
                val bytesRead = recorder.read(buffer, 0, frequenciesCount)
                /*if (bytesRead > 0) {
                    audioTrack.write(buffer, 0, bytesRead)
                }*/
                currentPosition += samplesBetweenWindows

                for (fr in 0 until frequenciesCount) {
                    x[fr] = buffer[fr] / 16384.0
                    y[fr] = 0.0
                }

                val xWindowed = x.copyOf()
                fft.hanningWindow(xWindowed)
                //currentPosition += samplesBetweenWindows
                fft.process(xWindowed, y)
                val limit = Math.pow(10.0,maxDb*maxFactor/20)
                for (fr in 0 until frequenciesCount) {
                    val mag_abs = Math.abs(y[fr])
                    data[currentIndex][fr] = mag_abs
                    val magdB = 10 * log(data[currentIndex][fr], 10.0)
                    if (magdB < maxDb)
                        data[currentIndex][fr] = 0.0
                    else{
                        val v = data[currentIndex][fr]//if (data[currentIndex][fr] < limit) limit else data[currentIndex][fr]
                        data[currentIndex][fr]  = v
                    }
                }
                //rectaCompens(data[currentIndex])
                val fmed = fMeans(data[currentIndex])
                val bw = bandWidth(data[currentIndex],fmed)
                val fmax = fmed+bw
                if (fmList.size < ipSize){
                    fmList.add(fmed.toDouble())
                    fmaxList.add(fmax.toDouble())
                }
                else{
                    if (currentIndex % ipSize == 0){
                        //val fmax = fmList.max()
                        val fmax = fmaxList.sum()/fmaxList.size
                        fmList.removeAt(0)
                        fmList.add(fmed.toDouble())
                        fmaxList.removeAt(0)
                        fmaxList.add(fmax.toDouble())
                    }
                }

                //updateGlobalMinMax()
                withContext(Dispatchers.Main) {
                    render()
                }
                currentIndex = if (currentIndex == (MAX_ARRAY_SIZE - 1)) 0 else currentIndex + 1
            }
            recorder.stop()
            //audioTrack.stop()
        }
    }

    private fun render() {
        if (this::bmp.isInitialized && bmp.width > 0 && bmp.height > 0) {
            val c = Canvas(bmp)
            val paint =Paint().apply {
                isAntiAlias = true
                isDither = true  // Mejora la gradación de colores
                style = Paint.Style.FILL
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            val rectWidth = 1.0f * (bmp.width) / (MAX_ARRAY_SIZE)
            val rectHeight = 1.0f * bmp.height / (frequenciesCount / 2.0f)


            val halfOfFrequencies = frequenciesCount / 2
            paint.color = Color.WHITE
            paint.textSize = 30f
            for (i in 0..Fs / 2 step 1000) {
                val yt = bmp.height * (1f - i.toFloat() / (Fs / 2))
                if (i < (Fs / 2 - 1000)) {
                    if (unit == "cm/s") {
                        var vel = getVelocidad(i.toDouble())
                        vel = Math.round(vel / 10.0) * 10.0
                        c.drawText(" ${String.format("%.0f", vel)}", 0f, yt - textHeight, paint)
                    } else {
                        c.drawText(" " + i / 1000, 0f, yt - textHeight, paint)
                    }
                } else {
                    paint.textSize = 25f
                    c.drawText(unit, 0f, yt, paint)
                }
            }
            val max_time = ((windowSize.toFloat() / Fs) * MAX_ARRAY_SIZE).toInt() + 1
            for (i in 0 until max_time) {
                val xt = (bmp.width - textHeight) * (1f - (i.toFloat() / max_time))
                c.drawText(" " + i + "s", xt, bmp.height.toFloat(), paint)
            }
            val gradientSize = 10
            repeat(MAX_ARRAY_SIZE) { x ->
                val index =
                    if (currentIndex + x + 1 < MAX_ARRAY_SIZE) currentIndex + x + 1 else currentIndex + x + 1 - MAX_ARRAY_SIZE
                val min = data[index].min()
                val max = data[index].max()
                val limit = Math.pow(10.0,maxDb*maxFactor/20)


                for (y in 0 until halfOfFrequencies) {
                    paint.color = if (grayScale)
                        getGrayByValueSimple(data[index][y], min, max)
                    else
                        getColorByValue2(data[index][y], min, max)
                    /*val magdB = 20 * log(data[index][y], 10.0)
                    if (magdB < maxDb)
                        paint.color = if (grayScale)
                            getGrayByValueSimple(0.0, min, max)
                                    else
                            getColorByValue2(0.0, min, max)
                    else {
                        val v = if (data[index][y] < limit) limit else data[index][y]
                        val value = v*gain
                        paint.color = if (grayScale)
                            getGrayByValueSimple(value, min, max)
                        else
                            getColorByValue2(value, min, max)
                    }*/
                    c.drawRect(
                        x * (rectWidth) + textWidth,
                        (halfOfFrequencies - y) * rectHeight - textHeight,
                        (x + 1) * (rectWidth) + textWidth,
                        (halfOfFrequencies - y + 1) * rectHeight - textHeight,
                        paint
                    )

                    // Guardar color actual para la siguiente transición
                }

                if (checkEnvol.isChecked) {
                    paint.color = Color.YELLOW
                    paint.strokeWidth = 4f
                    val fMedia = fMeans(data[index])
                    val yAct =
                        (halfOfFrequencies - fMedia/*maxPox!!.index*/) * rectHeight - textHeight
                    c.drawLine(
                        x * (rectWidth) + textWidth,
                        yAnt,
                        (x + 1) * (rectWidth) + textWidth,
                        yAct,
                        paint
                    )
                    yAnt = yAct
                }
            }
            runOnUiThread {
                imageView.setImageBitmap(bmp)
                imageView.invalidate()
            }
        }
    }


    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted ) {

                recordAudio()
            } else {

            }
        }

    @RequiresApi(Build.VERSION_CODES.M)
    fun recordAudioWithPermissions() {

        when {
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                recordAudio()
            }

            shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO) -> {
                Toast.makeText(
                    applicationContext,
                    "",
                    Toast.LENGTH_SHORT
                ).show()
            }

            else -> requestPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun fMeans(freqMags:DoubleArray):Int{
        val halfOfFrequencies = frequenciesCount/2
        var fMedia = 0
        val Pi = freqMags.foldIndexed(0.0){
            index,sum,value -> if(index < halfOfFrequencies) sum+value*value else sum
        }
        val fiPi = freqMags.foldIndexed(0.0){
            index,sum,value -> if(index < halfOfFrequencies) sum+(value*value*index) else sum
        }
        fMedia = (fiPi/Pi).toInt()
        return fMedia;
    }

    private fun bandWidth(freqMags:DoubleArray,fMedia:Int):Int{
        val halfOfFrequencies = frequenciesCount/2
        var bw = 0
        val Pi = freqMags.foldIndexed(0.0){
                index,sum,value -> if(index < halfOfFrequencies) sum+value*value else sum
        }
        val numerador  = freqMags.foldIndexed(0.0){
                index,sum,value -> if(index < halfOfFrequencies) sum+(value*value*(index-fMedia)*(index-fMedia)) else sum
        }
        bw = sqrt(numerador/Pi).toInt()
        return bw
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val itemId = item.itemId
        if (itemId == R.id.action_play) {
            isRunning = true
            recordAudioWithPermissions()
        } else if (itemId == R.id.action_pause) {
            isRunning = false
        }
        else if (itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        else if (itemId == R.id.action_screenshot){
            val bitmap = getBitmapFromUiView(imageView)
            //function call, pass the bitmap to save it
            saveBitmapImage(bitmap)
        }
        else if (itemId == R.id.action_open){
            openFilePicker()
        }
        return super.onOptionsItemSelected(item)
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "audio/wav"  // Solo archivos WAV
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/wav", "audio/x-wav"))
        }
        filePickerLauncher.launch(intent)
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                Thread {
                    processSelectedAudioFile(uri)
                }.start()
            }
        }
    }

    private fun processSelectedAudioFile(uri: Uri) {
        lifecycleScope.launch (Dispatchers.Default) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val (floatArray, sampleRate) = AudioDecoder.decodeToPCM(applicationContext, uri)
                        ?: throw Exception("Error decodificando audio")
                    isRunning = false
                    processAudioData(floatArray)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    // Mostrar error
                }
            }
        }
    }

    private fun processAudioData(floatArray: FloatArray) {
        var currentPosition = 0
        val RECORDER_SAMPLERATE = Fs//8000
        val RECORDER_AUDIO_ENCODING: Int = AudioFormat.ENCODING_PCM_16BIT
        frequenciesCount = windowSize//getMinimalPowerOf2(butterSize)
        data = Array(MAX_ARRAY_SIZE) {
            DoubleArray(frequenciesCount) { 0.0 }
        }
        var x = DoubleArray(frequenciesCount)
        var y = DoubleArray(frequenciesCount)

        val fft = FFT(frequenciesCount)

        val audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            RECORDER_SAMPLERATE,
            AudioFormat.CHANNEL_OUT_MONO,
            RECORDER_AUDIO_ENCODING,
            frequenciesCount,
            AudioTrack.MODE_STREAM
        )
        //audioTrack.play()
        //audioTrack.setVolume(AudioTrack.getMaxVolume())
        while (currentPosition+frequenciesCount < floatArray.size) {
            for (fr in 0 until frequenciesCount) {
                x[fr] = floatArray[fr+currentPosition].toDouble()
                y[fr] = 0.0
            }
            //audioTrack.write(audioBytes, currentPosition, frequenciesCount*2)
            fft.process(x, y)

            for (fr in 0 until frequenciesCount) {
                val mag_abs = Math.abs(y[fr])
                data[currentIndex][fr] = mag_abs
            }
            //rectaCompens(data[currentIndex])
            val fmed = fMeans(data[currentIndex])
            val bw = bandWidth(data[currentIndex],fmed)
            val fmax = fmed+bw
            if (fmList.size < ipSize){
                fmList.add(fmed.toDouble())
                fmaxList.add(fmax.toDouble())
            }
            else{
                if (currentIndex % ipSize == 0){
                    val fmm = fmList.sum()/fmList.size
                    //val fmax = fmList.max()
                    val fmax = fmaxList.sum()/fmaxList.size
                    fmList.removeAt(0)
                    fmList.add(fmed.toDouble())
                    fmaxList.removeAt(0)
                    fmaxList.add(fmax.toDouble())
                }
            }


            render()


            currentPosition += (frequenciesCount*4)
            currentIndex = if (currentIndex == (MAX_ARRAY_SIZE - 1)) 0 else currentIndex + 1
        }
        //audioTrack.stop()

    }

    private fun getAudioFormat(header: ByteArray): Int {
        // Offset 34-35: bits por muestra
        return ((header[34].toInt() and 0xFF) or
                ((header[35].toInt() and 0xFF) shl 8))
    }

    private fun convertPCM16ToFloat(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()

        val shorts = ShortArray(buffer.remaining())
        buffer.get(shorts)

        return FloatArray(shorts.size) { i ->
            shorts[i] / 1f
        }
    }

    private fun convertPCM8ToFloat(bytes: ByteArray): FloatArray {
        return FloatArray(bytes.size) { i ->
            (bytes[i].toInt()) / 1f
        }
    }

    private fun convertPCM32ToFloat(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asIntBuffer()

        val ints = IntArray(buffer.remaining())
        buffer.get(ints)

        return FloatArray(ints.size) { i ->
            ints[i] / 1f
        }
    }

    fun getVelocidad(frecuencia:Double):Double{
        return (c/f0)*frecuencia
    }

    /**Get Bitmap from any UI View
     * @param view any UI view to get Bitmap of
     * @return returnedBitmap the bitmap of the required UI View */
    private fun getBitmapFromUiView(view: View?): Bitmap {
        //Define a bitmap with the same size as the view
        val returnedBitmap = Bitmap.createBitmap(view!!.width, view.height, Bitmap.Config.ARGB_8888)
        //Bind a canvas to it
        val canvas = Canvas(returnedBitmap)
        //Get the view's background
        val bgDrawable = view.background
        if (bgDrawable != null) {
            //has background drawable, then draw it on the canvas
            bgDrawable.draw(canvas)
        } else {
            //does not have background drawable, then draw white background on the canvas
            canvas.drawColor(Color.WHITE)
        }
        // draw the view on the canvas
        view.draw(canvas)

        //return the bitmap
        return returnedBitmap
    }


    /**Save Bitmap To Gallery
     * @param bitmap The bitmap to be saved in Storage/Gallery*/
    private fun saveBitmapImage(bitmap: Bitmap) {
        val timestamp = System.currentTimeMillis()

        //Tell the media scanner about the new file so that it is immediately available to the user.
        val values = ContentValues()
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        values.put(MediaStore.Images.Media.DATE_ADDED, timestamp)
        var image_name = "$timestamp"

        values.put(MediaStore.Images.Media.DISPLAY_NAME,image_name )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.DATE_TAKEN, timestamp)
            values.put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "Pictures/" + getString(R.string.app_name)
            )
            values.put(MediaStore.Images.Media.IS_PENDING, true)
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                try {
                    val outputStream = contentResolver.openOutputStream(uri)
                    if (outputStream != null) {
                        try {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                            outputStream.close()
                        } catch (e: Exception) {
                            Log.e(TAG, "saveBitmapImage: ", e)
                        }
                    }
                    values.put(MediaStore.Images.Media.IS_PENDING, false)
                    contentResolver.update(uri, values, null, null)

                    Toast.makeText(this, "Saved...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "saveBitmapImage: ", e)
                }
            }
        } else {
            val imageFileFolder = File(
                Environment.getExternalStorageDirectory()
                    .toString() + '/' + getString(R.string.app_name)
            )
            if (!imageFileFolder.exists()) {
                imageFileFolder.mkdirs()
            }
            val mImageName = "$timestamp.png"
            val imageFile = File(imageFileFolder, mImageName)
            try {
                val outputStream: OutputStream = FileOutputStream(imageFile)
                try {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    outputStream.close()
                } catch (e: Exception) {
                    Log.e(TAG, "saveBitmapImage: ", e)
                }
                values.put(MediaStore.Images.Media.DATA, imageFile.absolutePath)
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

                Toast.makeText(this, "Saved...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "saveBitmapImage: ", e)
            }
        }
    }
}
fun getColorByValue(value: Double, min: Double, max: Double): Int {
    if (min == 0.0 && max == 0.0) {
        return Color.rgb(0, 0, 0)
    }
    val normalizedValue = (value - min) / (max - min)
    val freq = Math.PI * 2 / 1.5
    val red = ((sin(freq * normalizedValue + 0) * 127 + 128) * normalizedValue).roundToInt()
    val green = ((sin(freq * normalizedValue + 2) * 127 + 128) * normalizedValue).roundToInt()
    val blue = ((sin(freq * normalizedValue + 4) * 127 + 128) * normalizedValue).roundToInt()

    /*val freq = Math.PI.toFloat() * 2/1.5 // Frecuencia para cubrir 0-1

    val red = ((cos(freq * normalizedValue) * 127.5 + 127.5) * normalizedValue).roundToInt()
    val green = (sin(freq * normalizedValue) * 127.5 * normalizedValue + 127.5 * normalizedValue).roundToInt()
    val blue = ((sin(freq * normalizedValue - Math.PI.toFloat()) * 127.5 + 127.5) * normalizedValue).roundToInt()

     */
    return Color.rgb(red, green, blue)
}
// Para HSL si lo eliges después

fun getColorByValue2(value: Double, min: Double, max: Double): Int {
    if (min == max) {
        return Color.rgb(0, 0, 0)
    }
    var t = (value - min) / (max - min)
    if (t < 0.0) t = 0.0
    if (t > 1.0) t = 1.0

    val r: Int
    val g: Int
    val b: Int

    when {
        t <= 0.2 -> { // Negro a Azul
            val u = t / 0.2
            r = 0
            g = 0
            b = (u * 255).toInt()
        }
        t <= 0.4 -> { // Azul a Verde
            val u = (t - 0.2) / 0.2
            r = 0
            g = (u * 255).toInt()
            b = (255 - u * 255).toInt()
        }
        /*t <= 0.6 -> { // Verde a Amarillo
            val u = (t - 0.4) / 0.2
            r = (u * 255).toInt()
            g = 255
            b = 0
        }
        t <= 0.8 -> { // Amarillo a Naranja
            val u = (t - 0.6) / 0.2
            r = 255
            g = (255 - u * 90).toInt() // 255 -> 165
            b = 0
        }*/
        else -> { // Naranja a Rojo
            val u = (t - 0.8) / 0.2
            r = 255
            g = (165 - u * 165).toInt() // 165 -> 0
            b = 0
        }
    }

    return Color.rgb(r, g, b)
}

fun getColorByValue1(value: Double, min: Double, max: Double): Int {
    if (min == 0.0 && max == 0.0) {
        return Color.rgb(0, 0, 0) // Negro si no hay rango
    }
    if (value < min) return Color.rgb(0,0,0) // O algún color para valores fuera de rango inferior
    if (value > max) return Color.rgb(255,255,255) // O algún color para valores fuera de rango superior


    // Normalizar el valor entre 0 y 1
    // Si max == min (pero no ambos cero), normalizar a 0.5 o 1 para evitar división por cero
    // y darle un color consistente. O puedes devolver un color fijo.
    val normalizedValue = if (max - min == 0.0) 0.5 else (value - min) / (max - min)

    // Ajusta la frecuencia para determinar qué tan rápido cambian los colores
    // Un valor más alto significa cambios de color más rápidos a medida que normalizedValue aumenta.
    val freq = (PI * 2).toFloat() // Una onda completa a lo largo del rango normalizado

    // Amplitud y desplazamiento para asegurar que los colores estén en el rango 0-255
    // y sean brillantes.
    // La amplitud es 127.5, el desplazamiento es 127.5. Suma = 255.
    // (sin(angle) * 127.5 + 127.5) dará un rango de 0 a 255.

    // Desfasar las ondas para diferentes colores
    // Puedes experimentar con estos desfases para diferentes paletas de colores
    val redPhase = 0f
    val greenPhase = (2 * PI / 3).toFloat() // Desfase de 120 grados
    val bluePhase = (4 * PI / 3).toFloat()  // Desfase de 240 grados

    var red = (sin(freq * normalizedValue + redPhase) * 127.5 + 127.5).roundToInt()
    var green = (sin(freq * normalizedValue + greenPhase) * 127.5 + 127.5).roundToInt()
    var blue = (sin(freq * normalizedValue + bluePhase) * 127.5 + 127.5).roundToInt()

    // Asegurarse de que los valores estén dentro del rango [0, 255]
    red = red.coerceIn(0, 255)
    green = green.coerceIn(0, 255)
    blue = blue.coerceIn(0, 255)

    return Color.rgb(red, green, blue)
}

fun getGrayByValueSimple(value: Double, min: Double, max: Double): Int {
    if (min == max) {
        return Color.rgb(0, 0, 0)
    }
    val t = ((value - min) / (max - min)).coerceIn(0.0, 1.0)
    val gray = (t * 255).toInt()
    return Color.rgb(gray, gray, gray)
}

fun getSmoothedValue(x: Int, y: Int, values: Array<DoubleArray>): Double {
    val x0 = x.coerceAtMost(values.size - 2)
    val y0 = y.coerceAtMost(values[0].size - 2)

    // Interpolación bilineal
    val tl = values[x0][y0]
    val tr = values[x0 + 1][y0]
    val bl = values[x0][y0 + 1]
    val br = values[x0 + 1][y0 + 1]

    val xRatio = x - x0
    val yRatio = y - y0

    return (tl * (1 - xRatio) * (1 - yRatio) +
            tr * xRatio * (1 - yRatio) +
            bl * (1 - xRatio) * yRatio +
            br * xRatio * yRatio)
}

fun bilinearInterpolate(data: Array<DoubleArray>, x: Float, y: Float): Double {
    val x1 = x.toInt().coerceIn(0, data.size - 1)
    val y1 = y.toInt().coerceIn(0, data[0].size - 1)
    val x2 = (x1 + 1).coerceAtMost(data.size - 1)
    val y2 = (y1 + 1).coerceAtMost(data[0].size - 1)

    val xRatio = x - x1
    val yRatio = y - y1

    return (data[x1][y1] * (1 - xRatio) * (1 - yRatio) +
            data[x2][y1] * xRatio * (1 - yRatio) +
            data[x1][y2] * (1 - xRatio) * yRatio +
            data[x2][y2] * xRatio * yRatio)
}




