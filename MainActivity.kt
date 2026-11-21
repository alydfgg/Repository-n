package com.exfiles.app

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.graphics.drawable.Drawable
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.util.LruCache
import android.view.*
import android.widget.*
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private const val M = ViewGroup.LayoutParams.MATCH_PARENT
private const val W = ViewGroup.LayoutParams.WRAP_CONTENT
private val ICONS = arrayOf("📁", "🖼", "🎬", "🎵", "🤖", "🗜", "📝", "📄")
private val COLORS = intArrayOf(0xFFFFB300.toInt(), 0xFFEF5350.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(),
    0xFF43A047.toInt(), 0xFF00B0FF.toInt(), 0xFF78909C.toInt(), 0xFF9E9E9E.toInt())

class Row(val name: String, val sub: String, val kind: Int, val file: File?, val pkg: String? = null)
class ApkMeta(val label: String, val pkg: String, val ver: String, val code: Long, val icon: Bitmap?, val perms: List<String>)
class Tab(var label: String, var go: () -> Unit)
class Tile(val label: String, val ic: String, val color: Int, val lc: (() -> Unit)? = null, val act: () -> Unit)

class MainActivity : Activity() {
    private val BLUE = 0xFF2F6BE8.toInt()
    private val root: File = Environment.getExternalStorageDirectory()
    private val trash = File(root, ".EXTrash")
    private lateinit var prefs: SharedPreferences
    private lateinit var tvTitle: TextView
    private lateinit var homeView: ScrollView
    private lateinit var list: GridView
    private lateinit var selBar: LinearLayout
    private lateinit var pasteBar: LinearLayout
    private lateinit var pasteTv: TextView
    private lateinit var miniBar: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniPlay: TextView
    private lateinit var adapter: BaseAdapter
    private val rows = ArrayList<Row>()
    private val sel = HashSet<Int>()
    private var cwd: File? = null
    private var mode = "home"
    private var baseTitle = ""
    private var showHidden = false
    private var vm = 2   // 0 list, 1 big grid (3), 2 small grid (4)
    private val grid: Boolean get() = vm != 0
    private lateinit var pill: TextView
    private val tabs = ArrayList<Tab>()
    private var curTab = 0
    private lateinit var tabRow: LinearLayout
    private val apkInfo = LruCache<String, String>(300)
    private var sortBy = 0
    private var clip: List<File> = emptyList()
    private var cut = false
    private var asked = false
    private val h = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val INSTALL_ACTION = "com.exfiles.app.INSTALL_RESULT"
    private val installRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val n = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (n != null) { n.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(n) }
                }
                PackageInstaller.STATUS_SUCCESS -> toast("تم التثبيت بنجاح")
                else -> toast("فشل التثبيت: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""))
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val a = PlayerService.active
            miniBar.visibility = if (a) View.VISIBLE else View.GONE
            if (a) {
                miniTitle.text = PlayerService.title()
                miniPlay.text = if (PlayerService.playing()) "⏸" else "▶"
            }
            h.postDelayed(this, 700)
        }
    }

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) { runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() } }
    private fun tv(t: String, sz: Float, col: Int = Color.BLACK): TextView {
        val x = TextView(this); x.text = t; x.textSize = sz; x.setTextColor(col); return x
    }
    private fun circle(c: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
    private fun card() = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(10).toFloat() }
    // ---------- tabs ----------
    private fun renderTabs() {
        tabRow.removeAllViews()
        tabs.forEachIndexed { i, t ->
            val on = i == curTab
            val box = LinearLayout(this); box.orientation = LinearLayout.HORIZONTAL; box.gravity = Gravity.CENTER_VERTICAL
            box.setPadding(dp(10), dp(6), dp(10), dp(6))
            box.background = GradientDrawable().apply { setColor(if (on) 0x55FFFFFF else 0x22FFFFFF); cornerRadius = dp(16).toFloat() }
            box.addView(tv(if (t.label == "الرئيسية") "🏠" else "📂", 14f, Color.WHITE))
            if (on) {
                val l = tv(t.label.take(12), 13f, Color.WHITE); l.isSingleLine = true; l.setPadding(dp(6), 0, dp(6), 0)
                box.addView(l)
                if (tabs.size > 1) {
                    val x = tv("✕", 13f, Color.WHITE); x.setPadding(dp(6), dp(2), dp(2), dp(2))
                    x.setOnClickListener { closeTab(i) }; box.addView(x)
                }
            }
            box.setOnClickListener { if (i != curTab) { curTab = i; renderTabs(); t.go() } }
            box.setOnLongClickListener { closeTab(i); true }
            tabRow.addView(box, LinearLayout.LayoutParams(W, W).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        val plus = tv("＋", 18f, Color.WHITE); plus.setPadding(dp(10), dp(2), dp(10), dp(2))
        plus.setOnClickListener { newTab { showHome() } }
        tabRow.addView(plus)
    }

    private fun setTab(label: String, go: () -> Unit) {
        if (tabs.isEmpty()) return
        tabs[curTab].label = label; tabs[curTab].go = go; renderTabs()
    }

    private fun newTab(go: () -> Unit) {
        tabs.add(Tab("…", go)); curTab = tabs.size - 1; renderTabs(); go()
    }

    private fun closeTab(i: Int) {
        if (tabs.size <= 1) { toast("لا يمكن إغلاق التبويب الأخير"); return }
        val wasCur = i == curTab
        tabs.removeAt(i)
        if (i < curTab) curTab--
        if (curTab >= tabs.size) curTab = tabs.size - 1
        renderTabs()
        if (wasCur) tabs[curTab].go()
    }

    private fun openMany(fs: List<File>) {
        val files = fs.filter { !it.isDirectory }
        if (files.isEmpty()) return
        val k = Fs.kind(files[0])
        val l = ArrayList(files.filter { Fs.kind(it) == k }.map { it.path })
        clearSel()
        when (k) {
            1 -> startActivity(Intent(this, ImageActivity::class.java).putStringArrayListExtra("list", l).putExtra("i", 0))
            2 -> startActivity(Intent(this, VideoActivity::class.java).putStringArrayListExtra("list", l).putExtra("i", 0))
            3 -> { askNotif(); PlayerService.play(this, l, 0) }
            6 -> startActivity(Intent(this, TextActivity::class.java).putStringArrayListExtra("list", l).putExtra("i", 0))
            else -> toast("اختر ملفات من نفس النوع (صور، فيديو، صوت، نصوص)")
        }
    }

    private fun applyView() {
        list.numColumns = when (vm) { 0 -> 1; 1 -> 3; else -> 4 }
        list.horizontalSpacing = if (grid) dp(4) else 0
        list.verticalSpacing = if (grid) dp(4) else 0
    }
    private fun usage(): Int = try {
        val st = StatFs(root.path)
        val total = st.blockCountLong * st.blockSizeLong
        if (total > 0) ((total - st.availableBlocksLong * st.blockSizeLong) * 100 / total).toInt() else 0
    } catch (e: Exception) { 0 }
    private fun setHead(s: String) { baseTitle = s; tvTitle.text = s; pill.text = "${usage()}%" }

    // compileSdk 29: Android 11 (API 30) APIs are reached by reflection / string constants
    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            try { Environment::class.java.getMethod("isExternalStorageManager").invoke(null) as Boolean }
            catch (e: Exception) { false }
        } else checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED

    private fun ask() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                try { startActivity(Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION")) } catch (e2: Exception) {}
            }
        } else {
            requestPermissions(arrayOf("android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE"), 1)
        }
    }

    private fun askNotif() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 3)
    }

    private fun input(t: String, def: String, ok: (String) -> Unit) {
        val e = EditText(this); e.setText(def); e.setSelectAllOnFocus(true)
        AlertDialog.Builder(this).setTitle(t).setView(e)
            .setPositiveButton("موافق") { _, _ -> ok(e.text.toString().trim()) }
            .setNegativeButton("إلغاء", null).show()
    }

    // ---------- lifecycle ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        prefs = getSharedPreferences("ex", 0)
        vm = prefs.getInt("vm", 2)
        registerReceiver(installRx, IntentFilter(INSTALL_ACTION))
        window.statusBarColor = BLUE

        adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(p: Int): Any = rows[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, g: ViewGroup?): View {
                val ctx = this@MainActivity
                val r = rows[p]
                val iconLike = r.pkg != null || r.kind == 4
                val bg = if (p in sel) 0xFFD6E4FF.toInt() else Color.WHITE
                val subTv = tv(r.sub, 12f, 0xFF888888.toInt())
                val f0 = r.file
                val fl = FrameLayout(ctx)
                val ic = ImageView(ctx)
                ic.setImageDrawable(FileIconDrawable(r.kind, if (f0 != null && !f0.isDirectory) f0.extension else ""))
                val gi = if (vm == 1) 72 else 60
                if (grid) fl.addView(ic, FrameLayout.LayoutParams(dp(gi), dp(gi), Gravity.CENTER))
                else fl.addView(ic, FrameLayout.LayoutParams(M, M))
                val iv = ImageView(ctx)
                if (iconLike) { iv.scaleType = ImageView.ScaleType.FIT_CENTER; val pd = dp(if (grid) 8 else 2); iv.setPadding(pd, pd, pd, pd) }
                else iv.scaleType = ImageView.ScaleType.CENTER_CROP
                fl.addView(iv, FrameLayout.LayoutParams(M, M))
                if (r.pkg != null) appIconAsync(r.pkg, iv)
                else if (r.file != null && (r.kind == 1 || r.kind == 2)) thumb(r.file, iv, r.kind)
                else if (r.file != null && r.kind == 4) apkAsync(r.file, iv, subTv)

                if (grid) {
                    val cell = LinearLayout(ctx); cell.orientation = LinearLayout.VERTICAL
                    cell.setPadding(dp(3), dp(6), dp(3), dp(6)); cell.setBackgroundColor(bg)
                    cell.addView(fl, LinearLayout.LayoutParams(M, dp(if (vm == 1) 92 else 76)))
                    val n = tv(r.name, 12f); n.maxLines = 2; n.ellipsize = TextUtils.TruncateAt.END
                    n.gravity = Gravity.CENTER_HORIZONTAL; n.setPadding(0, dp(4), 0, 0)
                    cell.addView(n, LinearLayout.LayoutParams(M, W))
                    return cell
                }

                val row = LinearLayout(ctx)
                row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(dp(12), dp(8), dp(12), dp(8))
                row.setBackgroundColor(bg)
                row.addView(fl, LinearLayout.LayoutParams(dp(46), dp(46)))
                val col = LinearLayout(ctx); col.orientation = LinearLayout.VERTICAL
                val n = tv(r.name, 15f); n.isSingleLine = true; n.ellipsize = TextUtils.TruncateAt.MIDDLE
                col.addView(n)
                col.addView(subTv)
                row.addView(col, LinearLayout.LayoutParams(0, W, 1f).apply { setMargins(dp(12), 0, 0, 0) })
                return row
            }
        }

        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL

        // header
        val hd = LinearLayout(this)
        hd.orientation = LinearLayout.HORIZONTAL; hd.gravity = Gravity.CENTER_VERTICAL
        hd.setBackgroundColor(BLUE); hd.setPadding(dp(6), dp(10), dp(6), dp(10))
        fun hb(t: String, a: (View) -> Unit): TextView {
            val x = tv(t, 20f, Color.WHITE); x.setPadding(dp(12), dp(4), dp(12), dp(4))
            x.setOnClickListener { a(it) }; return x
        }
        hd.addView(hb("⋮") { menu(it) })
        hd.addView(hb("🔍") { search() })
        tabRow = LinearLayout(this); tabRow.orientation = LinearLayout.HORIZONTAL; tabRow.gravity = Gravity.CENTER_VERTICAL
        val tabScroll = HorizontalScrollView(this); tabScroll.isHorizontalScrollBarEnabled = false
        tabScroll.addView(tabRow)
        hd.addView(tabScroll, LinearLayout.LayoutParams(0, W, 1f))
        hd.addView(hb("🏠") { showHome() })
        col.addView(hd)
        val crumb = LinearLayout(this); crumb.orientation = LinearLayout.HORIZONTAL; crumb.gravity = Gravity.CENTER_VERTICAL
        crumb.setBackgroundColor(0xFFF2F4F8.toInt()); crumb.setPadding(dp(10), dp(6), dp(10), dp(6))
        pill = tv("", 13f, Color.BLACK); pill.setPadding(dp(10), dp(4), dp(10), dp(4))
        pill.background = GradientDrawable().apply { setColor(0xFFDDE3EE.toInt()); cornerRadius = dp(12).toFloat() }
        pill.setOnClickListener { quick(it) }
        crumb.addView(pill)
        tvTitle = tv("", 13f, 0xFF555555.toInt()); tvTitle.isSingleLine = true; tvTitle.ellipsize = TextUtils.TruncateAt.START
        tvTitle.setPadding(dp(10), 0, 0, 0); tvTitle.setOnClickListener { up() }
        crumb.addView(tvTitle, LinearLayout.LayoutParams(0, W, 1f))
        col.addView(crumb)
        tabs.add(Tab("الرئيسية") {})

        // body
        val body = FrameLayout(this)
        homeView = ScrollView(this); homeView.setBackgroundColor(0xFFF2F4F8.toInt())
        list = GridView(this); list.setBackgroundColor(Color.WHITE); applyView(); list.adapter = adapter
        list.setOnItemClickListener { _, _, p, _ -> if (sel.isNotEmpty()) toggle(p) else openItem(rows[p]) }
        list.setOnItemLongClickListener { _, _, p, _ -> if (rows[p].pkg == null) toggle(p); true }
        body.addView(homeView, M, M); body.addView(list, M, M)
        col.addView(body, LinearLayout.LayoutParams(M, 0, 1f))

        // mini player
        miniBar = LinearLayout(this); miniBar.orientation = LinearLayout.HORIZONTAL
        miniBar.gravity = Gravity.CENTER_VERTICAL; miniBar.setBackgroundColor(0xFF263238.toInt())
        miniBar.visibility = View.GONE
        miniTitle = tv("", 13f, Color.WHITE); miniTitle.isSingleLine = true; miniTitle.ellipsize = TextUtils.TruncateAt.END
        miniBar.addView(miniTitle, LinearLayout.LayoutParams(0, W, 1f).apply { setMargins(dp(12), 0, 0, 0) })
        fun mb(t: String, a: String): TextView {
            val x = tv(t, 20f, Color.WHITE); x.setPadding(dp(12), dp(10), dp(12), dp(10))
            x.setOnClickListener { PlayerService.cmd(this, a) }; return x
        }
        miniBar.addView(mb("⏮", "prev")); miniPlay = mb("⏸", "toggle"); miniBar.addView(miniPlay)
        miniBar.addView(mb("⏭", "next")); miniBar.addView(mb("⏹", "stop"))
        col.addView(miniBar)

        // paste bar
        pasteBar = LinearLayout(this); pasteBar.setBackgroundColor(0xFFFFA000.toInt()); pasteBar.visibility = View.GONE
        pasteTv = tv("", 15f, Color.WHITE); pasteTv.setPadding(dp(16), dp(14), dp(16), dp(14))
        pasteTv.setOnClickListener { paste() }
        pasteBar.addView(pasteTv, LinearLayout.LayoutParams(0, W, 1f))
        val cn = tv("إلغاء", 15f, Color.WHITE); cn.setPadding(dp(16), dp(14), dp(16), dp(14))
        cn.setOnClickListener { clip = emptyList(); refreshBars() }
        pasteBar.addView(cn)
        col.addView(pasteBar)

        // selection bar
        selBar = LinearLayout(this); selBar.setBackgroundColor(0xFF37474F.toInt()); selBar.visibility = View.GONE
        fun bt(t: String, a: (View) -> Unit) {
            val x = tv(t, 14f, Color.WHITE); x.gravity = Gravity.CENTER; x.setPadding(0, dp(15), 0, dp(15))
            x.setOnClickListener { a(it) }; selBar.addView(x, LinearLayout.LayoutParams(0, W, 1f))
        }
        bt("نسخ") { setClip(false) }
        bt("نقل") { setClip(true) }
        bt("حذف") { del() }
        bt("ضغط") { zipSel() }
        bt("المزيد") { more(it) }
        col.addView(selBar)

        setContentView(col)
        showHome()
    }

    override fun onResume() {
        super.onResume()
        h.post(tick)
        if (hasAccess()) { if (mode == "home") buildHome() }
        else if (!asked) { asked = true; ask() }
    }

    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    override fun onDestroy() {
        try { unregisterReceiver(installRx) } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onBackPressed() {
        when {
            sel.isNotEmpty() -> clearSel()
            mode == "dir" && cwd != null && cwd != root -> up()
            mode != "home" -> showHome()
            else -> super.onBackPressed()
        }
    }

    private fun up() {
        val c = cwd
        if (mode == "dir" && c != null && c != root && c.parentFile != null) openDir(c.parentFile!!) else showHome()
    }

    // ---------- screens ----------

    private fun tiles(ts: List<Tile>, cols: Int): View {
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL
        for (chunk in ts.chunked(cols)) {
            val r = LinearLayout(this); r.orientation = LinearLayout.HORIZONTAL; r.setPadding(0, dp(6), 0, dp(6))
            for (j in 0 until cols) {
                val t = chunk.getOrNull(j)
                val c = LinearLayout(this); c.orientation = LinearLayout.VERTICAL; c.gravity = Gravity.CENTER_HORIZONTAL
                if (t != null) {
                    val ic = tv(t.ic, 22f); ic.gravity = Gravity.CENTER; ic.background = circle(t.color)
                    c.addView(ic, LinearLayout.LayoutParams(dp(50), dp(50)))
                    val l = tv(t.label, 12f, 0xFF555555.toInt()); l.gravity = Gravity.CENTER
                    l.isSingleLine = true; l.ellipsize = TextUtils.TruncateAt.END; l.setPadding(dp(2), dp(4), dp(2), 0)
                    c.addView(l)
                    c.setOnClickListener { t.act() }
                    if (t.lc != null) c.setOnLongClickListener { t.lc.invoke(); true }
                }
                r.addView(c, LinearLayout.LayoutParams(0, W, 1f))
            }
            box.addView(r)
        }
        return box
    }

    private fun infoCard(big: String, t1: String, t2: String, act: () -> Unit): View {
        val c = LinearLayout(this); c.orientation = LinearLayout.HORIZONTAL; c.gravity = Gravity.CENTER_VERTICAL
        c.background = card(); c.setPadding(dp(12), dp(14), dp(12), dp(14))
        val b = tv(big, 17f, BLUE); b.setTypeface(null, android.graphics.Typeface.BOLD)
        c.addView(b)
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv(t1, 14f)); col.addView(tv(t2, 11f, 0xFFE65100.toInt()))
        c.addView(col, LinearLayout.LayoutParams(W, W).apply { setMargins(dp(10), 0, 0, 0) })
        c.setOnClickListener { act() }
        return c
    }

    private fun bms(): MutableSet<String> = HashSet(prefs.getStringSet("bm", emptySet())!!)
    private fun addBm(f: File) { val s = bms(); s.add(f.path); prefs.edit().putStringSet("bm", s).apply(); toast("تمت الإضافة للمفضلة") }
    private fun delBm(p: String) { val s = bms(); s.remove(p); prefs.edit().putStringSet("bm", s).apply(); buildHome(); toast("تمت الإزالة") }

    private fun buildHome() {
        val c = LinearLayout(this); c.orientation = LinearLayout.VERTICAL; c.setPadding(dp(10), dp(8), dp(10), dp(16))
        if (!hasAccess()) {
            val bn = Button(this); bn.text = "منح صلاحية الوصول للملفات"; bn.setOnClickListener { ask() }; c.addView(bn)
        }
        c.addView(tiles(listOf(
            Tile("الصور", "🖼", COLORS[1]) { cat(1, "الصور") },
            Tile("الفيديو", "🎬", COLORS[2]) { cat(2, "الفيديو") },
            Tile("الموسيقى", "🎵", COLORS[3]) { cat(3, "الموسيقى") },
            Tile("التطبيقات", "🤖", COLORS[4]) { apps() },
            Tile("الضغط", "🗜", COLORS[5]) { cat(5, "الملفات المضغوطة") }), 5))

        val st = StatFs(root.path)
        val total = st.blockCountLong * st.blockSizeLong
        val used = total - st.availableBlocksLong * st.blockSizeLong
        val pct = if (total > 0) (used * 100 / total).toInt() else 0
        val rw = LinearLayout(this); rw.orientation = LinearLayout.HORIZONTAL
        rw.addView(infoCard("$pct%", "التخزين الداخلي", "${Fs.fmt(used)}/${Fs.fmt(total)}") { openDir(root) },
            LinearLayout.LayoutParams(0, W, 1f).apply { setMargins(0, dp(8), dp(4), dp(8)) })
        rw.addView(infoCard("📊", "تحليل الملفات", "ملفات كبيرة") { clean() },
            LinearLayout.LayoutParams(0, W, 1f).apply { setMargins(dp(4), dp(8), 0, dp(8)) })
        c.addView(rw)

        val g = LinearLayout(this); g.orientation = LinearLayout.VERTICAL; g.background = card(); g.setPadding(dp(6), dp(6), dp(6), dp(6))
        g.addView(tiles(listOf(
            Tile("المنظف", "🧹", 0xFF43A047.toInt()) { clean() },
            Tile("ملفات جديدة", "🆕", 0xFFFB8C00.toInt()) {
                val t = System.currentTimeMillis() - 3L * 86400000
                scan("ملفات جديدة", compareByDescending<File> { it.lastModified() }) { !it.isDirectory && it.lastModified() > t }
            },
            Tile("التنزيلات", "⬇️", 0xFF26A69A.toInt()) { openDir(File(root, "Download")) },
            Tile("المستندات", "📄", 0xFFFDD835.toInt()) {
                scan("المستندات", compareByDescending<File> { it.lastModified() }) { !it.isDirectory && Fs.ext(it) in Fs.DOC }
            },
            Tile("الكاميرا", "📷", 0xFF5C6BC0.toInt()) { openDir(File(root, "DCIM/Camera")) },
            Tile("ملفات APK", "📦", 0xFF66BB6A.toInt()) { cat(4, "ملفات APK") },
            Tile("سلة المهملات", "🗑", 0xFF78909C.toInt()) { trash.mkdirs(); openDir(trash) },
            Tile("كل الملفات", "💾", 0xFF3F51B5.toInt()) { openDir(root) },
            Tile("نسخ التطبيقات", "💽", 0xFF7E57C2.toInt()) { val d = File(root, "EXBackup"); d.mkdirs(); openDir(d) }), 4))
        c.addView(g, LinearLayout.LayoutParams(M, W).apply { setMargins(0, 0, 0, dp(8)) })

        val bx = LinearLayout(this); bx.orientation = LinearLayout.VERTICAL; bx.background = card(); bx.setPadding(dp(12), dp(12), dp(12), dp(12))
        bx.addView(tv("المفضلة", 15f, BLUE))
        val bl = bms().sorted().map { p ->
            val f = File(p)
            Tile(f.name, "📁", COLORS[0], { delBm(p) }) { openDir(f) }
        }
        if (bl.isEmpty()) bx.addView(tv("اضغط مطولاً على مجلد ثم (المزيد) لإضافته هنا", 12f, 0xFF888888.toInt()))
        else bx.addView(tiles(bl, 4))
        c.addView(bx)

        homeView.removeAllViews(); homeView.addView(c)
    }

    private fun showHome() {
        mode = "home"; cwd = null; sel.clear()
        homeView.visibility = View.VISIBLE; list.visibility = View.GONE
        setHead("الرئيسية"); buildHome(); refreshBars()
        setTab("الرئيسية") { showHome() }
    }

    private fun showRows(newRows: List<Row>, ttl: String) {
        rows.clear(); rows.addAll(newRows); sel.clear()
        homeView.visibility = View.GONE; list.visibility = View.VISIBLE
        setHead(ttl); adapter.notifyDataSetChanged(); refreshBars()
        if (newRows.isEmpty()) toast("لا يوجد شيء هنا")
    }

    private fun openDir(f: File) {
        mode = "dir"; cwd = f
        val all = (f.listFiles() ?: emptyArray()).filter { showHidden || !it.name.startsWith(".") }
        val cmp: Comparator<File> = when (sortBy) {
            1 -> compareByDescending<File> { it.lastModified() }
            2 -> compareByDescending<File> { if (it.isDirectory) -1L else it.length() }
            else -> compareBy<File> { it.name.lowercase() }
        }
        val sorted = all.sortedWith(compareBy<File> { !it.isDirectory }.then(cmp))
        val rel = f.path.removePrefix(root.path)
        showRows(sorted.map {
            val s = if (it.isDirectory) "${it.list()?.size ?: 0} عنصر" else Fs.fmt(it.length())
            Row(it.name, Fs.date(it.lastModified()) + "   " + s, Fs.kind(it), it)
        }, if (rel.isEmpty()) "الذاكرة الداخلية" else rel)
        setTab(if (f == root) "الذاكرة" else f.name) { openDir(f) }
    }

    private fun toRow(f: File): Row {
        val s = if (f.isDirectory) "" else Fs.fmt(f.length()) + "   "
        return Row(f.name, s + Fs.date(f.lastModified()) + "\n" + (f.parent ?: ""), Fs.kind(f), f)
    }

    private fun scan(ttl: String, cmp: Comparator<File>? = null, base: File = root, pred: (File) -> Boolean) {
        setHead("$ttl …")
        thread {
            val out = ArrayList<File>()
            Fs.walk(base, out, 0, pred)
            if (cmp != null) out.sortWith(cmp)
            runOnUiThread { mode = "list"; cwd = null; showRows(out.map { toRow(it) }, "$ttl (${out.size})"); setTab(ttl) { scan(ttl, cmp, base, pred) } }
        }
    }

    private fun cat(k: Int, ttl: String) =
        scan(ttl, compareByDescending<File> { it.lastModified() }) { Fs.kind(it) == k }

    private fun clean() =
        scan("ملفات كبيرة", compareByDescending<File> { it.length() }) { !it.isDirectory && it.length() > 50L * 1024 * 1024 }

    private fun apps() {
        val pm = packageManager
        val l = pm.getInstalledApplications(0).filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
        mode = "list"; cwd = null
        showRows(l.map { Row(pm.getApplicationLabel(it).toString(), it.packageName, 4, File(it.sourceDir), it.packageName) },
            "التطبيقات (${l.size})")
        setTab("التطبيقات") { apps() }
    }

    private fun refresh() {
        when (mode) {
            "home" -> buildHome()
            "dir" -> cwd?.let { openDir(it) }
            else -> showRows(rows.filter { it.pkg != null || it.file?.exists() == true }, baseTitle)
        }
    }

    private fun toBmp(d: Drawable): Bitmap {
        val bm = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 160, 160); d.draw(Canvas(bm))
        return bm
    }

    private fun extractBase(f: File): File? = try {
        java.util.zip.ZipFile(f).use { z ->
            val apks = z.entries().toList().filter { !it.isDirectory && it.name.endsWith(".apk", true) }
            val pick = apks.firstOrNull { it.name.substringAfterLast('/').equals("base.apk", true) }
                ?: apks.maxByOrNull { it.size }
            if (pick == null) null else {
                val out = File(cacheDir, "meta_" + f.path.hashCode() + ".apk")
                z.getInputStream(pick).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                out
            }
        }
    } catch (e: Exception) { null }

    private fun readApk(f: File): ApkMeta? {
        val pm = packageManager
        var tmp: File? = null
        var target = f
        if (Fs.ext(f) != "apk") { tmp = extractBase(f); target = tmp ?: return null }
        try {
            val pi = pm.getPackageArchiveInfo(target.path, PackageManager.GET_PERMISSIONS) ?: return null
            val ai = pi.applicationInfo ?: return null
            ai.sourceDir = target.path; ai.publicSourceDir = target.path
            val label = try { ai.loadLabel(pm).toString() } catch (x: Throwable) { pi.packageName }
            var icon: Bitmap? = try { toBmp(ai.loadIcon(pm)) } catch (x: Throwable) { null }
            if (icon == null) icon = try { toBmp(pm.getApplicationIcon(pi.packageName)) } catch (x: Throwable) { null }
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            return ApkMeta(label, pi.packageName, pi.versionName ?: "", code, icon, pi.requestedPermissions?.toList() ?: emptyList())
        } finally { tmp?.delete() }
    }

    private fun apkStatus(pkg: String, code: Long): String = try {
        val cur = packageManager.getPackageInfo(pkg, 0)
        val cc = if (Build.VERSION.SDK_INT >= 28) cur.longVersionCode else cur.versionCode.toLong()
        when { code > cc -> "أحدث من المثبّت"; code < cc -> "أقدم من المثبّت"; else -> "مثبّت" }
    } catch (e: Exception) { "غير مثبّت" }

    private fun appIconAsync(pkg: String, iv: ImageView) {
        val key = "pkg:$pkg"; iv.tag = key
        val c = cache.get(key)
        if (c != null) { showThumb(iv, c); return }
        pool.execute {
            val pm = packageManager
            val bm: Bitmap? = try { toBmp(pm.getApplicationIcon(pkg)) } catch (e: Throwable) {
                try { toBmp(pm.getApplicationInfo(pkg, 0).loadIcon(pm)) } catch (e2: Throwable) { null }
            }
            if (bm != null) { cache.put(key, bm); runOnUiThread { if (iv.tag == key) showThumb(iv, bm) } }
        }
    }

    private fun apkAsync(f: File, iv: ImageView, sub: TextView) {
        iv.tag = f.path; sub.tag = f.path
        val c = cache.get(f.path); val inf = apkInfo.get(f.path)
        if (c != null) showThumb(iv, c)
        if (inf != null) sub.text = inf
        if (c != null && inf != null) return
        pool.execute {
            val m = try { readApk(f) } catch (e: Throwable) { null }
            if (m != null) {
                val bk = if (f.path.contains("EXBackup")) "💾 " else ""
                val txt = "$bk${m.label} • v${m.ver}\n${apkStatus(m.pkg, m.code)} • ${Fs.fmt(f.length())}"
                apkInfo.put(f.path, txt)
                if (m.icon != null) cache.put(f.path, m.icon)
                runOnUiThread {
                    if (iv.tag == f.path && m.icon != null) showThumb(iv, m.icon)
                    if (sub.tag == f.path) sub.text = txt
                }
            }
        }
    }

    // ---------- install ----------
    private fun canInstall(): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this).setTitle("السماح بالتثبيت")
                .setMessage("اسمح لهذا التطبيق بتثبيت التطبيقات من الإعدادات، ثم ارجع واضغط تثبيت مرة أخرى.")
                .setPositiveButton("فتح الإعدادات") { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
                }.setNegativeButton("إلغاء", null).show()
            return false
        }
        return true
    }

    private fun installApk(f: File) {
        if (!canInstall()) return
        try {
            val uri = Uri.parse("content://com.exfiles.app.files" + Uri.encode(f.absolutePath, "/"))
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { toast("تعذر بدء التثبيت") }
    }

    private fun installBundle(f: File) {
        if (!canInstall()) return
        toast("جاري تجهيز التثبيت…")
        thread {
            var ses: PackageInstaller.Session? = null
            try {
                val pi = packageManager.packageInstaller
                val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
                val sn = pi.openSession(id)
                ses = sn
                var n = 0
                java.util.zip.ZipFile(f).use { z ->
                    for (e in z.entries()) {
                        if (e.isDirectory) continue
                        if (e.name.endsWith(".apk", true)) {
                            z.getInputStream(e).use { i ->
                                sn.openWrite("split_" + (n++) + ".apk", 0, e.size).use { o -> i.copyTo(o); sn.fsync(o) }
                            }
                        } else if (e.name.startsWith("Android/obb/", true)) {
                            try {
                                val out = File(root, e.name); out.parentFile?.mkdirs()
                                z.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                            } catch (x: Exception) {}
                        }
                    }
                }
                if (n == 0) { sn.abandon(); toast("لا توجد ملفات APK داخل الحزمة"); return@thread }
                val pit = PendingIntent.getBroadcast(this, id, Intent(INSTALL_ACTION).setPackage(packageName), PendingIntent.FLAG_UPDATE_CURRENT)
                sn.commit(pit.intentSender)
                sn.close()
            } catch (e: Exception) {
                try { ses?.abandon() } catch (x: Exception) {}
                toast("فشل التجهيز: ${e.message}")
            }
        }
    }

    private fun apkDialog(f: File) {
        val bundle = Fs.ext(f) != "apk"
        val items = arrayOf(if (bundle) "تثبيت (حزمة XAPK/APKS)" else "تثبيت", "تفاصيل التطبيق", "عرض المحتوى (فك الضغط)", "فتح بواسطة…")
        AlertDialog.Builder(this).setTitle(f.name).setItems(items) { _, w ->
            when (w) {
                0 -> if (bundle) installBundle(f) else installApk(f)
                1 -> apkDetails(f)
                2 -> unzipDialog(f)
                3 -> openExt(f, true)
            }
        }.show()
    }

    private fun apkDetails(f: File) {
        thread {
            val m = try { readApk(f) } catch (e: Throwable) { null }
            runOnUiThread {
                if (m == null) { toast("تعذرت قراءة الحزمة"); return@runOnUiThread }
                val perms = m.perms.map { it.substringAfterLast('.') }.take(25).joinToString("\n• ", "• ")
                AlertDialog.Builder(this).setTitle(m.label)
                    .setMessage("الحزمة: ${m.pkg}\nالإصدار: ${m.ver} (${m.code})\nالحالة: ${apkStatus(m.pkg, m.code)}\nالحجم: ${Fs.fmt(f.length())}\n\nالأذونات (${m.perms.size}):\n" +
                        if (m.perms.isEmpty()) "لا يوجد" else perms)
                    .setPositiveButton("حسناً", null).show()
            }
        }
    }

    private fun showThumb(iv: ImageView, bm: Bitmap) {
        iv.setImageBitmap(bm)
        (iv.parent as? FrameLayout)?.getChildAt(0)?.visibility = View.GONE
    }

    private fun thumb(f: File, iv: ImageView, kind: Int) {
        iv.tag = f.path
        val c = cache.get(f.path)
        if (c != null) { showThumb(iv, c); return }
        pool.execute {
            val bm: Bitmap? = try {
                when (kind) {
                    1 -> {
                        val o = BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.path, o)
                        var s = 1; while (o.outWidth / s > 300) s *= 2
                        val o2 = BitmapFactory.Options(); o2.inSampleSize = s
                        BitmapFactory.decodeFile(f.path, o2)
                    }
                    2 -> ThumbnailUtils.createVideoThumbnail(f.path, MediaStore.Video.Thumbnails.MINI_KIND)
                    else -> null
                }
            } catch (e: Throwable) { null }
            if (bm != null) { cache.put(f.path, bm); runOnUiThread { if (iv.tag == f.path) showThumb(iv, bm) } }
        }
    }

    // ---------- menus ----------
    private fun quick(a: View) {
        val locs = listOf<Pair<String, File?>>("الرئيسية" to null, "الذاكرة الداخلية" to root,
            "Download" to File(root, "Download"), "DCIM" to File(root, "DCIM"), "Pictures" to File(root, "Pictures"),
            "Movies" to File(root, "Movies"), "Music" to File(root, "Music"), "Documents" to File(root, "Documents"))
        val p = PopupMenu(this, a)
        locs.forEachIndexed { i, l -> p.menu.add(0, i, i, l.first) }
        p.setOnMenuItemClickListener {
            val f = locs[it.itemId].second
            if (f == null) showHome() else openDir(f)
            true
        }
        p.show()
    }

    private fun menu(a: View) {
        val p = PopupMenu(this, a); val m = p.menu
        if (mode == "dir") { m.add(0, 1, 0, "مجلد جديد"); m.add(0, 2, 0, "ملف جديد") }
        m.add(0, 3, 0, "ترتيب")
        m.add(0, 4, 0, if (showHidden) "إخفاء الملفات المخفية" else "إظهار الملفات المخفية")
        m.add(0, 5, 0, "تحديث")
        m.add(0, 7, 0, "عرض")
        m.add(0, 11, 0, "نافذة جديدة (تبويب)")
        m.add(0, 12, 0, "النوافذ (${tabs.size})")
        m.add(0, 13, 0, "محلل الملفات")
        m.add(0, 14, 0, "السجل (ملفات جديدة)")
        if (cwd == trash) m.add(0, 6, 0, "تفريغ سلة المهملات")
        p.setOnMenuItemClickListener { i ->
            when (i.itemId) {
                1 -> input("اسم المجلد", "") { n -> if (n.isNotEmpty() && File(cwd, n).mkdirs()) refresh() else toast("فشل") }
                2 -> input("اسم الملف", "new.txt") { n ->
                    try { if (n.isNotEmpty() && File(cwd, n).createNewFile()) refresh() else toast("فشل") } catch (e: Exception) { toast("فشل") }
                }
                3 -> AlertDialog.Builder(this).setTitle("ترتيب حسب")
                    .setItems(arrayOf("الاسم", "التاريخ", "الحجم")) { _, w -> sortBy = w; refresh() }.show()
                4 -> { showHidden = !showHidden; refresh() }
                5 -> refresh()
                7 -> AlertDialog.Builder(this).setTitle("طريقة العرض")
                    .setSingleChoiceItems(arrayOf("قائمة", "شبكة كبيرة", "شبكة صغيرة"), vm) { d, w ->
                        vm = w; prefs.edit().putInt("vm", w).apply(); applyView(); adapter.notifyDataSetChanged(); d.dismiss()
                    }.show()
                11 -> newTab { showHome() }
                12 -> {
                    val labels = tabs.map { it.label }.toTypedArray()
                    AlertDialog.Builder(this).setTitle("النوافذ").setItems(labels) { _, w ->
                        if (w != curTab) { curTab = w; renderTabs(); tabs[w].go() }
                    }.setPositiveButton("＋ نافذة جديدة") { _, _ -> newTab { showHome() } }.setNegativeButton("إغلاق", null).show()
                }
                13 -> clean()
                14 -> {
                    val t3 = System.currentTimeMillis() - 3L * 86400000
                    scan("ملفات جديدة", compareByDescending<File> { it.lastModified() }) { !it.isDirectory && it.lastModified() > t3 }
                }
                6 -> { trash.listFiles()?.forEach { Fs.delete(it) }; refresh() }
            }
            true
        }
        p.show()
    }

    private fun search() {
        input("بحث", "") { q ->
            if (q.isNotEmpty()) scan("بحث: $q", null, cwd ?: root) { it.name.contains(q, true) }
        }
    }

    // ---------- selection & operations ----------
    private fun toggle(p: Int) { if (!sel.add(p)) sel.remove(p); adapter.notifyDataSetChanged(); refreshBars() }
    private fun clearSel() { sel.clear(); adapter.notifyDataSetChanged(); refreshBars() }

    private fun refreshBars() {
        selBar.visibility = if (sel.isNotEmpty()) View.VISIBLE else View.GONE
        pasteBar.visibility = if (sel.isEmpty() && clip.isNotEmpty() && mode == "dir") View.VISIBLE else View.GONE
        pasteTv.text = (if (cut) "نقل هنا" else "لصق هنا") + " (${clip.size})"
        tvTitle.text = if (sel.isEmpty()) baseTitle else "${sel.size} محدد"
    }

    private fun selFiles(): List<File> = sel.sorted().mapNotNull { rows.getOrNull(it)?.file }

    private fun setClip(c: Boolean) {
        clip = selFiles(); cut = c; clearSel()
        toast("${clip.size} عنصر - افتح المجلد الهدف ثم اضغط لصق")
    }

    private fun paste() {
        val d = cwd ?: return
        val items = clip; val mv = cut
        clip = emptyList(); refreshBars()
        toast("جاري التنفيذ…")
        thread {
            for (f in items) {
                if (d.path == f.path || d.path.startsWith(f.path + "/")) continue
                val t = Fs.unique(File(d, f.name))
                try { if (mv) Fs.move(f, t) else Fs.copy(f, t) } catch (e: Exception) {}
            }
            runOnUiThread { openDir(d); toast("تم") }
        }
    }

    private fun del() {
        val fs = selFiles(); if (fs.isEmpty()) return
        val b = AlertDialog.Builder(this).setTitle("حذف ${fs.size} عنصر؟")
        if (cwd != trash) b.setPositiveButton("للمهملات") { _, _ -> doDel(fs, false) }
        b.setNeutralButton("حذف نهائي") { _, _ -> doDel(fs, true) }.setNegativeButton("إلغاء", null).show()
    }

    private fun doDel(fs: List<File>, perm: Boolean) {
        thread {
            trash.mkdirs()
            for (f in fs) {
                try {
                    if (perm) Fs.delete(f) else {
                        val t = File(trash, "${System.currentTimeMillis()}_${f.name}")
                        prefs.edit().putString("t_" + t.name, f.path).apply()
                        Fs.move(f, t)
                    }
                } catch (e: Exception) {}
            }
            runOnUiThread { refresh() }
        }
    }

    private fun zipSel() {
        val fs = selFiles(); if (fs.isEmpty()) return
        val dir = fs[0].parentFile ?: return
        input("اسم الملف المضغوط", if (fs.size == 1) fs[0].nameWithoutExtension else "archive") { n ->
            toast("جاري الضغط…")
            thread {
                try { Fs.zip(fs, Fs.unique(File(dir, n.removeSuffix(".zip") + ".zip"))); toast("تم الضغط") }
                catch (e: Exception) { toast("فشل: ${e.message}") }
                runOnUiThread { refresh() }
            }
        }
    }

    private fun unzipDialog(f: File) {
        AlertDialog.Builder(this).setTitle(f.name)
            .setItems(arrayOf("فك الضغط هنا", "فك الضغط إلى مجلد")) { _, w ->
                val dir = if (w == 0) f.parentFile!! else Fs.unique(File(f.parentFile, f.nameWithoutExtension))
                toast("جاري فك الضغط…")
                thread {
                    try { Fs.unzip(f, dir); toast("تم") } catch (e: Exception) { toast("فشل: ${e.message}") }
                    runOnUiThread { refresh() }
                }
            }.show()
    }

    private fun more(anchor: View) {
        val p = PopupMenu(this, anchor); val m = p.menu
        m.add(0, 1, 0, "تحديد الكل")
        if (sel.size == 1) { m.add(0, 2, 0, "إعادة تسمية"); m.add(0, 3, 0, "خصائص"); m.add(0, 4, 0, "فتح بواسطة…") }
        m.add(0, 5, 0, "مشاركة"); m.add(0, 6, 0, "إضافة للمفضلة"); m.add(0, 7, 0, "تشغيل في الخلفية")
        if (cwd == trash) m.add(0, 8, 0, "استعادة")
        if (sel.size > 1) m.add(0, 10, 0, "فتح المحدد (تنقل بين الملفات)")
        if (sel.size == 1 && selFiles().firstOrNull()?.isDirectory == true) m.add(0, 9, 0, "فتح في تبويب جديد")
        p.setOnMenuItemClickListener { i ->
            val fs = selFiles()
            when (i.itemId) {
                1 -> { for (k in rows.indices) if (rows[k].pkg == null) sel.add(k); adapter.notifyDataSetChanged(); refreshBars() }
                2 -> { val f = fs[0]; input("الاسم الجديد", f.name) { n ->
                    if (n.isNotEmpty() && f.renameTo(File(f.parentFile, n))) refresh() else toast("فشل") } }
                3 -> props(fs[0])
                4 -> openExt(fs[0], true)
                5 -> {
                    val uris = ArrayList<Uri>(fs.filter { !it.isDirectory }.map { Uri.fromFile(it) })
                    val sh = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    startActivity(Intent.createChooser(sh, "مشاركة"))
                }
                6 -> fs.filter { it.isDirectory }.forEach { addBm(it) }
                9 -> { val d = fs[0]; clearSel(); newTab { openDir(d) } }
                10 -> openMany(fs)
                7 -> {
                    val a = fs.filter { Fs.kind(it) == 3 || Fs.kind(it) == 2 }.map { it.path }
                    if (a.isEmpty()) toast("اختر ملفات صوت أو فيديو") else { askNotif(); PlayerService.play(this, a, 0); clearSel() }
                }
                8 -> {
                    for (f in fs) {
                        val o = prefs.getString("t_" + f.name, null) ?: File(root, "Restored/" + f.name).path
                        val t = Fs.unique(File(o)); t.parentFile?.mkdirs()
                        try { Fs.move(f, t) } catch (e: Exception) {}
                    }
                    refresh()
                }
            }
            true
        }
        p.show()
    }

    private fun props(f: File) {
        thread {
            val s = Fs.size(f)
            val extra = if (f.isDirectory) "\nالعناصر: ${f.list()?.size ?: 0}" else "\nالنوع: ${Fs.mime(f)}"
            runOnUiThread {
                AlertDialog.Builder(this).setTitle(f.name)
                    .setMessage("المسار: ${f.path}\nالحجم: ${Fs.fmt(s)}\nآخر تعديل: ${Fs.date(f.lastModified())}$extra")
                    .setPositiveButton("حسناً", null).show()
            }
        }
    }

    // ---------- opening ----------
    private fun openExt(f: File, choose: Boolean = false) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(f), Fs.mime(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(if (choose) Intent.createChooser(i, f.name) else i) }
        catch (e: Exception) { toast("لا يوجد تطبيق لفتح هذا الملف") }
    }

    private fun appDialog(r: Row) {
        val pkg = r.pkg ?: return
        AlertDialog.Builder(this).setTitle(r.name)
            .setItems(arrayOf("تشغيل", "نسخ APK احتياطي", "معلومات التطبيق", "إلغاء التثبيت")) { _, w ->
                when (w) {
                    0 -> packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                    1 -> thread {
                        val d = File(root, "EXBackup"); d.mkdirs()
                        val v = try { packageManager.getPackageInfo(pkg, 0).versionName } catch (e: Exception) { "" }
                        try { Fs.copy(r.file!!, Fs.unique(File(d, r.name.replace("/", "_") + "_" + v + ".apk"))); toast("تم الحفظ في EXBackup") }
                        catch (e: Exception) { toast("فشل النسخ") }
                    }
                    2 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
                    3 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
                }
            }.show()
    }

    private fun openItem(r: Row) {
        if (r.pkg != null) { appDialog(r); return }
        val f = r.file ?: return
        if (f.isDirectory) { openDir(f); return }
        val k = Fs.kind(f)
        val sib = rows.filter { it.kind == k && it.file != null && it.pkg == null }.map { it.file!!.path }
        val i = sib.indexOf(f.path)
        when (k) {
            1 -> startActivity(Intent(this, ImageActivity::class.java).putStringArrayListExtra("list", ArrayList(sib)).putExtra("i", i))
            2 -> startActivity(Intent(this, VideoActivity::class.java).putStringArrayListExtra("list", ArrayList(sib)).putExtra("i", i))
            3 -> { askNotif(); PlayerService.play(this, sib, i) }
            4 -> apkDialog(f)
            5 -> unzipDialog(f)
            6 -> if (f.length() < 2_000_000) startActivity(Intent(this, TextActivity::class.java).putExtra("p", f.path).putStringArrayListExtra("list", ArrayList(sib)).putExtra("i", i)) else openExt(f)
            else -> openExt(f)
        }
    }
}
