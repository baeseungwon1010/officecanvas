package io.github.baeseungwon1010.officecanvas.sample

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.baeseungwon1010.officecanvas.OfficeCanvas
import io.github.baeseungwon1010.officecanvas.OfficeCanvasException
import java.io.File
import kotlin.concurrent.thread

/** Pick a .pptx, .ppt or .hwpx and see its pages rendered one under another. */
class MainActivity : Activity() {

    private lateinit var pages: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { setPadding(32, 16, 32, 16) }
        val open = Button(this).apply {
            text = "Open document"
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                    REQUEST_OPEN
                )
            }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(open, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(status)
            addView(pages)
        }
        setContentView(ScrollView(this).apply { addView(column) })
        intent?.data?.let { show(it) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OPEN && resultCode == RESULT_OK) data?.data?.let { show(it) }
    }

    private fun show(uri: Uri) {
        pages.removeAllViews()
        status.text = "Rendering…"
        val width = resources.displayMetrics.widthPixels
        thread {
            // The library reads a File; copy the picked document into the cache first.
            val file = File(cacheDir, "document")
            contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            val started = System.currentTimeMillis()
            try {
                val count = OfficeCanvas.render(this, file, longSidePx = width) { _, bitmap -> addPage(bitmap) }
                val seconds = (System.currentTimeMillis() - started) / 1000.0
                runOnUiThread { status.text = "$count pages in %.1f s".format(seconds) }
            } catch (e: OfficeCanvasException) {
                runOnUiThread { status.text = e.message }
            }
        }
    }

    private fun addPage(bitmap: Bitmap) = runOnUiThread {
        pages.addView(ImageView(this).apply {
            adjustViewBounds = true
            setImageBitmap(bitmap)
            setPadding(0, 8, 0, 8)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private companion object {
        const val REQUEST_OPEN = 1
    }
}
