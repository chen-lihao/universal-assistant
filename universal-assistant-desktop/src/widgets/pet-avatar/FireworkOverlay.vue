<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'

type Launch = { startX: number; startY: number; burstX: number; burstY: number }
type Spark = { angle: number; speed: number; color: string; size: number; delay: number }

const canvasRef = ref<HTMLCanvasElement | null>(null)
const colors = ['#dfb666', '#e899c7', '#b597ec', '#d7c2ff', '#ffffff']
let frame = 0
let unsubscribe: (() => void) | undefined

function play(launch: Launch) {
  const canvas = canvasRef.value
  const context = canvas?.getContext('2d')
  if (!canvas || !context) return

  cancelAnimationFrame(frame)
  const ratio = window.devicePixelRatio || 1
  canvas.width = Math.round(440 * ratio)
  canvas.height = Math.round(370 * ratio)
  context.setTransform(ratio, 0, 0, ratio, 0, 0)
  const sparks: Spark[] = Array.from({ length: 72 }, (_, index) => ({
    angle: (index / 72) * Math.PI * 2 + Math.random() * 0.08,
    speed: 65 + Math.random() * 45,
    color: colors[index % colors.length],
    size: 1.5 + Math.random() * 2.6,
    delay: Math.random() * 0.12,
  }))
  const began = performance.now()

  function draw(now: number) {
    if (!context) return
    const t = (now - began) / 1000
    context.clearRect(0, 0, 440, 370)

    if (t < 0.68) {
      const progress = Math.min(1, t / 0.68)
      const eased = 1 - (1 - progress) ** 2
      const x = launch.startX + (launch.burstX - launch.startX) * eased
      const y = launch.startY + (launch.burstY - launch.startY) * eased
      for (let tail = 0; tail < 13; tail += 1) {
        const lag = Math.max(0, progress - tail * 0.018)
        const tailX = launch.startX + (launch.burstX - launch.startX) * (1 - (1 - lag) ** 2)
        const tailY = launch.startY + (launch.burstY - launch.startY) * (1 - (1 - lag) ** 2)
        context.globalAlpha = (1 - tail / 13) * 0.75
        context.fillStyle = tail % 3 === 0 ? '#e899c7' : '#dfb666'
        context.beginPath()
        context.arc(tailX, tailY, Math.max(1, 3 - tail * 0.16), 0, Math.PI * 2)
        context.fill()
      }
      context.globalAlpha = 1
      context.shadowBlur = 20
      context.shadowColor = '#dfb666'
      context.fillStyle = '#ffffff'
      context.beginPath()
      context.arc(x, y, 5, 0, Math.PI * 2)
      context.fill()
    } else {
      const elapsed = t - 0.68
      sparks.forEach((spark) => {
        const age = elapsed - spark.delay
        if (age < 0 || age > 1.55) return
        const distance = spark.speed * age * (1 - 0.16 * age)
        const x = launch.burstX + Math.cos(spark.angle) * distance
        const y = launch.burstY + Math.sin(spark.angle) * distance + 18 * age * age
        context.globalAlpha = Math.max(0, 1 - age / 1.55)
        context.shadowBlur = 12
        context.shadowColor = spark.color
        context.fillStyle = spark.color
        context.beginPath()
        context.arc(x, y, spark.size * (1 - age * 0.25), 0, Math.PI * 2)
        context.fill()
      })
      if (elapsed < 0.4) {
        context.globalAlpha = 1 - elapsed / 0.4
        context.strokeStyle = '#ffffff'
        context.lineWidth = 2
        context.beginPath()
        context.arc(launch.burstX, launch.burstY, elapsed * 55, 0, Math.PI * 2)
        context.stroke()
      }
    }

    context.globalAlpha = 1
    context.shadowBlur = 0
    if (t < 2.25) frame = requestAnimationFrame(draw)
  }

  frame = requestAnimationFrame(draw)
}

onMounted(() => {
  unsubscribe = window.assistant?.onFirework(play)
})
onUnmounted(() => {
  unsubscribe?.()
  cancelAnimationFrame(frame)
})
</script>

<template>
  <canvas ref="canvasRef" class="firework-canvas" aria-hidden="true" />
</template>

<style scoped>
.firework-canvas {
  display: block;
  width: 440px;
  height: 370px;
  pointer-events: none;
}
</style>
