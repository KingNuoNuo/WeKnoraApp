package com.jinge.weknora

import android.os.Bundle
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.view.Gravity
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.pdf.*
import androidx.pdf.view.PdfView
import androidx.pdf.ink.EditablePdfViewerFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jinge.weknora.data.PdfBookmark
import com.jinge.weknora.data.PdfOutline
import kotlinx.coroutines.*
import java.io.File

@OptIn(ExperimentalPdfApi::class)
class NativePdfActivity : FragmentActivity() {
    private lateinit var viewer: androidx.pdf.viewer.fragment.PdfViewerFragment
    private val editor get() = viewer as? ReaderPdfFragment
    private var pdf: PdfView? = null
    private var count = 0
    private lateinit var pageLabel: TextView
    private lateinit var previous: MaterialButton
    private lateinit var next: MaterialButton
    private var bookmarks = emptyList<PdfBookmark>()
    private val expanded = mutableSetOf<Int>()
    private var pendingWrite: PdfWriteHandle? = null
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val handle = pendingWrite
        pendingWrite = null
        if (handle != null) lifecycleScope.launch {
            try {
                if (uri != null) withContext(Dispatchers.IO) {
                    handle.use { h -> contentResolver.openFileDescriptor(uri, "w")!!.use { h.writeTo(it) } }
                } else handle.close()
                if (uri != null) { editor?.isEditModeEnabled = false; toast("已保存 PDF 副本") }
            } catch(e: Exception) { handle.close(); toast("保存失败：${e.message}") }
        }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun button(label: String, dark: Boolean = false, action: () -> Unit) =
        MaterialButton(this).apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            text = label; textSize = 14f; minWidth = 0; minimumWidth = 0
            insetTop = 0; insetBottom = 0
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(if(dark) Color.WHITE else 0xff0f6b63.toInt())
            setOnClickListener { action() }
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewer = (supportFragmentManager.findFragmentByTag("pdf") as? androidx.pdf.viewer.fragment.PdfViewerFragment) ?: if (android.os.ext.SdkExtensions.getExtensionVersion(android.os.Build.VERSION_CODES.S) >= 18) ReaderPdfFragment() else ReadOnlyPdfFragment()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xfff1f3f5.toInt()) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val title = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(0xff12201e.toInt()) }
        title.addView(button("‹ 返回", true) { leave() }, LinearLayout.LayoutParams(-2,-1))
        title.addView(TextView(this).apply {
            text = intent.getStringExtra("title"); setTextColor(Color.WHITE); textSize = 14f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0,-2,1f))
        title.addView(button("更多", true) { more(it = title) }, LinearLayout.LayoutParams(-2,-1))
        root.addView(title, LinearLayout.LayoutParams(-1,dp(44)))
        val tools = LinearLayout(this).apply { gravity = Gravity.CENTER; setBackgroundColor(Color.WHITE) }
        listOf(
            "目录" to { outline() },
            "跳转" to { jump() },
            "搜索" to { viewer.isTextSearchActive = !viewer.isTextSearchActive },
            "缩放" to { zoom() }
        ).forEach { (label, action) -> tools.addView(button(label, action = action), LinearLayout.LayoutParams(0,dp(44),1f)) }
        root.addView(tools)
        val content = FrameLayout(this).apply { id = 0x123456 }
        root.addView(content, LinearLayout.LayoutParams(-1,0,1f))
        val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.WHITE) }
        previous = button("上一页") { navigate((pdf?.firstVisiblePage ?: 0)-1) }
        next = button("下一页") { navigate((pdf?.firstVisiblePage ?: 0)+1) }
        pageLabel = TextView(this).apply { text = "正在加载"; gravity = Gravity.CENTER; textSize = 13f; setTextColor(0xff41534f.toInt()); setOnClickListener { jump() } }
        bottom.addView(previous, LinearLayout.LayoutParams(-2,-1))
        bottom.addView(pageLabel, LinearLayout.LayoutParams(0,-1,1f))
        bottom.addView(next, LinearLayout.LayoutParams(-2,-1))
        root.addView(bottom, LinearLayout.LayoutParams(-1,dp(44)))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) { override fun handleOnBackPressed() { leave() } })
        if(savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(content.id,viewer,"pdf").commitNow()
            viewer.documentUri = intent.data
        }
        lifecycleScope.launch {
            bookmarks = withContext(Dispatchers.IO) {
                val file = File.createTempFile("outline-", ".pdf", cacheDir)
                try {
                    contentResolver.openInputStream(intent.data!!)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                    PdfOutline.read(this@NativePdfActivity,file)
                } catch(_: Exception) { emptyList() } finally { file.delete() }
            }
        }
    }
    fun attach(view: PdfView) {
        pdf = view
        view.verticalPageSpacing = dp(6)
        view.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> updatePage() }
        view.viewTreeObserver.addOnScrollChangedListener { updatePage() }
    }
    fun loaded(document: PdfDocument) { count = document.pageCount; updatePage() }
    private fun updatePage() {
        if(!::pageLabel.isInitialized) return
        val page = (pdf?.firstVisiblePage ?: 0).coerceAtLeast(0)
        pageLabel.text = if(count > 0) "第 ${page+1} / $count 页" else "正在加载"
        previous.isEnabled = count > 0 && page > 0
        next.isEnabled = count > 0 && page < count-1
    }
    private fun navigate(page: Int) { if(page in 0 until count) { pdf?.scrollToPage(page); updatePage() } }
    private fun jump() {
        if(count == 0) return
        val field = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText("${(pdf?.firstVisiblePage ?: 0)+1}"); selectAll(); hint = "1–$count" }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("跳转页码").setView(field).setNegativeButton("取消",null).setPositiveButton("跳转",null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val page = field.text.toString().toIntOrNull()
            if(page == null || page !in 1..count) field.error = "请输入 1–$count" else { navigate(page-1); dialog.dismiss() }
        } }; dialog.show()
    }
    private fun outline() {
        val dialog = android.app.Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        header.addView(TextView(this).apply {
            text = "文档目录"; textSize = 16f
            setTextColor(0xff243b35.toInt()); includeFontPadding = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("关闭") { dialog.dismiss() }, LinearLayout.LayoutParams(-2, -1))
        panel.addView(header, LinearLayout.LayoutParams(-1, dp(44)))
        panel.addView(View(this).apply { setBackgroundColor(0xffe6ebe9.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setPadding(dp(4), dp(2), dp(4), dp(4))
            addView(list)
        }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(panel)
        fun rows() {
            list.removeAllViews()
            val ancestors = mutableListOf<Int>()
            bookmarks.forEachIndexed { index, mark ->
                while(ancestors.size > mark.depth) ancestors.removeAt(ancestors.lastIndex)
                val visible = ancestors.all { it in expanded }
                if(visible) {
                    val row = LinearLayout(this).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(40)
                        setPadding(dp(4+mark.depth.coerceAtMost(6)*8),0,dp(8),0)
                    }
                    val children = (bookmarks.getOrNull(index+1)?.depth ?: -1) > mark.depth
                    if(children) row.addView(button(if(index in expanded) "⌄" else "›") {
                        val position = scroll.scrollY
                        if(!expanded.add(index)) expanded.remove(index)
                        rows()
                        scroll.post { scroll.scrollTo(0, position) }
                    }.apply {
                        setPadding(0,0,0,0)
                        contentDescription = if(index in expanded) "收起 ${mark.title}" else "展开 ${mark.title}"
                    }, LinearLayout.LayoutParams(dp(32),dp(40)))
                    else row.addView(Space(this), LinearLayout.LayoutParams(dp(8),1))
                    row.addView(TextView(this).apply {
                        text = mark.title; textSize = 14f; setTextColor(0xff243b35.toInt()); maxLines = 2
                        includeFontPadding = false
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setLineSpacing(0f, 1f)
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(40)
                        setPadding(0,dp(3),dp(6),dp(3)); setOnClickListener { mark.page?.let { navigate(it); dialog.dismiss() } }
                    }, LinearLayout.LayoutParams(0,-2,1f))
                    row.addView(TextView(this).apply {
                        text = mark.page?.let { "${it+1}" }.orEmpty(); textSize = 12f
                        setTextColor(0xff6b7774.toInt()); includeFontPadding = false
                        gravity = Gravity.CENTER_VERTICAL or Gravity.END
                        minWidth = dp(32); minimumHeight = dp(40)
                        setOnClickListener { mark.page?.let { navigate(it); dialog.dismiss() } }
                    })
                    list.addView(row)
                }
                ancestors.add(index)
            }
            if(bookmarks.isEmpty()) list.addView(TextView(this).apply { text = "此文档没有可用书签，可使用页码跳转。"; setPadding(dp(24),dp(24),dp(24),dp(24)) })
        }
        rows(); dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            val bounds = windowManager.currentWindowMetrics.bounds
            setLayout((bounds.width() * .98).toInt(), (bounds.height() * .94).toInt())
        }
    }
    private fun zoom() {
        MaterialAlertDialogBuilder(this).setTitle("缩放").setItems(arrayOf("适合宽度","100%","150%","200%")) { _, which ->
            val view = pdf ?: return@setItems
            if (which == 0) lifecycleScope.launch {
                try {
                    val info = view.pdfDocument?.getPageInfo(view.firstVisiblePage.coerceAtLeast(0)) ?: return@launch
                    view.zoom = (view.width.toFloat() / info.width).coerceIn(view.minZoom, view.maxZoom)
                } catch(e: Exception) { toast("缩放失败：${e.message}") }
            } else view.zoom = when(which) { 1 -> 1f; 2 -> 1.5f; else -> 2f }
        }.show()
    }
    private fun more(it: View) {
        PopupMenu(this,it).apply {
            menu.add("批注 / 填写表单").setOnMenuItemClickListener {
                try { if (editor == null) toast("当前系统 PDF 扩展版本不足，批注需要扩展 18 或以上") else editor?.isEditModeEnabled = true } catch(e: Exception) { toast("当前系统扩展不支持编辑：${e.message}") }; true
            }
            menu.add("保存编辑副本").setOnMenuItemClickListener {
                if(editor?.hasUnsavedChanges == true) editor?.applyDraftEdits() else toast("当前没有未保存的编辑"); true
            }
            menu.add("分享 PDF").setOnMenuItemClickListener {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM,intent.data).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"分享 PDF")); true
            }
            menu.add("双页 / 单页").setOnMenuItemClickListener { pdf?.let { v -> v.pagesPerRow = if(v.pagesPerRow == 1) 2 else 1 }; true }
        }.show()
    }
    fun save(handle: PdfWriteHandle) { pendingWrite?.close(); pendingWrite = handle; export.launch("WeKnora-annotated.pdf") }
    private fun leave() {
        if(editor?.hasUnsavedChanges == true) MaterialAlertDialogBuilder(this).setTitle("保留编辑？").setMessage("可以先保存 PDF 副本。")
            .setPositiveButton("保存") { _,_ -> editor?.applyDraftEdits() }.setNegativeButton("放弃并返回") { _,_ -> finish() }.setNeutralButton("继续阅读",null).show()
        else finish()
    }
    fun toast(message: String) { Toast.makeText(this,message,Toast.LENGTH_LONG).show() }
}

@OptIn(ExperimentalPdfApi::class)
class ReaderPdfFragment : EditablePdfViewerFragment() {
    override fun onInitOcrProvider(): androidx.pdf.ocr.OcrProvider = androidx.pdf.ocr.playservices.MlKitOcrProvider()
    override fun onPdfViewCreated(pdfView: PdfView) {
        super.onPdfViewCreated(pdfView)
        (activity as? NativePdfActivity)?.attach(pdfView)
        if (android.os.ext.SdkExtensions.getExtensionVersion(android.os.Build.VERSION_CODES.S) >= 18) pdfView.isFormFillingEnabled = true
        if (android.os.ext.SdkExtensions.getExtensionVersion(android.os.Build.VERSION_CODES.S) >= 19) pdfView.isImageSelectionEnabled = true
    }
    override fun onLoadDocumentSuccess(document: PdfDocument) {
        super.onLoadDocumentSuccess(document)
        (activity as? NativePdfActivity)?.loaded(document)
    }
    override fun onApplyEditsSuccess(handle: PdfWriteHandle) { (activity as? NativePdfActivity)?.save(handle) ?: handle.close() }
    override fun onApplyEditsFailed(error: Throwable) { (activity as? NativePdfActivity)?.toast("编辑失败：${error.message}") }
    override fun onLoadDocumentError(error: Throwable) { android.util.Log.e("WeKnoraPDF", "PDF load failed", error); super.onLoadDocumentError(error); (activity as? NativePdfActivity)?.toast("PDF 加载失败：${error.message}") }
}
@OptIn(ExperimentalPdfApi::class)
class ReadOnlyPdfFragment : androidx.pdf.viewer.fragment.PdfViewerFragment() {
    override fun onPdfViewCreated(pdfView: PdfView) { super.onPdfViewCreated(pdfView); (activity as? NativePdfActivity)?.attach(pdfView) }
    override fun onLoadDocumentSuccess(document: PdfDocument) { super.onLoadDocumentSuccess(document); (activity as? NativePdfActivity)?.loaded(document) }
    override fun onLoadDocumentError(error: Throwable) { super.onLoadDocumentError(error); (activity as? NativePdfActivity)?.toast("PDF 加载失败：${error.message}") }
}

