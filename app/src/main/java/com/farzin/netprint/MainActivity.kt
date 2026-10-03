package com.farzin.netprint

import android.content.Intent
import android.net.Uri
import android.os.*
import android.print.*
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.print.PrintHelper
import kotlinx.coroutines.launch
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private val printers = linkedMapOf<String, NetPrinter>()
    private lateinit var adapter: ArrayAdapter<NetPrinter>
    private lateinit var status: TextView
    private lateinit var fileLabel: TextView
    private var selected: NetPrinter? = null
    private var docUri: Uri? = null
    private var docMime = "application/pdf"
    private lateinit var discovery: PrinterDiscovery

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::setDoc) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        discovery = PrinterDiscovery(this) { addPrinter(it) }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, mutableListOf())

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
        val row = { vararg v: android.view.View -> LinearLayout(this).apply { v.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) } } }

        status = TextView(this).apply { text = "Ready" }
        fileLabel = TextView(this).apply { text = "No file selected" }
        val ip = EditText(this).apply { hint = "Manual IP (e.g. 192.168.1.50)" }
        val proto = Spinner(this).apply { adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, Protocol.values()) }

        val list = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_SINGLE; adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> selected = this@MainActivity.adapter.getItem(pos) }
        }

        root.addView(row(btn("Find printers (Bonjour)") { status.text = "Searching…"; discovery.startMdns() },
                         btn("Deep scan network") { scan() }))
        root.addView(row(ip, proto, btn("Add") {
            val h = ip.text.toString().trim(); if (h.isEmpty()) return@btn
            val pr = proto.selectedItem as Protocol
            addPrinter(NetPrinter("Manual", h, when (pr) { Protocol.IPP -> 631; Protocol.RAW -> 9100; Protocol.LPD -> 515 }, pr))
        }))
        root.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(btn("Choose file (PDF / image / text)") { pick.launch(arrayOf("application/pdf", "image/*", "text/plain")) })
        root.addView(fileLabel)
        root.addView(row(btn("Print direct") { printDirect() }, btn("System print dialog") { systemPrint() }))
        root.addView(status)
        setContentView(root)

        // Share-to-print from other apps
        if (intent?.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION") (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let(::setDoc)
        }
        discovery.startMdns()
    }

    override fun onDestroy() { discovery.stopMdns(); super.onDestroy() }

    private fun addPrinter(p: NetPrinter) {
        // prefer IPP entry if the same host shows up several times
        if (printers.containsKey(p.key)) return
        printers[p.key] = p
        adapter.clear(); adapter.addAll(printers.values.sortedBy { it.protocol.ordinal })
        status.text = "${printers.size} printer endpoint(s) found"
    }

    private fun scan() = lifecycleScope.launch {
        status.text = "Scanning subnet (≈10–20s)…"
        discovery.scanSubnet()
        status.text = "Scan done: ${printers.size} endpoint(s)"
    }

    private fun setDoc(uri: Uri) {
        docUri = uri
        docMime = contentResolver.getType(uri) ?: "application/pdf"
        fileLabel.text = "File: ${uri.lastPathSegment} ($docMime)"
    }

    private fun printDirect() {
        val p = selected ?: return toast("Pick a printer first")
        val uri = docUri ?: return toast("Pick a file first")
        lifecycleScope.launch {
            status.text = "Sending to ${p.host}…"
            try {
                // RAW/LPD + plain text: send text as-is (works on virtually every printer). Otherwise PDF.
                val (data, mime) = if (p.protocol != Protocol.IPP && docMime.startsWith("text/"))
                    (DocumentConverter.read(this@MainActivity, uri) + 0x0C.toByte()) to "text/plain"
                else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    DocumentConverter.toPdf(this@MainActivity, uri, docMime)
                } to "application/pdf"
                PrintSender.send(p, data, mime, uri.lastPathSegment ?: "NetPrint job")
                status.text = "✅ Sent to ${p.name}"
            } catch (e: Exception) {
                status.text = "❌ ${e.message}\nTip: try another protocol or 'System print dialog'"
            }
        }
    }

    /** Android print framework: uses Mopria / HP / Canon / Epson… plugins → covers printers that need proprietary formats. */
    private fun systemPrint() {
        val uri = docUri ?: return toast("Pick a file first")
        val name = uri.lastPathSegment ?: "NetPrint"
        if (docMime.startsWith("image/")) {
            PrintHelper(this).apply { scaleMode = PrintHelper.SCALE_MODE_FIT }.printBitmap(name, uri); return
        }
        lifecycleScope.launch {
            val pdf = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DocumentConverter.toPdf(this@MainActivity, uri, docMime) }
            (getSystemService(PRINT_SERVICE) as PrintManager).print(name, PdfAdapter(name, pdf), null)
        }
    }

    private class PdfAdapter(val name: String, val pdf: ByteArray) : PrintDocumentAdapter() {
        override fun onLayout(o: PrintAttributes?, n: PrintAttributes, s: CancellationSignal?, cb: LayoutResultCallback, e: Bundle?) =
            cb.onLayoutFinished(PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
        override fun onWrite(p: Array<out PageRange>, d: ParcelFileDescriptor, s: CancellationSignal?, cb: WriteResultCallback) {
            FileOutputStream(d.fileDescriptor).use { it.write(pdf) }
            cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        }
    }

    private fun toast(m: String) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }
}
