package com.example.gndtomidi

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Executors

/** UI can be recreated on rotation; ConversionManager owns the worker and survives Activity recreation. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var convertButton: Button
    private lateinit var saveButton: Button
    private lateinit var folderButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        ConversionManager.attach(this) { refresh() }
        refresh()
    }

    override fun onDestroy() {
        ConversionManager.detach(this)
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 14)
            setBackgroundColor(0xFFF4F6FA.toInt())
        }
        root.addView(TextView(this).apply {
            text = "GND → MIDI 변환기"; textSize = 24f
            setTextColor(0xFF172554.toInt()); gravity = Gravity.CENTER
            setPadding(0, 4, 0, 12)
        })
        root.addView(TextView(this).apply {
            text = "파일 또는 폴더를 선택해 여러 GND 파일을 일괄 변환합니다. 변환은 백그라운드에서 실행되어 화면 회전 후에도 계속됩니다."
            textSize = 15f; setTextColor(0xFF334155.toInt()); setPadding(4, 0, 4, 10)
        })
        root.addView(button("GND 파일 선택") { pickSource() })
        folderButton = button("폴더 선택 · 일괄 변환") { pickFolder() }
        root.addView(folderButton)
        convertButton = button("선택 파일 MIDI 변환") { convertSingle() }
        root.addView(convertButton)
        saveButton = button("변환 파일 저장") { saveMidi() }
        root.addView(saveButton)
        status = TextView(this).apply {
            textSize = 14f; setTextColor(0xFF0F172A.toInt()); setPadding(8, 12, 8, 8)
        }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(status) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 15f; isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 3, 0, 3) }
    }

    private fun pickSource() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQ_FILE)
    }

    private fun pickFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQ_FOLDER)
    }

    @Deprecated("Compatibility with Android document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (flags != 0) contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) { }
        when (requestCode) {
            REQ_FILE -> ConversionManager.setSource(uri, queryName(uri))
            REQ_FOLDER -> {
                ConversionManager.startFolderBatch(uri)
                Toast.makeText(this, "폴더 일괄 변환을 시작했습니다.", Toast.LENGTH_SHORT).show()
            }
            REQ_SAVE -> ConversionManager.saveSelected(contentResolver, uri)
        }
        refresh()
    }

    private fun convertSingle() = ConversionManager.convertSingle(contentResolver)

    private fun saveMidi() {
        val name = ConversionManager.outputName().ifBlank { "converted.mid" }
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "audio/midi"
            putExtra(Intent.EXTRA_TITLE, if (name.endsWith(".mid", true)) name else "$name.mid")
        }, REQ_SAVE)
    }

    private fun queryName(uri: Uri): String {
        var name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "converted.gnd"
        try { contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        } } catch (_: Exception) { }
        return name
    }

    private fun refresh() {
        if (!::status.isInitialized) return
        status.text = ConversionManager.statusText()
        convertButton.isEnabled = ConversionManager.hasSource() && !ConversionManager.isRunning()
        folderButton.isEnabled = !ConversionManager.isRunning()
        saveButton.isEnabled = ConversionManager.hasMidi()
    }

    companion object { const val REQ_FILE = 100; const val REQ_SAVE = 101; const val REQ_FOLDER = 102 }
}

private object ConversionManager {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "gnd-midi-worker").apply { isDaemon = true } }
    private val handler = Handler(Looper.getMainLooper())
    private var activity: MainActivity? = null
    private var refreshCallback: (() -> Unit)? = null
    private var sourceUri: Uri? = null
    private var sourceName = "converted.gnd"
    private var midiBytes: ByteArray? = null
    private var midiName = "converted.mid"
    private var running = false
    private var status = "GND 파일 또는 폴더를 선택하세요.\n\n폴더 변환은 해당 폴더의 GND 파일을 차례대로 MIDI로 저장합니다."

    @Synchronized fun attach(a: MainActivity, cb: () -> Unit) { activity = a; refreshCallback = cb; notifyUi() }
    @Synchronized fun detach(a: MainActivity) { if (activity === a) { activity = null; refreshCallback = null } }
    @Synchronized fun hasSource() = sourceUri != null
    @Synchronized fun hasMidi() = midiBytes != null
    @Synchronized fun isRunning() = running
    @Synchronized fun outputName() = midiName
    @Synchronized fun statusText() = status
    @Synchronized fun setSource(uri: Uri, name: String) {
        sourceUri = uri; sourceName = name; midiBytes = null
        status = "선택 파일: $name\n\n'선택 파일 MIDI 변환'을 누르세요."
        notifyUi()
    }

    fun convertSingle(resolver: android.content.ContentResolver) {
        val uri: Uri; val name: String
        synchronized(this) {
            if (running) return
            uri = sourceUri ?: return
            name = sourceName
            running = true; status = "변환 준비 중: $name\n화면을 회전해도 작업은 계속됩니다."; notifyUi()
        }
        executor.execute {
            try {
                val bytes = resolver.openInputStream(uri)?.use { it.readBytesCompat() } ?: error("파일을 읽을 수 없습니다.")
                update("파일 읽기 완료: ${bytes.size} bytes\nMIDI 변환 중: $name")
                val result = GndConverter.convert(bytes)
                synchronized(this) { midiBytes = result.midi; midiName = name.substringBeforeLast('.', name) + ".mid" }
                update("변환 완료\n파일: $name\n트랙 수: ${result.trackCount}\nMIDI 형식: ${result.format}\nPPQ: ${result.ppq}\n출력 크기: ${result.midi.size} bytes\n\n'변환 파일 저장'을 눌러 저장하세요.")
            } catch (e: Exception) { synchronized(this) { midiBytes = null }; update("변환 실패: ${e.message}") }
            finally { synchronized(this) { running = false; notifyUi() } }
        }
    }

    fun startFolderBatch(treeUri: Uri) {
        synchronized(this) {
            if (running) return
            running = true; midiBytes = null
            status = "폴더의 GND 파일 목록을 확인하는 중..."; notifyUi()
        }
        val resolver = activity?.contentResolver
        if (resolver == null) { synchronized(this) { running = false }; return }
        executor.execute {
            var success = 0; var failed = 0
            val details = StringBuilder()
            try {
                val rootId = DocumentsContract.getTreeDocumentId(treeUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
                val items = ArrayList<Pair<Uri, String>>()
                resolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2) ?: ""
                        if (mime != DocumentsContract.Document.MIME_TYPE_DIR && name.endsWith(".gnd", true)) {
                            items.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, id) to name)
                        }
                    }
                } ?: error("선택한 폴더의 파일 목록을 읽을 수 없습니다.")
                if (items.isEmpty()) error("선택한 폴더 바로 아래에 GND 파일이 없습니다.")
                update("일괄 변환 시작: ${items.size}개 파일\n출력은 선택한 폴더에 .mid로 저장됩니다.")
                for ((index, item) in items.withIndex()) {
                    val (uri, name) = item
                    update("진행: ${index + 1}/${items.size}\n$name\n성공 $success · 실패 $failed")
                    try {
                        val input = resolver.openInputStream(uri)?.use { it.readBytesCompat() } ?: error("파일을 열 수 없습니다.")
                        val result = GndConverter.convert(input)
                        val outName = name.substringBeforeLast('.', name) + ".mid"
                        val existing = findChild(treeUri, rootId, outName, resolver)
                        if (existing != null) resolver.delete(existing, null, null)
                        val outUri = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId), "audio/midi", outName)
                            ?: error("출력 파일을 만들 수 없습니다.")
                        resolver.openOutputStream(outUri, "w")?.use { it.write(result.midi) } ?: error("출력 파일을 쓸 수 없습니다.")
                        success++
                        details.append("✓ ").append(name).append(" → ").append(outName).append("\n")
                    } catch (e: Exception) {
                        failed++; details.append("✗ ").append(name).append(" : ").append(e.message ?: "오류").append("\n")
                    }
                }
                update("일괄 변환 완료\n전체 ${items.size}개 · 성공 $success개 · 실패 $failed개\n\n$details")
            } catch (e: Exception) { update("폴더 변환 실패: ${e.message}") }
            finally { synchronized(this) { running = false; notifyUi() } }
        }
    }

    private fun findChild(tree: Uri, parentId: String, name: String, resolver: android.content.ContentResolver): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1).equals(name, true)) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        }
        return null
    }

    fun saveSelected(resolver: android.content.ContentResolver, uri: Uri) {
        val bytes = synchronized(this) { midiBytes } ?: return
        try { resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("저장 위치를 열 수 없습니다.")
            update("MIDI 파일 저장 완료: ${uri.lastPathSegment}")
        } catch (e: Exception) { update("저장 실패: ${e.message}") }
    }

    private fun update(message: String) { synchronized(this) { status = message; notifyUi() } }
    private fun notifyUi() { handler.post { synchronized(this) { refreshCallback?.invoke() } } }
    private fun InputStream.readBytesCompat(): ByteArray {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(64 * 1024)
        while (true) { val n = read(buffer); if (n < 0) break; out.write(buffer, 0, n) }
        return out.toByteArray()
    }
}
