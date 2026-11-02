package com.jinge.weknora

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.fragment.app.FragmentActivity
import androidx.pdf.viewer.fragment.PdfViewerFragment

/** Official AndroidX PDF viewer, backed by the Android 15 PDF framework. */
class NativePdfActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val content = FrameLayout(this).apply { id = 0x123456 }
        val viewer = (supportFragmentManager.findFragmentByTag("pdf") as? PdfViewerFragment) ?: PdfViewerFragment()
        val toolbar = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setBackgroundColor(0xff12201e.toInt()) }
        toolbar.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } })
        toolbar.addView(TextView(this).apply { text = intent.getStringExtra("title"); setTextColor(-1); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0,-2,1f))
        toolbar.addView(Button(this).apply { text = "搜索"; setOnClickListener { viewer.isTextSearchActive = !viewer.isTextSearchActive } })
        root.addView(toolbar, LinearLayout.LayoutParams(-1, (44 * resources.displayMetrics.density).toInt()))
        root.addView(content, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(content.id, viewer, "pdf").commitNow()
            viewer.documentUri = intent.data
        }
    }
}
