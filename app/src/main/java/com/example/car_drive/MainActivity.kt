package com.example.car_drive

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.Window
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(CrossyDriveView(this))
    }
}

class CrossyDriveView(context: Context) : View(context) {

    // 게임 상태
    private enum class GameState { READY, PLAYING, GAME_OVER }
    private var state = GameState.READY

    // 기록 저장
    private val prefs = context.getSharedPreferences("crossy_drive_prefs", Context.MODE_PRIVATE)
    private var bestMeters = prefs.getInt("best_meters", 0)
    private var distanceMeters = 0f

    // 도로 설정 (5차선)
    private val laneCount = 5
    private var roadLeft = 0f
    private var roadRight = 0f
    private var laneWidth = 0f

    // 플레이어 차량 상태
    private var targetLane = 2 // 0 ~ 4 (가운데 2번)
    private var playerX = 0f
    private var playerY = 0f
    private var playerWidth = 0f
    private var playerHeight = 0f
    private var hopAnim = 0f // 차선 변경 시 통통 튀는 애니메이션 값 (0~1)

    // 주행 속도 및 단계 가속 상태 (50m마다 상승)
    private var currentSpeed = 650f
    private var speedLevel = 1
    private var speedUpBannerTime = 0f

    // 터치 입력 상태
    private var touchDownX = 0f
    private var movedInCurrentTouch = false

    // 스크롤 및 스폰 관리
    private var roadScrollY = 0f
    private var nextSpawnY = -200f
    private var rowCounter = 0

    // 화면 흔들림 (충돌 연출)
    private var shakeTime = 0f

    // 데이터 모델
    private data class Obstacle(
        var x: Float,
        var y: Float,
        val width: Float,
        val height: Float,
        val speedY: Float, // 0이면 고정 장애물(바리케이드), >0이면 주행 차량
        val color: Int,
        val isBarricade: Boolean
    )

    private data class Pedestrian(
        var x: Float,
        var y: Float,
        val size: Float,
        var speedX: Float, // 좌우 횡단 속도
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
        val roadWidth = w * 0.78f
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

        // 초반 도로 미리 채우기
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
        // 50m마다 속도 단계 상승
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

        // 주행 거리(m) 누적
        val pixelsPerMeter = height * 0.055f
        distanceMeters += dy / pixelsPerMeter

        // 플레이어 차선 부드러운 이동 & 호핑 애니메이션
        val desiredX = getLaneCenterX(targetLane)
        playerX += (desiredX - playerX) * min(1f, dt * 18f)
        if (hopAnim > 0f) {
            hopAnim = max(0f, hopAnim - dt * 4.5f)
        }

        // 새로운 도로 패턴 생성
        while (nextSpawnY > -250f) {
            spawnNextRow()
        }

        // 횡단보도 이동
        val cwIter = crosswalks.iterator()
        while (cwIter.hasNext()) {
            val cw = cwIter.next()
            cw.y += dy
            if (cw.y > height + 200f) cwIter.remove()
        }

        // 장애물 및 차량 이동 & 충돌 판정
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

        // 보행자 횡단 이동 & 충돌 판정
        val pedIter = pedestrians.iterator()
        while (pedIter.hasNext()) {
            val ped = pedIter.next()
            ped.y += dy
            ped.x += ped.speedX * dt
            ped.walkPhase += dt * 12f

            // 인도 끝에 닿으면 반대 방향으로 되돌아감
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

        // 4번째 줄마다 횡단보도 + 보행자 생성
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
            // 일반 도로: 1~3개 차선에 장애물 또는 다른 차량 배치 (최소 2개 차선은 반드시 비워둠)
            val maxObstacles = if (distanceMeters > 200f) 3 else 2
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

    // --- 렌더링 (길건너 친구들풍 복셀 입체감) ---

    private fun drawEnvironment(canvas: Canvas) {
        // 양옆 잔디밭 배경
        canvas.drawColor(Color.parseColor("#7CB342"))

        // 연석(인도) 테두리
        val curbWidth = 22f
        paint.color = Color.parseColor("#CFD8DC")
        canvas.drawRect(roadLeft - curbWidth, 0f, roadRight + curbWidth, height.toFloat(), paint)

        // 아스팔트 도로
        paint.color = Color.parseColor("#37474F")
        canvas.drawRect(roadLeft, 0f, roadRight, height.toFloat(), paint)

        // 차선 점선 스크롤
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

        // 그림자
        paint.color = Color.argb(80, 0, 0, 0)
        canvas.drawRect(left + 8f, top + 10f, left + w + 8f, top + h + 10f, paint)

        // 입체 하단 측면
        paint.color = Color.parseColor("#BF360C")
        canvas.drawRect(left, top + depth, left + w, top + h + depth, paint)

        // 상단 주황 바리케이드 본체
        paint.color = Color.parseColor("#FF6D00")
        canvas.drawRect(left, top, left + w, top + h, paint)

        // 흰색 경고 줄무늬
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

            // 그림자
            paint.color = Color.argb(85, 0, 0, 0)
            canvas.drawOval(cx - s * 0.55f, ped.y - s * 0.25f, cx + s * 0.55f, ped.y + s * 0.35f, paint)

            // 몸통 (블록 형태)
            paint.color = ped.shirtColor
            canvas.drawRect(cx - s * 0.42f, cy - s * 0.35f, cx + s * 0.42f, cy + s * 0.35f, paint)

            // 머리 (살구색 복셀 블록)
            paint.color = Color.parseColor("#FFCCBC")
            canvas.drawRect(cx - s * 0.32f, cy - s * 0.72f, cx + s * 0.32f, cy - s * 0.22f, paint)

            // 머리카락 상단
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

        // 바닥 그림자
        paint.color = Color.argb(90, 0, 0, 0)
        canvas.drawRoundRect(
            left + 10f,
            top + 14f + extraShadow,
            left + w + 10f,
            top + h + 14f + extraShadow,
            12f, 12f, paint
        )

        // 타이어 4개
        paint.color = Color.parseColor("#212121")
        val tireW = w * 0.14f
        val tireH = h * 0.20f
        canvas.drawRect(left - tireW * 0.6f, top + h * 0.15f, left + tireW * 0.4f, top + h * 0.15f + tireH, paint)
        canvas.drawRect(left + w - tireW * 0.4f, top + h * 0.15f, left + w + tireW * 0.6f, top + h * 0.15f + tireH, paint)
        canvas.drawRect(left - tireW * 0.6f, top + h * 0.65f, left + tireW * 0.4f, top + h * 0.65f + tireH, paint)
        canvas.drawRect(left + w - tireW * 0.4f, top + h * 0.65f, left + w + tireW * 0.6f, top + h * 0.65f + tireH, paint)

        // 차체 입체 측면 (어두운 톤)
        paint.color = darkenColor(bodyColor, 0.72f)
        canvas.drawRoundRect(left, top + depth, left + w, top + h + depth, 12f, 12f, paint)

        // 차체 상단 메인 컬러
        paint.color = bodyColor
        canvas.drawRoundRect(left, top, left + w, top + h, 12f, 12f, paint)

        // 앞유리창
        paint.color = Color.parseColor("#263238")
        canvas.drawRect(left + w * 0.16f, top + h * 0.22f, left + w * 0.84f, top + h * 0.42f, paint)

        // 지붕 (밝은 톤 하이라이트)
        paint.color = lightenColor(bodyColor, 1.15f)
        canvas.drawRect(left + w * 0.18f, top + h * 0.42f, left + w * 0.82f, top + h * 0.72f, paint)

        // 전조등 (헤드라이트)
        paint.color = Color.parseColor("#FFF59D")
        canvas.drawRect(left + w * 0.14f, top + 4f, left + w * 0.34f, top + 14f, paint)
        canvas.drawRect(left + w * 0.66f, top + 4f, left + w * 0.86f, top + 14f, paint)
    }

    private fun drawHUD(canvas: Canvas) {
        // 상단 거리 표시
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

        // 50m 돌파 시 가속 안내 배너
        if (speedUpBannerTime > 0f && state == GameState.PLAYING) {
            paint.textSize = 44f
            paint.color = Color.parseColor("#FF7043")
            canvas.drawText("SPEED UP!", width / 2f, 245f, paint)
        }

        // 시작 대기 또는 게임 오버 오버레이
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