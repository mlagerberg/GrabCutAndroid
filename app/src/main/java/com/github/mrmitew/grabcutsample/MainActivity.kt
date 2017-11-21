package com.github.mrmitew.grabcutsample

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.ImageView
import android.widget.Toast
import com.tbruyelle.rxpermissions2.RxPermissions
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_main.*
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream

typealias Coordinates = ArrayList<Point>

class MainActivity : AppCompatActivity() {

    companion object {
        val REQUEST_OPEN_IMAGE = 1337
        val TARGET_SIZE = 1080
        val LINE_WIDTH = 20
        val TEMP_FILENAME = "input.jpg"
        val GRABCUT_ITERATIONS = 3
        val MEDIAN_BLUR_SIZE = 3
        val STORE_DEBUG_IMAGES = true
        val DASHES_LENGTH = 10f
        val DASHES = floatArrayOf(DASHES_LENGTH, DASHES_LENGTH)

        init {
            System.loadLibrary("opencv_java3")
        }
    }

    private var coordinates: Coordinates? = null
    private val disposables = CompositeDisposable()
    private lateinit var rxPermissions: RxPermissions
    private lateinit var bitmap: Bitmap
    private lateinit var paint: Paint
    private lateinit var path: Path
    private lateinit var paintAnts: Array<Paint>
    private var pathAnts: Path? = null
    private var antsPhase = 0f
    private var left: Float = 0f
    private var right: Float = 0f
    private var top: Float = 0f
    private var bottom: Float = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        check(OpenCVLoader.initDebug(), { Toast.makeText(this, "OpenCV was not initialized properly", Toast.LENGTH_SHORT).show() })

        rxPermissions = RxPermissions(this)

        paint = Paint()
        paint.color = Color.WHITE
        paint.alpha = 128
        paint.isAntiAlias = true
        paint.strokeWidth = LINE_WIDTH.toFloat()
        paint.style = Paint.Style.STROKE
        paint.pathEffect = null
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        fun initDashPaint(ignore: Int): Paint {
            val p = Paint()
            p.isAntiAlias = true
            p.strokeWidth = 5f
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.BUTT
            p.strokeJoin = Paint.Join.ROUND
            return p
        }

        paintAnts = Array(2, ::initDashPaint)
        paintAnts[0].color = Color.CYAN
        paintAnts[0].pathEffect = DashPathEffect(DASHES, antsPhase)
        paintAnts[1].color = Color.MAGENTA
        paintAnts[1].pathEffect = DashPathEffect(DASHES, antsPhase + DASHES_LENGTH)

        image.setOnTouchListener { _, event ->
            if (!isPhotoChosen()) {
                return@setOnTouchListener false
            }

            val bounds = getBitmapPositionInsideImageView(image)
            val xScaled = (event.x / bounds[4]) - bounds[0]
            val yScaled = (event.y / bounds[5]) - bounds[1]
            when {
                event.action == MotionEvent.ACTION_DOWN -> {
                    if (coordinates == null) {
                        coordinates = ArrayList()
                        coordinates!!.add(Point(xScaled.toDouble(), yScaled.toDouble()))
                        path = Path()
                        path.moveTo(xScaled, yScaled)
                        top = yScaled
                        bottom = yScaled
                        right = xScaled
                        left = xScaled
                    }
                }
                event.action == MotionEvent.ACTION_MOVE -> {
                    coordinates?.add(Point(xScaled.toDouble(), yScaled.toDouble()))
                    coordinates?.add(Point(xScaled.toDouble(), yScaled.toDouble()))
                    path.lineTo(xScaled, yScaled)
                    left = Math.min(xScaled, left)
                    right = Math.max(xScaled, right)
                    top = Math.min(yScaled, top)
                    bottom = Math.max(yScaled, bottom)
                }
                event.action == MotionEvent.ACTION_UP -> {
                    coordinates?.add(Point(xScaled.toDouble(), yScaled.toDouble()))
                    path.close()
                    startCutting()
                }
            }

            // TODO move this to the ondraw of the view, instead of re-creating a new bitmap every time
            // Or use the overlayview
            with(Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.RGB_565)) {
                Canvas(this).apply {
                    drawBitmap(bitmap, 0f, 0f, null)
                    drawPath(path, paint)
                }
                image.setImageDrawable(BitmapDrawable(resources, this))
            }
            true
        }

        // Overlay (for marching ants)
        overlay.addCallback(object : OverlayView.TimedDrawCallback() {
            override fun draw(canvas: Canvas, elapsed: Long) {
                // Draw the ants
                if (pathAnts != null) {
                    paintAnts.forEach {
                        canvas.drawPath(pathAnts!!, it)
                    }
                }

                // March forward every so many milliseconds (~ 60fps)
                overlay.postDelayed({
                    if (elapsed > 0) {
                        antsPhase += (elapsed * 58.9f) * 0.34f // March 0.34 every 17 ms
                    }
                    // Unfortunately, there's no way to adjust the phase except by making a new DashPathEffect
                    paintAnts[0].pathEffect = DashPathEffect(DASHES, antsPhase)
                    paintAnts[1].pathEffect = DashPathEffect(DASHES, antsPhase + DASHES_LENGTH)
                    overlay.invalidate()
                }, 17)
            }
        })

        // Auto-load previous image
        val input = File(filesDir, TEMP_FILENAME)
        if (input.exists()) {
            loadPictureFromPath(input.absolutePath, false)
        } else {
            loadPictureFromAssets("sample.jpg")
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_OPEN_IMAGE -> if (resultCode == Activity.RESULT_OK && data?.data != null) {
                loadPictureFromUri(data.data)
            }
        }
    }

    private fun decodeBitmapFromFilePath(currentPhotoPath: String): Bitmap {
        // Figure out size of bitmap
        val bmOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(currentPhotoPath, bmOptions)
        // Find smallest size that is > 1080px
        var inSampleSize = 1
        var factor = 1.0
        while (bmOptions.outWidth / factor > TARGET_SIZE
                && bmOptions.outHeight / factor > TARGET_SIZE) {
            inSampleSize++
            factor *= 2
        }
        if (inSampleSize > 1) inSampleSize--

        // Load bitmap
        bmOptions.apply {
            inJustDecodeBounds = false
            this.inSampleSize = inSampleSize
        }
        return BitmapFactory.decodeFile(currentPhotoPath, bmOptions)
    }

    private fun loadPictureFromUri(data: Uri) {
        val imgUri: Uri = data
        val filePathColumn = arrayOf(MediaStore.Images.Media.DATA)
        val cursor = contentResolver.query(imgUri, filePathColumn, null, null, null)
        // TODO: Provide complex object that has both path and extension
        val path = cursor.moveToFirst()
                .let { cursor.getString(cursor.getColumnIndex(filePathColumn[0])) }
                .also { cursor.close() }
        loadPictureFromPath(path)
    }

    private fun loadPictureFromPath(photoPath: String, saveAfterLoad: Boolean = true) {
        bitmap = decodeBitmapFromFilePath(photoPath)
        image.setImageBitmap(bitmap)
        if (saveAfterLoad) {
            saveResized(bitmap)
        }
    }

    private fun loadPictureFromAssets(photoPath: String) {
        bitmap = BitmapFactory.decodeStream(assets.open(photoPath))
        image.setImageBitmap(bitmap)
        saveResized(bitmap)
    }

    private fun saveResized(bitmap: Bitmap) {
        try {
            val out = FileOutputStream(File(filesDir, TEMP_FILENAME))
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        } catch (e: Exception) {
            Log.e("grabcut", "Unable to save source file", e)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_clear_target -> {
                check(isPhotoChosen())
                resetTarget()
            }
            R.id.action_open_img -> {
                rxPermissions
                        .request(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        .subscribe({ granted ->
                            if (granted) {
                                val getPictureIntent = Intent(Intent.ACTION_GET_CONTENT)
                                        .apply { type = "image/*" }
                                val pickPictureIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                                val chooserIntent = Intent.createChooser(getPictureIntent, "Select Image")
                                        .apply { putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(pickPictureIntent)) }
                                startActivityForResult(chooserIntent, REQUEST_OPEN_IMAGE)
                            } else {
                                Toast.makeText(this, "App needs permission to read/write external storage", Toast.LENGTH_SHORT).show()
                            }
                        })
                        .addTo(disposables)
                return true
            }
            R.id.action_cut_image -> {
                startCutting()
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    private fun startCutting() {
        if (isPhotoChosen() && isTargetChosen()) {
            Single.fromCallable { extractForegroundFromBackground(coordinates!!, File(filesDir, TEMP_FILENAME)) }
                    .subscribeOn(Schedulers.computation())
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnSubscribe { vg_loading.visibility = VISIBLE }
                    .doOnSuccess { displayResult(it) }
                    .doFinally {
                        resetTargetCoordinates()
                        vg_loading.visibility = GONE
                    }
                    .subscribe()
                    .addTo(disposables)
        }
    }

    private fun resetTarget() {
        resetTargetCoordinates()
        image.setImageBitmap(bitmap)
    }

    private fun resetTargetCoordinates() {
        coordinates = null
    }

    private fun isPhotoChosen() = File(filesDir, TEMP_FILENAME).exists()

    private fun isTargetChosen() = coordinates != null && coordinates!!.isNotEmpty()

    private fun displayResult(photoPath: String) {
        // TODO: Provide complex object that has both path and extension
        image.apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            adjustViewBounds = true
            bitmap = BitmapFactory.decodeFile(photoPath + "_result.png")
            setImageBitmap(bitmap)
            invalidate()
        }
    }

    private fun extractForegroundFromBackground(coordinates: Coordinates, currentPhotoPath: File): String {
        // TODO: Provide complex object that has both path and extension

        val startTime = System.currentTimeMillis()
        val colorBack = Scalar(Imgproc.GC_BGD.toDouble())
        val colorInnerStroke = Scalar(Imgproc.GC_PR_FGD.toDouble())
        val colorOuterStroke = Scalar(Imgproc.GC_PR_BGD.toDouble())
        val colorInside = Scalar(Imgproc.GC_FGD.toDouble())

        // Load source again
        val srcImage = Imgcodecs.imread(currentPhotoPath.absolutePath)

        // Bounding box of the polyline
        left = Math.max(0f, left - LINE_WIDTH * .5f)
        top = Math.max(0f, top - LINE_WIDTH * .5f)
        right = Math.min(srcImage.width().toFloat(), right + LINE_WIDTH * .5f)
        bottom = Math.min(srcImage.height().toFloat(), bottom + LINE_WIDTH * .5f)
        val rect = Rect(
                Point(left.toDouble(), top.toDouble()),
                Point(right.toDouble(), bottom.toDouble()))

        // Offset all coordinates
        coordinates.forEach { point: Point ->
            run {
                point.x -= left
                point.y -= top
            }
        }

        // Generate polyline
        val mop = MatOfPoint().apply {
            fromList(coordinates)
        }
        val mopList = ArrayList<MatOfPoint>()
        mopList.add(mop)

        // Mask image where we specify which areas are background, foreground or probable background/foreground
        val firstMask = Mat(
                rect.size(),
                CvType.CV_8UC1,
                colorBack)

        // Outer stroke (3px 'probably background')
        Imgproc.polylines(firstMask, mopList, true, colorOuterStroke, LINE_WIDTH + 8)
        // Inner stroke, first pass (10px on the outside of the hand-drawn path)
        Imgproc.polylines(firstMask, mopList, true, colorInnerStroke, LINE_WIDTH)
        // Fill the polygon
        Imgproc.fillPoly(firstMask, mopList, colorInside)
        // Inner stroke, second pass (5px on the inside of the hand-drawn path)
        Imgproc.polylines(firstMask, mopList, true, colorInnerStroke, LINE_WIDTH - 10)
        // Save result
        if (STORE_DEBUG_IMAGES) {
            Imgcodecs.imwrite(currentPhotoPath.absolutePath + "_1a_firstmask.jpg", firstMask)
        }

        val cropped = Mat(srcImage, rect)
        srcImage.release()
        val median = cropped.clone()
        Imgproc.medianBlur(cropped, median, MEDIAN_BLUR_SIZE)

        // Run the grab cut algorithm with a mask
        // Matrices that OpenCV will be using internally
        val bgModel = Mat()
        val fgModel = Mat()
        Imgproc.grabCut(median, firstMask, rect, bgModel, fgModel, GRABCUT_ITERATIONS, Imgproc.GC_INIT_WITH_MASK)
        median.release()
        bgModel.release()
        fgModel.release()

        if (STORE_DEBUG_IMAGES) {
            Imgcodecs.imwrite(currentPhotoPath.absolutePath + "_1b_secondmask.jpg", firstMask)
        }

        // Create a matrix of 0s and 1s, indicating whether individual pixels are equal
        // or different between "firstMask" and "source" objects
        // Result is stored back to "firstMask"
        val newMask = Mat.zeros(firstMask.size(), CvType.CV_8U)
        val source1 = Mat(1, 1, CvType.CV_8U, colorInside)
        val source2 = Mat(1, 1, CvType.CV_8U, colorInnerStroke)
        Core.compare(firstMask, source1, newMask, Core.CMP_EQ)
        Core.compare(firstMask, source2, firstMask, Core.CMP_EQ)
        Core.add(firstMask, newMask, newMask)
        firstMask.release()
        if (STORE_DEBUG_IMAGES) {
            Imgcodecs.imwrite(currentPhotoPath.absolutePath + "_1c_finalmask.jpg", newMask)
        }

        // Locate contours in the mask
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(newMask, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE)

        if (contours.isNotEmpty()) {
            val biggestContour = findBiggestContour(contours)
            //contours = ArrayList()
            if (biggestContour != null) {
                //contours = Coordinates()
                pathAnts = null
                for (point in biggestContour.toArray()) {
                    if (pathAnts == null) {
                        pathAnts = Path()
                        pathAnts!!.moveTo(point.x.toFloat(), point.y.toFloat())
                    } else {
                        pathAnts!!.lineTo(point.x.toFloat(), point.y.toFloat())
                    }
                }
                pathAnts!!.close()
            }
        }

        // Create a matrix to represent the foreground, filled with 0% alpha
        val alpha = Scalar(255.0, 255.0, 255.0, 0.0)
        val foreground = Mat(cropped.size(), CvType.CV_8UC4, alpha)
        // Convert to 4-channel (RGBA) color space
        //cropped.convertTo(foreground, CvType.CV_8UC4)
        Imgproc.cvtColor(cropped, foreground, Imgproc.COLOR_BGR2BGRA)
        cropped.release()

        // Invert mask
        Core.bitwise_not(newMask, newMask)
        // Set alpha = 0 on all white pixels in the inverted mask
        foreground.setTo(alpha, newMask)

        Imgcodecs.imwrite(currentPhotoPath.absolutePath + "_result.png", foreground)

        // Clean up used resources
        foreground.release()
        newMask.release()

        val endTime = System.currentTimeMillis()
        Log.w("grabcut", "Operation took " + ((endTime - startTime) / 1000) + "s")

        return currentPhotoPath.absolutePath
    }

    /**
     * Returns the largest contour in the list, measured by area
     */
    private fun findBiggestContour(cont: ArrayList<MatOfPoint>): MatOfPoint? {
        if (cont.isEmpty()) {
            return null
        }

        var maxSize = 0.0
        var maxIndex = -1
        cont.forEachIndexed { i: Int, point: MatOfPoint ->
            val size = Imgproc.contourArea(point)
            if (size > maxSize) {
                maxSize = size
                maxIndex = i
            }
        }

        return cont[maxIndex]
    }

    /**
     * @author Glen Pierce
     * @link https://stackoverflow.com/questions/35250485/how-to-translate-scale-ontouchevent-coordinates-onto-bitmap-canvas-in-android-in
     */
    private fun getBitmapPositionInsideImageView(imageView: ImageView): FloatArray {
        val rect = FloatArray(6)

        if (imageView.drawable == null)
            return rect

        // Get image dimensions
        // Get image matrix values and place them in an array
        val f = FloatArray(9)
        imageView.imageMatrix.getValues(f)

        // Extract the scale values using the constants (if aspect ratio maintained, scaleX == scaleY)
        val scaleX = f[Matrix.MSCALE_X]
        val scaleY = f[Matrix.MSCALE_Y]

        rect[4] = scaleX
        rect[5] = scaleY

        // Get the drawable (could also get the bitmap behind the drawable and getWidth/getHeight)
        val d = imageView.drawable
        val origW = d.intrinsicWidth
        val origH = d.intrinsicHeight

        // Calculate the actual dimensions
        val actW = Math.round(origW * scaleX)
        val actH = Math.round(origH * scaleY)

        rect[2] = actW.toFloat()
        rect[3] = actH.toFloat()

        // Get image position
        // We assume that the image is centered into ImageView
        val imgViewW = imageView.width
        val imgViewH = imageView.height

        val left = (imgViewW - actW) / 2
        val top = (imgViewH - actH) / 2

        rect[0] = left.toFloat()
        rect[1] = top.toFloat()

        return rect
    }

    override fun onDestroy() {
        super.onDestroy()
        disposables.dispose()
    }
}
