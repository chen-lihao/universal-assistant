import { screen } from 'electron'
import type { Rectangle } from 'electron'
import {
  FOLLOW_STEP_MS,
  FOLLOW_STIFFNESS,
  ROAM_MAX_DELAY_MS,
  ROAM_MAX_DURATION_MS,
  ROAM_MIN_DELAY_MS,
  ROAM_MIN_DISTANCE_Y,
  ROAM_MIN_DURATION_MS,
  ROAM_RANGE_X,
  ROAM_RANGE_Y,
  WINDOW_GAP,
} from './constants'
import type { AssistantWindowState, PetAction, PetMotionPayload, PetState } from './types'
import type { createWindowMotion, WindowPoint } from './windowMotion'
import { clamp, randomBetween } from './windowMotion'
import { horizontalDirectionFromWindow } from './windowFactory'

type WindowMotion = ReturnType<typeof createWindowMotion>

export function createPetMotionController(options: {
  state: AssistantWindowState
  windowMotion: WindowMotion
  setPetState: (state: PetState) => void
  positionChatWindow: () => void
}) {
  const { state, windowMotion } = options

  function clearPetRoamTimer() {
    if (state.petRoamTimer) {
      clearTimeout(state.petRoamTimer)
      state.petRoamTimer = null
    }
  }

  function stopPetFollow() {
    state.petFollowTarget = null
    if (state.petFollowTimer) {
      clearTimeout(state.petFollowTimer)
      state.petFollowTimer = null
    }
  }

  function cancelPetRoam() {
    clearPetRoamTimer()

    if (state.petWindow) {
      windowMotion.cancelWindowAnimation(state.petWindow)
    }
  }

  function getChatTargetForPet(): WindowPoint | null {
    if (!state.petWindow || !state.chatWindow) {
      return null
    }

    const chatBounds = state.chatWindow.getBounds()
    const petBounds = state.petWindow.getBounds()
    const workArea = screen.getDisplayMatching(chatBounds).workArea

    let x = chatBounds.x + chatBounds.width + WINDOW_GAP
    if (x + petBounds.width > workArea.x + workArea.width - WINDOW_GAP) {
      x = chatBounds.x - petBounds.width - WINDOW_GAP
    }

    const y = clamp(
      chatBounds.y + chatBounds.height - petBounds.height,
      workArea.y + WINDOW_GAP,
      workArea.y + workArea.height - petBounds.height - WINDOW_GAP,
    )

    return {
      x: clamp(x, workArea.x + WINDOW_GAP, workArea.x + workArea.width - petBounds.width - WINDOW_GAP),
      y,
    }
  }

  function tickPetFollow() {
    state.petFollowTimer = null
    if (!state.petFollowTarget || !state.petWindow || state.petWindow.isDestroyed() || !state.chatWindow?.isVisible()) {
      stopPetFollow()
      return
    }

    if (state.petPointerActive || Date.now() < state.petManualControlUntil) {
      state.petFollowTimer = setTimeout(tickPetFollow, 120)
      return
    }

    const petBounds = state.petWindow.getBounds()
    const deltaX = state.petFollowTarget.x - petBounds.x
    const deltaY = state.petFollowTarget.y - petBounds.y
    const distance = Math.hypot(deltaX, deltaY)

    if (distance < 1) {
      windowMotion.setWindowPosition(state.petWindow, state.petFollowTarget.x, state.petFollowTarget.y)
      stopPetFollow()
      return
    }

    windowMotion.setWindowPosition(
      state.petWindow,
      petBounds.x + deltaX * FOLLOW_STIFFNESS,
      petBounds.y + deltaY * FOLLOW_STIFFNESS,
    )
    state.petFollowTimer = setTimeout(tickPetFollow, FOLLOW_STEP_MS)
  }

  function startPetFollow() {
    if (!state.petFollowTimer) {
      state.petFollowTimer = setTimeout(tickPetFollow, FOLLOW_STEP_MS)
    }
  }

  function updatePetFollowTarget() {
    if (!state.chatWindow?.isVisible() || state.petPointerActive || Date.now() < state.petManualControlUntil) {
      return
    }

    const target = getChatTargetForPet()
    if (!target) {
      return
    }

    cancelPetRoam()
    state.petFollowTarget = target
    startPetFollow()
  }

  function canPetRoam() {
    return Boolean(
      state.petWindow &&
      !state.petWindow.isDestroyed() &&
      !state.chatWindow?.isVisible() &&
      !state.petPointerActive &&
      Date.now() >= state.petManualControlUntil,
    )
  }

  function schedulePetRoam(delay?: number) {
    clearPetRoamTimer()
    if (!state.petWindow || state.petWindow.isDestroyed() || state.chatWindow?.isVisible() || state.petPointerActive) {
      return
    }

    const cooldownDelay = Math.max(0, state.petManualControlUntil - Date.now())
    const roamDelay = Math.max(0, delay ?? randomBetween(ROAM_MIN_DELAY_MS, ROAM_MAX_DELAY_MS))
    state.petRoamTimer = setTimeout(startPetRoam, cooldownDelay + roamDelay)
  }

  function getPetMotionTarget(rangeX: number, rangeY: number): WindowPoint | null {
    if (!state.petWindow) {
      return null
    }

    const petBounds = state.petWindow.getBounds()
    const workArea = screen.getDisplayMatching(petBounds).workArea
    return {
      x: Math.round(
        clamp(
          petBounds.x + randomBetween(-rangeX, rangeX),
          workArea.x + WINDOW_GAP,
          workArea.x + workArea.width - petBounds.width - WINDOW_GAP,
        ),
      ),
      y: Math.round(
        clamp(
          petBounds.y + randomBetween(-rangeY, rangeY),
          workArea.y + WINDOW_GAP,
          workArea.y + workArea.height - petBounds.height - WINDOW_GAP,
        ),
      ),
    }
  }

  function getPetRoamTarget(): WindowPoint | null {
    if (!state.petWindow) {
      return null
    }

    const petBounds = state.petWindow.getBounds()
    const workArea = screen.getDisplayMatching(petBounds).workArea
    const minX = workArea.x + WINDOW_GAP
    const maxX = workArea.x + workArea.width - petBounds.width - WINDOW_GAP
    const minY = workArea.y + WINDOW_GAP
    const maxY = workArea.y + workArea.height - petBounds.height - WINDOW_GAP
    const canMoveDown = petBounds.y + ROAM_MIN_DISTANCE_Y <= maxY
    const canMoveUp = petBounds.y - ROAM_MIN_DISTANCE_Y >= minY
    let directionY = Math.random() > 0.5 ? 1 : -1

    if (!canMoveDown && canMoveUp) {
      directionY = -1
    } else if (!canMoveUp && canMoveDown) {
      directionY = 1
    }

    const distanceY = randomBetween(ROAM_MIN_DISTANCE_Y, ROAM_RANGE_Y)
    let targetY = clamp(petBounds.y + directionY * distanceY, minY, maxY)
    if (Math.abs(targetY - petBounds.y) < ROAM_MIN_DISTANCE_Y && (canMoveDown || canMoveUp)) {
      targetY = clamp(petBounds.y - directionY * distanceY, minY, maxY)
    }

    const directionX = Math.random() > 0.5 ? 1 : -1
    const distanceX = randomBetween(12, ROAM_RANGE_X)
    let targetX = clamp(petBounds.x + directionX * distanceX, minX, maxX)
    if (Math.abs(targetX - petBounds.x) < 8) {
      targetX = clamp(petBounds.x - directionX * distanceX, minX, maxX)
    }

    return {
      x: Math.round(targetX),
      y: Math.round(targetY),
    }
  }

  function performPetMotion(payload?: PetMotionPayload) {
    const force = Boolean(payload?.force)
    if (!state.petWindow || (!force && !canPetRoam())) {
      return
    }

    cancelPetRoam()
    if (force) {
      stopPetFollow()
    }
    const type = payload?.type || 'vertical-run'
    const start = state.petWindow.getBounds()
    const workArea = screen.getDisplayMatching(start).workArea
    const finish = (nextState: PetState = 'idle', delay = 900) => {
      if (!force && !canPetRoam()) {
        return
      }

      options.setPetState(nextState)
      setTimeout(() => {
        if (
          !state.petWindow ||
          state.petWindow.isDestroyed() ||
          state.chatWindow?.isVisible() ||
          state.petPointerActive
        ) {
          return
        }

        options.setPetState('idle')
        schedulePetRoam()
      }, delay)
    }

    if (type === 'jump') {
      options.setPetState('happy')
      const jumpHeight = clamp(Number(payload?.rangeY ?? 112), 60, 150)
      const peakY = clamp(
        start.y - jumpHeight,
        workArea.y + WINDOW_GAP,
        workArea.y + workArea.height - start.height - WINDOW_GAP,
      )
      windowMotion.animateWindowPath(
        state.petWindow,
        [
          { x: start.x, y: peakY, duration: 320 },
          { x: start.x, y: start.y, duration: 460 },
        ],
        () => finish('happy', 700),
      )
      return
    }

    if (type === 'dash') {
      options.setPetState('running')
      const direction = start.x + start.width / 2 < workArea.x + workArea.width / 2 ? 1 : -1
      const dashDistance = clamp(Number(payload?.rangeX ?? 140), 80, 180)
      const targetX = clamp(
        start.x + direction * dashDistance,
        workArea.x + WINDOW_GAP,
        workArea.x + workArea.width - start.width - WINDOW_GAP,
      )
      const targetY = clamp(
        start.y + randomBetween(-28, 28),
        workArea.y + WINDOW_GAP,
        workArea.y + workArea.height - start.height - WINDOW_GAP,
      )

      windowMotion.animateWindowPath(
        state.petWindow,
        [
          { x: start.x + direction * 18, y: start.y - 14, duration: 140 },
          { x: targetX, y: targetY, duration: 640 },
          { x: targetX - direction * 18, y: targetY + 8, duration: 220 },
        ],
        () => finish('happy', 700),
      )
      return
    }

    const rangeX = clamp(Number(payload?.rangeX ?? ROAM_RANGE_X), 0, 120)
    const rangeY = clamp(Number(payload?.rangeY ?? ROAM_RANGE_Y), 0, 180)
    const duration = Math.round(clamp(Number(payload?.duration ?? 1050), 360, 2200))
    const target = getPetMotionTarget(rangeX, rangeY)
    if (!target) {
      return
    }

    options.setPetState('running')
    windowMotion.animateWindowTo(state.petWindow, target.x, target.y, duration, () => finish('happy', 900))
  }

  function performPetRoamMotion() {
    if (!state.petWindow || !canPetRoam()) {
      return
    }

    cancelPetRoam()
    const start = state.petWindow.getBounds()
    const target = getPetRoamTarget()
    if (!target) {
      return
    }

    const duration = Math.round(randomBetween(ROAM_MIN_DURATION_MS, ROAM_MAX_DURATION_MS))
    const liftY = target.y < start.y ? -14 : 10
    const midPoint = {
      x: Math.round(start.x + (target.x - start.x) * 0.42),
      y: Math.round(start.y + (target.y - start.y) * 0.42 + liftY),
    }

    options.setPetState('running')
    windowMotion.animateWindowPath(
      state.petWindow,
      [
        { x: midPoint.x, y: midPoint.y, duration: Math.round(duration * 0.38) },
        { x: target.x, y: target.y, duration: Math.round(duration * 0.62) },
      ],
      () => {
        if (
          !state.petWindow ||
          state.petWindow.isDestroyed() ||
          state.chatWindow?.isVisible() ||
          state.petPointerActive
        ) {
          return
        }

        options.setPetState('happy')
        setTimeout(() => {
          if (
            !state.petWindow ||
            state.petWindow.isDestroyed() ||
            state.chatWindow?.isVisible() ||
            state.petPointerActive
          ) {
            return
          }

          options.setPetState('idle')
          schedulePetRoam()
        }, 760)
      },
    )
  }

  function startPetRoam() {
    state.petRoamTimer = null
    if (!canPetRoam() || !state.petWindow) {
      schedulePetRoam()
      return
    }

    performPetRoamMotion()
  }

  function movePetWindowBy(deltaX: unknown, deltaY: unknown) {
    if (!state.petWindow || typeof deltaX !== 'number' || typeof deltaY !== 'number') {
      return
    }

    state.petManualControlUntil = Date.now() + 900
    stopPetFollow()
    cancelPetRoam()

    const petBounds = state.petWindow.getBounds()
    const workArea = screen.getDisplayMatching(petBounds).workArea
    const x = clamp(petBounds.x + deltaX, workArea.x, workArea.x + workArea.width - petBounds.width)
    const y = clamp(petBounds.y + deltaY, workArea.y, workArea.y + workArea.height - petBounds.height)

    windowMotion.moveWindowTo(state.petWindow, x, y)

    if (state.chatWindow?.isVisible()) {
      options.positionChatWindow()
    }
  }

  function setPetPointerActive(active: unknown) {
    state.petPointerActive = Boolean(active)
    state.petWindow?.setIgnoreMouseEvents(!state.petPointerActive, { forward: true })

    if (state.petPointerActive) {
      state.petManualControlUntil = Date.now() + 900
      stopPetFollow()
      cancelPetRoam()
      return
    }

    state.petManualControlUntil = Date.now() + 900
    if (!state.chatWindow?.isVisible()) {
      schedulePetRoam()
    }
  }

  function resolveHorizontalTarget(start: Rectangle, workArea: Rectangle, distance: number) {
    const minX = workArea.x + WINDOW_GAP
    const maxX = workArea.x + workArea.width - start.width - WINDOW_GAP
    let direction = horizontalDirectionFromWindow(start, workArea)
    let targetX = clamp(start.x + direction * distance, minX, maxX)

    if (Math.abs(targetX - start.x) < Math.min(52, distance * 0.48)) {
      direction *= -1
      targetX = clamp(start.x + direction * distance, minX, maxX)
    }

    return { direction, targetX }
  }

  function performPetInteractionMotion(action: Extract<PetAction, 'jump' | 'run'>) {
    if (!state.petWindow || state.petWindow.isDestroyed()) {
      return
    }

    stopPetFollow()
    cancelPetRoam()
    state.petManualControlUntil = Date.now() + 1800

    const start = state.petWindow.getBounds()
    const workArea = screen.getDisplayMatching(start).workArea

    if (action === 'jump') {
      options.setPetState('happy')
      const { direction, targetX } = resolveHorizontalTarget(start, workArea, 78)
      const targetY = clamp(
        start.y + randomBetween(-18, 18),
        workArea.y + WINDOW_GAP,
        workArea.y + workArea.height - start.height - WINDOW_GAP,
      )
      const peakY = clamp(
        start.y - 118,
        workArea.y + WINDOW_GAP,
        workArea.y + workArea.height - start.height - WINDOW_GAP,
      )

      windowMotion.animateWindowPath(
        state.petWindow,
        [
          { x: start.x + direction * 28, y: peakY, duration: 260 },
          { x: targetX, y: targetY, duration: 430 },
          { x: targetX - direction * 6, y: targetY + 4, duration: 120 },
        ],
        () => {
          options.setPetState('happy')
          setTimeout(() => {
            if (!state.chatWindow?.isVisible()) {
              options.setPetState('idle')
              schedulePetRoam()
            }
          }, 700)
        },
      )
      return
    }

    options.setPetState('running')
    const { direction, targetX } = resolveHorizontalTarget(start, workArea, 178)
    const targetY = clamp(
      start.y + randomBetween(-34, 34),
      workArea.y + WINDOW_GAP,
      workArea.y + workArea.height - start.height - WINDOW_GAP,
    )

    windowMotion.animateWindowPath(
      state.petWindow,
      [
        { x: start.x + direction * 24, y: start.y - 12, duration: 120 },
        { x: targetX, y: targetY, duration: 560 },
        { x: targetX - direction * 12, y: targetY + 6, duration: 180 },
      ],
      () => {
        options.setPetState('happy')
        setTimeout(() => {
          if (!state.chatWindow?.isVisible()) {
            options.setPetState('idle')
            schedulePetRoam()
          }
        }, 700)
      },
    )
  }

  return {
    cancelPetRoam,
    movePetWindowBy,
    performPetInteractionMotion,
    performPetMotion,
    schedulePetRoam,
    setPetPointerActive,
    stopPetFollow,
    updatePetFollowTarget,
  }
}
