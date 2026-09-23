export type FireworkParticle = {
  id: number
  x: number
  y: number
  color: string
  delay: number
  size: number
}

export type ParticleStyle = Record<
  '--tx' | '--ty' | '--particle-color' | '--particle-delay' | '--particle-size',
  string
>
