package com.example.car_drive

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : Activity() {

    private val githubUser = "SBUINNG"
    private val githubRepo = "Car_drive"
    private var isUpdateDialogShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(CrossyDriveView(this))

        autoCheckUpdate()
    }

    private fun autoCheckUpdate() {
        Thread {
            try {
                val url = URL("https://api.github.com/repos/$githubUser/$githubRepo/releases/latest")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    setRequestProperty("User-Agent", "CarDrive-AutoUpdater")
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val latestTag = json.getString("tag_name").removePrefix("v").trim()

                    var apkUrl: String? = null
                    val assets = json.getJSONArray("assets")
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.getString("name").endsWith(".apk")) {
                            apkUrl = asset.getString("browser_download_url")
                            break
                        }
                    }

                    val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"

                    if (apkUrl != null && isNewerVersion(latestTag, currentVersion)) {
                        runOnUiThread {
                            showUpdateConfirmDialog(latestTag, apkUrl)
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun showUpdateConfirmDialog(latestVersion: String, apkUrl: String) {
        if (isFinishing || isDestroyed || isUpdateDialogShown) return
        isUpdateDialogShown = true

        AlertDialog.Builder(this)
            .setTitle("업데이트 알림")
            .setMessage("새 버전(v$latestVersion)이 있습니다.\n업데이트하시겠습니까?")
            .setCancelable(false)
            .setPositiveButton("예") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, "새 업데이트(v$latestVersion) 다운로드 중...", Toast.LENGTH_SHORT).show()
                Thread {
                    downloadAndInstallApk(apkUrl, latestVersion)
                }.start()
            }
            .setNegativeButton("아니요") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun downloadAndInstallApk(apkUrl: String, version: String) {
        try {
            val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "CarDrive-AutoUpdater")
                connectTimeout = 10000
                readTimeout = 20000
            }

            val apkFile = File(cacheDir, "update_v$version.apk")
            if (apkFile.exists()) apkFile.delete()

            conn.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    input.copyTo(output)
                }
            }
            conn.disconnect()

            if (!apkFile.exists() || apkFile.length() <= 0L) return

            runOnUiThread {
                launchApkInstaller(apkFile)
            }
        } catch (_: Exception) {
        }
    }

    private fun launchApkInstaller(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "자동 업데이트를 위해 '이 출처 허용'을 켜주세요.", Toast.LENGTH_LONG).show()
            val permIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(permIntent)
            return
        }

        val apkUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(installIntent)
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}

class CrossyDriveView(context: Context) : View(context) {

    private enum class GameState { READY, PLAYING, GAME_OVER }
    private var state = GameState.READY

    private val prefs = context.getSharedPreferences("crossy_drive_prefs", Context.MODE_PRIVATE)
    private var bestMeters = prefs.getInt("best_meters", 0)
    private var distanceMeters = 0f

    private val laneCount = 6
    private var roadLeft = 0f
    private var roadRight = 0f
    private var laneWidth = 0f

    private var targetLane = 2
    private var playerX = 0f
    private var playerY = 0f
    private var playerWidth = 0f
    private var playerHeight = 0f
    private var hopAnim = 0f

    private var currentSpeed = 650f
    private var speedLevel = 1
    private var speedUpBannerTime = 0f

    private var touchDownX = 0f
    private var movedInCurrentTouch = false

    private var roadScrollY = 0f
    private var nextSpawnY = -200f
    private var rowCounter = 0

    private var shakeTime = 0f

    private data class Obstacle(
        var x: Float,
        var y: Float,
        val width: Float,
        val height: Float,
        val speedY: Float,
        val color: Int,
        val isBarricade: Boolean
    )

    private data class Pedestrian(
        var x: Float,
        var y: Float,
        val size: Float,
        var speedX: Float,
        val shirtColor: Int,
        var walkPhase: Float
    )

    private data class Crosswalk(
        var y: Float,
        val height: Float
    )

    private val obstacles = mutableListOf<Obstacle>()
    private val pedestrians = mutableListOf<Pedestrian>()
    private val crosswalks = mutableListOf<Crosswalk>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastFrameNano = 0L

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val roadWidth = w * 0.82f
        roadLeft = (w - roadWidth) / 2f
        roadRight = roadLeft + roadWidth
        laneWidth = roadWidth / laneCount

        playerWidth = laneWidth * 0.62f
        playerHeight = playerWidth * 1.65f
        playerY = h * 0.76f
        playerX = getLaneCenterX(targetLane)
    }

    private fun getLaneCenterX(lane: Int): Float {
        return roadLeft + laneWidth * (lane + 0.5f)
    }

    private fun startNewGame() {
        obstacles.clear()
        pedestrians.clear()
        crosswalks.clear()

        targetLane = 2
        playerX = getLaneCenterX(targetLane)
        hopAnim = 0f
        distanceMeters = 0f
        speedLevel = 1
        speedUpBannerTime = 0f
        currentSpeed = height * 0.36f
        nextSpawnY = height * 0.25f
        rowCounter = 0
        shakeTime = 0f

        while (nextSpawnY > -height) {
            spawnNextRow()
        }
        state = GameState.PLAYING
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = System.nanoTime()
        val dt = if (lastFrameNano == 0L) 0.016f else min((now - lastFrameNano) / 1_000_000_000f, 0.05f)
        lastFrameNano = now

        if (state == GameState.PLAYING) {
            updateGame(dt)
        } else if (shakeTime > 0f) {
            shakeTime = max(0f, shakeTime - dt)
        }

        canvas.save()
        if (shakeTime > 0f) {
            val mag = shakeTime * 35f
            canvas.translate(Random.nextFloat() * mag - mag / 2f, Random.nextFloat() * mag - mag / 2f)
        }

        drawEnvironment(canvas)
        drawCrosswalks(canvas)
        drawObstacles(canvas)
        drawPedestrians(canvas)
        drawPlayerCar(canvas)

        canvas.restore()

        drawHUD(canvas)
        postInvalidateOnAnimation()
    }

    private fun updateGame(dt: Float) {
        val newLevel = (distanceMeters.toInt() / 50) + 1
        if (newLevel > speedLevel) {
            speedLevel = newLevel
            speedUpBannerTime = 1.4f
        }
        if (speedUpBannerTime > 0f) {
            speedUpBannerTime = max(0f, speedUpBannerTime - dt)
        }

        val initialSpeed = height * 0.36f
        val stepBonus = (height * 0.055f) * (speedLevel - 1)
        val targetSpeed = min(initialSpeed + stepBonus, height * 0.95f)
        currentSpeed += (targetSpeed - currentSpeed) * min(1f, dt * 8f)

        val dy = currentSpeed * dt
        roadScrollY += dy
        nextSpawnY += dy

        val pixelsPerMeter = height * 0.055f
        distanceMeters += dy / pixelsPerMeter

        val desiredX = getLaneCenterX(targetLane)
        playerX += (desiredX - playerX) * min(1f, dt * 18f)
        if (hopAnim > 0f) {
            hopAnim = max(0f, hopAnim - dt * 4.5f)
        }

        while (nextSpawnY > -250f) {
            spawnNextRow()
        }

        val cwIter = crosswalks.iterator()
        while (cwIter.hasNext()) {
            val cw = cwIter.next()
            cw.y += dy
            if (cw.y > height + 200f) cwIter.remove()
        }

        val obsIter = obstacles.iterator()
        while (obsIter.hasNext()) {
            val obs = obsIter.next()
            obs.y += dy - (obs.speedY * dt)
            if (obs.y > height + 300f) {
                obsIter.remove()
                continue
            }
            if (checkCollision(playerX, playerY, playerWidth * 0.78f, playerHeight * 0.82f,
                    obs.x, obs.y, obs.width * 0.85f, obs.height * 0.85f)) {
                triggerGameOver()
                return
            }
        }

        val pedIter = pedestrians.iterator()
        while (pedIter.hasNext()) {
            val ped = pedIter.next()
            ped.y += dy
            ped.x += ped.speedX * dt
            ped.walkPhase += dt * 12f

            if (ped.x < roadLeft - laneWidth * 0.3f) {
                ped.x = roadLeft - laneWidth * 0.3f
                ped.speedX = abs(ped.speedX)
            } else if (ped.x > roadRight + laneWidth * 0.3f) {
                ped.x = roadRight + laneWidth * 0.3f
                ped.speedX = -abs(ped.speedX)
            }

            if (ped.y > height + 200f) {
                pedIter.remove()
                continue
            }
            if (checkCollision(playerX, playerY, playerWidth * 0.75f, playerHeight * 0.8f,
                    ped.x, ped.y, ped.size * 0.75f, ped.size * 0.75f)) {
                triggerGameOver()
                return
            }
        }
    }

    private fun spawnNextRow() {
        rowCounter++
        val rowGap = playerHeight * Random.nextFloat().let { 1.75f + it * 0.65f }
        nextSpawnY -= rowGap

        if (rowCounter % 4 == 0) {
            val cwHeight = laneWidth * 0.9f
            crosswalks.add(Crosswalk(nextSpawnY, cwHeight))

            val pedCount = if (distanceMeters > 150f) Random.nextInt(1, 3) else 1
            repeat(pedCount) {
                val fromLeft = Random.nextBoolean()
                val startX = if (fromLeft) roadLeft - laneWidth * 0.2f else roadRight + laneWidth * 0.2f
                val walkSpeed = (laneWidth * (1.1f + Random.nextFloat() * 0.8f)) * if (fromLeft) 1f else -1f
                val shirtColors = intArrayOf(
                    Color.parseColor("#FF7043"),
                    Color.parseColor("#29B6F6"),
                    Color.parseColor("#AB47BC"),
                    Color.parseColor("#FFEE58")
                )
                pedestrians.add(
                    Pedestrian(
                        x = startX,
                        y = nextSpawnY + Random.nextFloat() * 20f - 10f,
                        size = laneWidth * 0.38f,
                        speedX = walkSpeed,
                        shirtColor = shirtColors.random(),
                        walkPhase = Random.nextFloat() * 6f
                    )
                )
            }
        } else {
            val maxObstacles = if (distanceMeters > 200f) 4 else 3
            val count = Random.nextInt(1, maxObstacles + 1)
            val chosenLanes = (0 until laneCount).shuffled().take(count)

            val carColors = intArrayOf(
                Color.parseColor("#42A5F5"),
                Color.parseColor("#66BB6A"),
                Color.parseColor("#FFA726"),
                Color.parseColor("#ECEFF1"),
                Color.parseColor("#7E57C2")
            )

            for (lane in chosenLanes) {
                val isBarricade = Random.nextFloat() < 0.38f
                if (isBarricade) {
                    obstacles.add(
                        Obstacle(
                            x = getLaneCenterX(lane),
                            y = nextSpawnY,
                            width = laneWidth * 0.68f,
                            height = laneWidth * 0.38f,
                            speedY = 0f,
                            color = Color.parseColor("#FF6D00"),
                            isBarricade = true
                        )
                    )
                } else {
                    val trafficSpeed = currentSpeed * (0.28f + Random.nextFloat() * 0.22f)
                    obstacles.add(
                        Obstacle(
                            x = getLaneCenterX(lane),
                            y = nextSpawnY,
                            width = laneWidth * 0.60f,
                            height = laneWidth * 1.55f,
                            speedY = trafficSpeed,
                            color = carColors.random(),
                            isBarricade = false
                        )
                    )
                }
            }
        }
    }

    private fun checkCollision(
        ax: Float, ay: Float, aw: Float, ah: Float,
        bx: Float, by: Float, bw: Float, bh: Float
    ): Boolean {
        return abs(ax - bx) * 2f < (aw + bw) && abs(ay - by) * 2f < (ah + bh)
    }

    private fun triggerGameOver() {
        state = GameState.GAME_OVER
        shakeTime = 0.45f
        val finalScore = distanceMeters.toInt()
        if (finalScore > bestMeters) {
            bestMeters = finalScore
            prefs.edit().putInt("best_meters", bestMeters).apply()
        }
    }

    private fun drawEnvironment(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#7CB342"))

        val curbWidth = 22f
        paint.color = Color.parseColor("#CFD8DC")
        canvas.drawRect(roadLeft - curbWidth, 0f, roadRight + curbWidth, height.toFloat(), paint)

        paint.color = Color.parseColor("#37474F")
        canvas.drawRect(roadLeft, 0f, roadRight, height.toFloat(), paint)

        paint.color = Color.parseColor("#90A4AE")
        val dashLen = 65f
        val gapLen = 55f
        val cycle = dashLen + gapLen
        val offset = roadScrollY % cycle

        for (i in 1 until laneCount) {
            val lineX = roadLeft + laneWidth * i
            var y = offset - cycle
            while (y < height) {
                canvas.drawRect(lineX - 4f, y, lineX + 4f, y + dashLen, paint)
                y += cycle
            }
        }
    }

    private fun drawCrosswalks(canvas: Canvas) {
        paint.color = Color.parseColor("#ECEFF1")
        for (cw in crosswalks) {
            val stripeW = laneWidth * 0.45f
            val step = laneWidth * 0.85f
            var x = roadLeft + 18f
            while (x + stripeW < roadRight - 10f) {
                canvas.drawRect(x, cw.y - cw.height / 2f, x + stripeW, cw.y + cw.height / 2f, paint)
                x += step
            }
        }
    }

    private fun drawObstacles(canvas: Canvas) {
        for (obs in obstacles) {
            if (obs.isBarricade) {
                drawBlockBarricade(canvas, obs.x, obs.y, obs.width, obs.height)
            } else {
                drawVoxelCar(canvas, obs.x, obs.y, obs.width, obs.height, obs.color, 0f)
            }
        }
    }

    private fun drawBlockBarricade(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        val left = cx - w / 2f
        val top = cy - h / 2f
        val depth = 12f

        paint.color = Color.argb(80, 0, 0, 0)
        canvas.drawRect(left + 8f, top + 10f, left + w + 8f, top + h + 10f, paint)

        paint.color = Color.parseColor("#BF360C")
        canvas.drawRect(left, top + depth, left + w, top + h + depth, paint)

        paint.color = Color.parseColor("#FF6D00")
        canvas.drawRect(left, top, left + w, top + h, paint)

        paint.color = Color.WHITE
        canvas.drawRect(left + w * 0.2f, top, left + w * 0.38f, top + h, paint)
        canvas.drawRect(left + w * 0.62f, top, left + w * 0.8f, top + h, paint)
    }

    private fun drawPedestrians(canvas: Canvas) {
        for (ped in pedestrians) {
            val bounce = abs(sin(ped.walkPhase)) * 8f
            val s = ped.size
            val cx = ped.x
            val cy = ped.y - bounce

            paint.color = Color.argb(85, 0, 0, 0)
            canvas.drawOval(cx - s * 0.55f, ped.y - s * 0.25f, cx + s * 0.55f, ped.y + s * 0.35f, paint)

            paint.color = ped.shirtColor
            canvas.drawRect(cx - s * 0.42f, cy - s * 0.35f, cx + s * 0.42f, cy + s * 0.35f, paint)

            paint.color = Color.parseColor("#FFCCBC")
            canvas.drawRect(cx - s * 0.32f, cy - s * 0.72f, cx + s * 0.32f, cy - s * 0.22f, paint)

            paint.color = Color.parseColor("#3E2723")
            canvas.drawRect(cx - s * 0.32f, cy - s * 0.78f, cx + s * 0.32f, cy - s * 0.56f, paint)
        }
    }

    private fun drawPlayerCar(canvas: Canvas) {
        val jumpOffset = sin(hopAnim * Math.PI).toFloat() * 24f
        drawVoxelCar(
            canvas = canvas,
            cx = playerX,
            cy = playerY - jumpOffset,
            w = playerWidth,
            h = playerHeight,
            bodyColor = Color.parseColor("#E53935"),
            extraShadow = jumpOffset
        )
    }

    private fun drawVoxelCar(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        bodyColor: Int,
        extraShadow: Float
    ) {
        val left = cx - w / 2f
        val top = cy - h / 2f
        val depth = 16f

        paint.color = Color.argb(90, 0, 0, 0)
        canvas.drawRoundRect(
            left + 10f,
            top + 14f + extraShadow,
            left + w + 10f,
            top + h + 14f + extraShadow,
            12f, 12f, paint
        )

        paint.color = Color.parseColor("#212121")
        val tireW = w * 0.14f
        val tireH = h * 0.20f
        canvas.drawRect(left - tireW * 0.6f, top + h * 0.15f, left + tireW * 0.4f, top + h * 0.15f + tireH, paint)
        canvas.drawRect(left + w - tireW * 0.4f, top + h * 0.15f, left + w + tireW * 0.6f, top + h * 0.15f + tireH, paint)
        canvas.drawRect(left - tireW * 0.6f, top + h * 0.65f, left + tireW * 0.4f, top + h * 0.65f + tireH, paint)
        canvas.drawRect(left + w - tireW * 0.4f, top + h * 0.65f, left + w + tireW * 0.6f, top + h * 0.65f + tireH, paint)

        paint.color = darkenColor(bodyColor, 0.72f)
        canvas.drawRoundRect(left, top + depth, left + w, top + h + depth, 12f, 12f, paint)

        paint.color = bodyColor
        canvas.drawRoundRect(left, top, left + w, top + h, 12f, 12f, paint)

        paint.color = Color.parseColor("#263238")
        canvas.drawRect(left + w * 0.16f, top + h * 0.22f, left + w * 0.84f, top + h * 0.42f, paint)

        paint.color = lightenColor(bodyColor, 1.15f)
        canvas.drawRect(left + w * 0.18f, top + h * 0.42f, left + w * 0.82f, top + h * 0.72f, paint)

        paint.color = Color.parseColor("#FFF59D")
        canvas.drawRect(left + w * 0.14f, top + 4f, left + w * 0.34f, top + 14f, paint)
        canvas.drawRect(left + w * 0.66f, top + 4f, left + w * 0.86f, top + 14f, paint)
    }

    private fun drawHUD(canvas: Canvas) {
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

        paint.textSize = 72f
        paint.color = Color.argb(110, 0, 0, 0)
        canvas.drawText("${distanceMeters.toInt()} m", width / 2f + 4f, 134f, paint)
        paint.color = Color.WHITE
        canvas.drawText("${distanceMeters.toInt()} m", width / 2f, 130f, paint)

        paint.textSize = 34f
        paint.color = Color.parseColor("#FFF59D")
        canvas.drawText("BEST: ${bestMeters} m  |  SPEED Lv.$speedLevel", width / 2f, 180f, paint)

        if (speedUpBannerTime > 0f && state == GameState.PLAYING) {
            paint.textSize = 44f
            paint.color = Color.parseColor("#FF7043")
            canvas.drawText("SPEED UP!", width / 2f, 245f, paint)
        }

        if (state != GameState.PLAYING) {
            paint.color = Color.argb(145, 0, 0, 0)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

            paint.color = Color.WHITE
            paint.textSize = 68f
            val title = if (state == GameState.READY) "CROSSY DRIVE" else "CRASH!"
            canvas.drawText(title, width / 2f, height * 0.42f, paint)

            if (state == GameState.GAME_OVER) {
                paint.textSize = 46f
                paint.color = Color.parseColor("#80CBC4")
                canvas.drawText("주행 거리: ${distanceMeters.toInt()} m", width / 2f, height * 0.50f, paint)
            }

            paint.textSize = 38f
            paint.color = Color.parseColor("#FFD54F")
            val subText = if (state == GameState.READY) "화면을 터치해 출발하세요" else "화면을 터치해 다시 도전"
            canvas.drawText(subText, width / 2f, height * 0.58f, paint)

            paint.textSize = 28f
            paint.color = Color.LTGRAY
            canvas.drawText("좌/우 터치 또는 스와이프: 차선 이동", width / 2f, height * 0.65f, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (state == GameState.READY || state == GameState.GAME_OVER) {
                    if (shakeTime <= 0.1f) {
                        startNewGame()
                    }
                    return true
                }
                touchDownX = event.x
                movedInCurrentTouch = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (state == GameState.PLAYING && !movedInCurrentTouch) {
                    val dx = event.x - touchDownX
                    if (abs(dx) > 55f) {
                        if (dx < 0) moveLane(-1) else moveLane(1)
                        movedInCurrentTouch = true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (state == GameState.PLAYING && !movedInCurrentTouch) {
                    if (event.x < width / 2f) moveLane(-1) else moveLane(1)
                }
            }
        }
        return true
    }

    private fun moveLane(delta: Int) {
        val next = (targetLane + delta).coerceIn(0, laneCount - 1)
        if (next != targetLane) {
            targetLane = next
            hopAnim = 1f
        }
    }

    private fun darkenColor(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun lightenColor(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}