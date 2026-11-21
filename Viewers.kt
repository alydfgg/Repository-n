package com.exfiles.app

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.graphics.*
import android.media.ExifInterface
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.*
import java.io.File
import kotlin.math.abs

class VideoActivity : Activity() {
    private lateinit var vv: VideoView
    private lateinit var bar: LinearLayout
    private var paths = ArrayList<String>()
    private var i = 0
    private var mpl: MediaPlayer? = null
    private val speeds = floatArrayOf(1f, 1.5f, 2f, 0.5f)
    private var si = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (PlayerService.active) PlayerService.cmd(this, "stop")
        paths = intent.getStringArrayListExtra("list") ?: ArrayList()
        i = intent.getIntExtra("i", 0)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val fl = FrameLayout(this); fl.setBackgroundColor(Color.BLACK)
        vv = VideoView(this)
        fl.addView(vv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        vv.setMediaController(MediaController(this))
        bar = LinearLayout(this); bar.setBackgroundColor(0x88000000.toInt()); bar.gravity = Gravity.CENTER_VERTICAL
        fun bt(t: String, a: (TextView) -> Unit) {
            val x = TextView(this); x.text = t; x.textSize = 20f; x.setTextColor(Color.WHITE)
            x.setPadding(24, 16, 24, 16); x.setOnClickListener { a(x) }; bar.addView(x)
        }
        bt("⏮") { play(i - 1) }
        bt("⏭") { play(i + 1) }
        bt("1x") { s -> si = (si + 1) % speeds.size; s.text = "${speeds[si]}x"; setSpeed() }
        bt("🎧") { PlayerService.play(this, ArrayList(paths), i, vv.currentPosition); finish() }
        bt("⧉") { pip() }
        fl.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        setContentView(fl)
        play(i)
    }

    private fun play(n: Int) {
        if (paths.isEmpty()) { finish(); return }
        i = (n + paths.size) % paths.size
        val p = paths[i]
        vv.setOnPreparedListener { m ->
            mpl = m; setSpeed()
            val pos = getSharedPreferences("ex", 0).getInt("vp_$p", 0)
            if (pos > 0 && pos < m.duration - 3000) vv.seekTo(pos)
        }
        vv.setOnCompletionListener { if (i < paths.size - 1) play(i + 1) else finish() }
        vv.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "تعذر تشغيل الملف", Toast.LENGTH_SHORT).show(); true
        }
        vv.setVideoPath(p); vv.start()
        Toast.makeText(this, File(p).name, Toast.LENGTH_SHORT).show()
    }

    private fun setSpeed() {
        try { mpl?.let { it.playbackParams = it.playbackParams.setSpeed(speeds[si]) } } catch (e: Exception) {}
    }

    private fun pip() {
        if (Build.VERSION.SDK_INT >= 26) try {
            enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        } catch (e: Exception) {}
    }

    override fun onUserLeaveHint() { if (vv.isPlaying) pip() }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        bar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        if (paths.isNotEmpty())
            getSharedPreferences("ex", 0).edit().putInt("vp_" + paths[i], vv.currentPosition).apply()
    }

    override fun onDestroy() { vv.stopPlayback(); super.onDestroy() }
}

class ImageActivity : Activity() {
    private lateinit var iv: ImageView
    private lateinit var info: TextView
    private var paths = ArrayList<String>()
    private var i = 0
    private var sc = 1f

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        paths = intent.getStringArrayListExtra("list") ?: ArrayList()
        i = intent.getIntExtra("i", 0)
        val fl = FrameLayout(this); fl.setBackgroundColor(Color.BLACK)
        iv = ImageView(this); iv.scaleType = ImageView.ScaleType.FIT_CENTER
        fl.addView(iv, FrameLayout.LayoutParams(-1, -1))
        info = TextView(this); info.setTextColor(Color.WHITE); info.setBackgroundColor(0x88000000.toInt())
        info.setPadding(24, 16, 24, 16)
        fl.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM))
        val sd = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                sc = (sc * d.scaleFactor).coerceIn(1f, 6f)
                iv.scaleX = sc; iv.scaleY = sc
                if (sc <= 1f) { iv.translationX = 0f; iv.translationY = 0f }
                return true
            }
        })
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent?): Boolean = true
            override fun onDoubleTap(e: MotionEvent?): Boolean { show(i); return true }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent?, dx: Float, dy: Float): Boolean {
                if (sc > 1.05f) { iv.translationX -= dx; iv.translationY -= dy; return true }
                return false
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent?, vx: Float, vy: Float): Boolean {
                if (sc <= 1.05f && abs(vx) > abs(vy) && abs(vx) > 800) {
                    show(if (vx < 0) i + 1 else i - 1); return true
                }
                return false
            }
        })
        iv.setOnTouchListener { _, e -> sd.onTouchEvent(e); gd.onTouchEvent(e); true }
        setContentView(fl)
        show(i)
    }

    private fun show(n: Int) {
        if (paths.isEmpty()) return
        i = (n + paths.size) % paths.size
        sc = 1f; iv.scaleX = 1f; iv.scaleY = 1f; iv.translationX = 0f; iv.translationY = 0f
        val p = paths[i]
        val o = BitmapFactory.Options(); o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(p, o)
        var s = 1
        while (o.outWidth / s > 2400 || o.outHeight / s > 2400) s *= 2
        val o2 = BitmapFactory.Options(); o2.inSampleSize = s
        var bm = BitmapFactory.decodeFile(p, o2)
        val deg = try {
            when (ExifInterface(p).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f
                3 -> 180f
                8 -> 270f
                else -> 0f
            }
        } catch (e: Exception) { 0f }
        if (bm != null && deg != 0f) {
            val m = Matrix(); m.postRotate(deg)
            bm = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
        }
        iv.setImageBitmap(bm)
        info.text = "${i + 1}/${paths.size}   " + File(p).name
    }
}

class TextActivity : Activity() {
    private lateinit var et: EditText
    private lateinit var ttl: TextView
    private lateinit var sv: ScrollView
    private var paths = ArrayList<String>()
    private var i = 0
    private var orig = ""
    private var canSave = true

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        paths = intent.getStringArrayListExtra("list") ?: ArrayList()
        val one = intent.getStringExtra("p")
        if (paths.isEmpty() && one != null) paths.add(one)
        if (paths.isEmpty()) { finish(); return }
        i = intent.getIntExtra("i", 0).coerceIn(0, paths.size - 1)

        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        val bar = LinearLayout(this); bar.setBackgroundColor(0xFF2F6BE8.toInt()); bar.gravity = Gravity.CENTER_VERTICAL
        fun bt(t: String, a: () -> Unit): TextView {
            val x = TextView(this); x.text = t; x.textSize = 18f; x.setTextColor(Color.WHITE)
            x.setPadding(24, 24, 24, 24); x.setOnClickListener { a() }; return x
        }
        if (paths.size > 1) bar.addView(bt("⏮") { go(i - 1) })
        ttl = TextView(this); ttl.textSize = 15f; ttl.setTextColor(Color.WHITE); ttl.isSingleLine = true
        bar.addView(ttl, LinearLayout.LayoutParams(0, -2, 1f))
        if (paths.size > 1) bar.addView(bt("⏭") { go(i + 1) })
        bar.addView(bt("💾 حفظ") { save() })
        col.addView(bar)
        et = EditText(this)
        et.gravity = Gravity.TOP or Gravity.START; et.setBackgroundColor(Color.WHITE)
        et.typeface = Typeface.MONOSPACE; et.minLines = 20
        sv = ScrollView(this); sv.addView(et)
        col.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(col)
        load(i)
    }

    private fun dirty() = canSave && et.text.toString() != orig

    private fun save(): Boolean {
        if (!canSave) return false
        return try {
            File(paths[i]).writeText(et.text.toString()); orig = et.text.toString()
            Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show(); true
        } catch (e: Exception) { Toast.makeText(this, "فشل الحفظ", Toast.LENGTH_SHORT).show(); false }
    }

    private fun load(n: Int) {
        i = n
        val f = File(paths[i])
        canSave = f.length() < 2_000_000
        var t = ""
        if (canSave) try { t = f.readText() } catch (e: Exception) { canSave = false }
        if (!canSave) Toast.makeText(this, "ملف كبير أو غير قابل للقراءة", Toast.LENGTH_SHORT).show()
        orig = t; et.setText(t); et.isEnabled = canSave
        ttl.text = (if (paths.size > 1) "${i + 1}/${paths.size}  " else "") + f.name
        sv.scrollTo(0, 0)
    }

    private fun go(n: Int) {
        val t = (n + paths.size) % paths.size
        if (dirty()) {
            AlertDialog.Builder(this).setTitle("حفظ التغييرات؟")
                .setPositiveButton("حفظ") { _, _ -> if (save()) load(t) }
                .setNegativeButton("تجاهل") { _, _ -> load(t) }
                .setNeutralButton("إلغاء", null).show()
        } else load(t)
    }

    override fun onBackPressed() {
        if (dirty()) {
            AlertDialog.Builder(this).setTitle("حفظ التغييرات؟")
                .setPositiveButton("حفظ") { _, _ -> if (save()) finish() }
                .setNegativeButton("تجاهل") { _, _ -> finish() }
                .setNeutralButton("إلغاء", null).show()
        } else super.onBackPressed()
    }
}
