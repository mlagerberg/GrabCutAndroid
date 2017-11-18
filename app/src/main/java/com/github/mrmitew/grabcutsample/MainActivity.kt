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


typealias Coordinates = Pair<Point, Point>
class MainActivity : AppCompatActivity() {

    companion object {
        val REQUEST_OPEN_IMAGE = 1337
        val TARGET_SIZE = 1080
        val LINE_WIDTH = 30
        val TEMP_FILENAME = "input.jpg"

        init {
            System.loadLibrary("opencv_java3")
        }
    }

    private var coordinates: ArrayList<Point>? = null
    private val disposables = CompositeDisposable()
    private lateinit var rxPermissions: RxPermissions
    private lateinit var bitmap: Bitmap
    private lateinit var paint: Paint
    private lateinit var path: Path
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
        paint.color = Color.BLACK
        paint.isAntiAlias = true
        paint.strokeWidth = LINE_WIDTH.toFloat()
        paint.style = Paint.Style.STROKE
        paint.pathEffect = null
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

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
                    top = Math.min(xScaled, top)
                    bottom = Math.max(xScaled, bottom)
                }
                event.action == MotionEvent.ACTION_UP -> {
                    coordinates?.add(Point(xScaled.toDouble(), yScaled.toDouble()))
                    path.close()
                    startCutting()
                }
            }

            // TODO move this to the ondraw of the view, instead of re-creating a new bitmap every time
            with(Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.RGB_565)) {
                Canvas(this).apply {
                    drawBitmap(bitmap, 0f, 0f, null)
                    drawPath(path, paint)
                }
                image.setImageDrawable(BitmapDrawable(resources, this))
            }
            true
        }

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
            bitmap = BitmapFactory.decodeFile(photoPath + "_4_final.jpg")
            setImageBitmap(bitmap)
            invalidate()
        }
    }

    private fun extractForegroundFromBackground(coordinates: ArrayList<Point>, currentPhotoPath: File): String {
        // TODO: Provide complex object that has both path and extension

        val startTime = System.currentTimeMillis()
        val colorBack = Scalar(Imgproc.GC_BGD.toDouble()) //Scalar(Imgproc.GC_PR_BGD.toDouble())
        val colorOutline = Scalar(Imgproc.GC_PR_BGD.toDouble()) // Scalar(Imgproc.GC_PR_FGD.toDouble())
        val colorInside = Scalar(Imgproc.GC_FGD.toDouble()) // Scalar(Imgproc.GC_FGD.toDouble())

        // Matrices that OpenCV will be using internally
        val bgModel = Mat()
        val fgModel = Mat()

        val srcImage = Imgcodecs.imread(currentPhotoPath.absolutePath)
        val iterations = 5

        // Mask image where we specify which areas are background, foreground or probable background/foreground
        val firstMask = Mat(
                Size(srcImage.cols().toDouble(), srcImage.rows().toDouble()),
                CvType.CV_8UC1, colorBack)
        // Fill the polygon
        val mop = MatOfPoint().apply {
            fromList(coordinates)
        }
        val mopList = ArrayList<MatOfPoint>()
        mopList.add(mop)
        Imgproc.fillPoly(firstMask, mopList, colorInside)
        // Draw the fat polygon outline
        Imgproc.polylines(firstMask, mopList, true, colorOutline, LINE_WIDTH)
        Imgcodecs.imwrite(currentPhotoPath.absolutePath + "_1_firstmask.jpg", firstMask)

        val source = Mat(1, 1, CvType.CV_8U, colorInside)
        val rect = Rect(
                Point(left.toDouble(), top.toDouble()),
                Point(right.toDouble(), bottom.toDouble()))

        // Run the grab cut algorithm with a rectangle (for subsequent iterations with touch-up strokes,
        // flag should be Imgproc.GC_INIT_WITH_MASK)
        Imgproc.grabCut(srcImage, firstMask, rect, bgModel, fgModel, iterations, Imgproc.GC_INIT_WITH_MASK)

        // Create a matrix of 0s and 1s, indicating whether individual pixels are equal
        // or different between "firstMask" and "source" objects
        // Result is stored back to "firstMask"
        Core.compare(firstMask, source, firstMask, Core.CMP_EQ)

        // Create a matrix to represent the foreground, filled with white color
        val foreground = Mat(srcImage.size(), CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))

        // Copy the foreground matrix to the first mask
        srcImage.copyTo(foreground, firstMask)

        // Create a red color
        val color = Scalar(255.0, 0.0, 0.0, 255.0)
        // Draw a rectangle using the coordinates of the bounding box that surrounds the foreground
        Imgproc.rectangle(srcImage,
                Point(left.toDouble(), top.toDouble()),
                Point(right.toDouble(), bottom.toDouble()),
                color)
        Imgcodecs.imwrite(currentPhotoPath.absolutePath + "_2_foreground.jpg", firstMask)

        // Create a new matrix to represent the background, filled with white color
        val background = Mat(srcImage.size(), CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))
        Imgcodecs.imwrite(currentPhotoPath.absolutePath + "_3_background.jpg", background)

        val mask = Mat(foreground.size(), CvType.CV_8UC1, Scalar(255.0, 255.0, 255.0))
        // Convert the foreground's color space from BGR to gray scale
        Imgproc.cvtColor(foreground, mask, Imgproc.COLOR_BGR2GRAY)

        // Separate out regions of the mask by comparing the pixel intensity with respect to a threshold value
        Imgproc.threshold(mask, mask, 254.0, 255.0, Imgproc.THRESH_BINARY_INV)

        // Create a matrix to hold the final image
        val dst = Mat()
        // copy the background matrix onto the matrix that represents the final result
        background.copyTo(dst)

        val vals = Mat(1, 1, CvType.CV_8UC3, Scalar(0.0))
        // Replace all 0 values in the background matrix given the foreground mask
        background.setTo(vals, mask)

        // Add the sum of the background and foreground matrices by applying the mask
        Core.add(background, foreground, dst, mask)

        // Save the final image to storage
        Imgcodecs.imwrite(currentPhotoPath.absolutePath + "_4_final.jpg", dst)

        // Clean up used resources
        firstMask.release()
        source.release()
        bgModel.release()
        fgModel.release()
        vals.release()
        dst.release()

        val endTime = System.currentTimeMillis()
        Log.w("grabcut", "Operation took " + ((endTime - startTime) / 1000) + "s")

        return currentPhotoPath.absolutePath
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
