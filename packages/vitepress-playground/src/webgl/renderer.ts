import type {
  PlaygroundCameraState,
  PlaygroundFrameStats,
  PlaygroundOutputEvent,
  PlaygroundSceneBatch,
  PlaygroundSceneSection,
  PlaygroundViewportOptions,
  PlaygroundViewportScene,
} from '../types'

interface RendererCallbacks {
  cameraChange: (camera: PlaygroundCameraState) => void
  frameStats: (stats: PlaygroundFrameStats) => void
  contextLost: (lost: boolean) => void
}

interface MovementState {
  forward: number
  right: number
  up: number
}

interface CpuSection {
  vertices: Float32Array
  previous: Float32Array
  indices: Uint32Array
  batches: PlaygroundSceneBatch[]
  stride: number
}

interface GpuSection {
  vao: WebGLVertexArrayObject
  current: WebGLBuffer
  previous: WebGLBuffer
  indices: WebGLBuffer
  indexCount: number
  batches: PlaygroundSceneBatch[]
}

interface ViewportParticle {
  position: [number, number, number]
  velocity: [number, number, number]
  bornAt: number
  lifetime: number
  gravity: number
  color: [number, number, number, number]
  size: number
}

const DEFAULTS: Required<Pick<PlaygroundViewportOptions,
  'targetFps' | 'fieldOfView' | 'moveSpeed' | 'minimumPixelRatio' | 'maximumPixelRatio'>> = {
  targetFps: 60,
  fieldOfView: 70,
  moveSpeed: 6,
  minimumPixelRatio: 0.5,
  maximumPixelRatio: 2,
}

export class WebglViewportRenderer {
  private gl?: WebGL2RenderingContext
  private program?: WebGLProgram
  private particleProgram?: WebGLProgram
  private particleVao?: WebGLVertexArrayObject
  private particleBuffer?: WebGLBuffer
  private texture?: WebGLTexture
  private blocks?: GpuSection
  private entities?: GpuSection
  private blockCpu?: CpuSection
  private entityCpu?: CpuSection
  private atlas?: { width: number; height: number; rgba: Uint8Array }
  private animationFrame?: number
  private resizeObserver?: ResizeObserver
  private disposed = false
  private lost = false
  private revision = 0
  private tickRate = 20
  private sceneReceivedAt = 0
  private lastFrameAt = 0
  private lastDrawAt = 0
  private statStartedAt = 0
  private statFrames = 0
  private statFrameTime = 0
  private pixelRatio = 1
  private automatic = true
  private speed: number
  private movement: MovementState = { forward: 0, right: 0, up: 0 }
  private particles: ViewportParticle[] = []
  private particleSequence = 0
  private camera: PlaygroundCameraState = {
    position: [6, 5, 6],
    yaw: -135,
    pitch: 25,
    speed: DEFAULTS.moveSpeed,
    automatic: true,
  }
  private readonly options: typeof DEFAULTS

  constructor(
    private readonly canvas: HTMLCanvasElement,
    options: PlaygroundViewportOptions,
    private readonly callbacks: RendererCallbacks,
  ) {
    this.options = {
      targetFps: clamp(options.targetFps ?? DEFAULTS.targetFps, 1, 144),
      fieldOfView: clamp(options.fieldOfView ?? DEFAULTS.fieldOfView, 30, 110),
      moveSpeed: clamp(options.moveSpeed ?? DEFAULTS.moveSpeed, 0.1, 100),
      minimumPixelRatio: clamp(options.minimumPixelRatio ?? DEFAULTS.minimumPixelRatio, 0.25, 4),
      maximumPixelRatio: clamp(options.maximumPixelRatio ?? DEFAULTS.maximumPixelRatio, 0.25, 4),
    }
    if (this.options.minimumPixelRatio > this.options.maximumPixelRatio) {
      this.options.minimumPixelRatio = this.options.maximumPixelRatio
    }
    this.speed = this.options.moveSpeed
    this.pixelRatio = clamp(globalThis.devicePixelRatio || 1, this.options.minimumPixelRatio, this.options.maximumPixelRatio)
    this.canvas.addEventListener('webglcontextlost', this.onContextLost)
    this.canvas.addEventListener('webglcontextrestored', this.onContextRestored)
    this.initialize()
    this.resizeObserver = new ResizeObserver(() => this.resize())
    this.resizeObserver.observe(canvas)
    this.start()
  }

  updateScene(scene: PlaygroundViewportScene): void {
    this.revision = scene.revision
    this.tickRate = scene.tickRate
    this.sceneReceivedAt = performance.now()
    if (scene.atlas) {
      this.atlas = { width: scene.atlas.width, height: scene.atlas.height, rgba: new Uint8Array(scene.atlas.rgba) }
    }
    if (scene.blocks) this.blockCpu = nextCpuSection(this.blockCpu, scene.blocks, false, scene.vertexStride)
    if (scene.entities) this.entityCpu = nextCpuSection(this.entityCpu, scene.entities, true, scene.vertexStride)
    if (this.automatic) this.applyAutomaticCamera(scene)
    if (this.gl && !this.lost) this.uploadAll()
  }

  handleOutput(output: PlaygroundOutputEvent): void {
    if (output.channel !== 'visual' || output.command !== 'particle' || !output.payload || typeof output.payload !== 'object' || Array.isArray(output.payload)) return
    const payload = output.payload as Record<string, unknown>
    const name = String(payload.particle ?? output.text)
    const count = clamp(Math.trunc(Number(payload.renderCount ?? 1)), 1, 4_096)
    const origin: [number, number, number] = [Number(payload.x ?? 0), Number(payload.y ?? 0), Number(payload.z ?? 0)]
    const delta: [number, number, number] = [Number(payload.deltaX ?? 0), Number(payload.deltaY ?? 0), Number(payload.deltaZ ?? 0)]
    const speed = Math.max(0, Number(payload.speed ?? 0))
    const style = particleStyle(name)
    const random = seededRandom(hashString(`${output.tick}:${name}:${origin.join(':')}:${this.particleSequence++}`))
    const bornAt = performance.now()
    for (let index = 0; index < count; index += 1) {
      this.particles.push({
        position: [
          origin[0] + centered(random) * delta[0],
          origin[1] + centered(random) * delta[1],
          origin[2] + centered(random) * delta[2],
        ],
        velocity: [centered(random) * speed, centered(random) * speed + style.rise, centered(random) * speed],
        bornAt,
        lifetime: style.lifetime,
        gravity: style.gravity,
        color: style.color,
        size: style.size,
      })
    }
    if (this.particles.length > 16_384) this.particles.splice(0, this.particles.length - 16_384)
  }

  setMovement(movement: Partial<MovementState>): void {
    Object.assign(this.movement, movement)
  }

  look(deltaYaw: number, deltaPitch: number): void {
    this.automatic = false
    this.camera.yaw = normalizeDegrees(this.camera.yaw + deltaYaw)
    this.camera.pitch = clamp(this.camera.pitch + deltaPitch, -89, 89)
    this.emitCamera()
  }

  adjustSpeed(delta: number): void {
    this.speed = clamp(this.speed * Math.exp(delta), 0.1, 100)
    this.emitCamera()
  }

  resetView(scene?: PlaygroundViewportScene): void {
    this.automatic = true
    if (scene) this.applyAutomaticCamera(scene)
    else this.emitCamera()
  }

  dispose(): void {
    if (this.disposed) return
    this.disposed = true
    if (this.animationFrame !== undefined) cancelAnimationFrame(this.animationFrame)
    this.resizeObserver?.disconnect()
    this.canvas.removeEventListener('webglcontextlost', this.onContextLost)
    this.canvas.removeEventListener('webglcontextrestored', this.onContextRestored)
    this.deleteResources()
  }

  private initialize(): void {
    const gl = this.canvas.getContext('webgl2', {
      alpha: false,
      antialias: true,
      depth: true,
      powerPreference: 'high-performance',
    })
    if (!gl) throw new Error('WebGL2 is unavailable; static PNG rendering remains available.')
    this.gl = gl
    this.program = createProgram(gl, VERTEX_SHADER, FRAGMENT_SHADER)
    this.particleProgram = createProgram(gl, PARTICLE_VERTEX_SHADER, PARTICLE_FRAGMENT_SHADER)
    this.particleVao = required(gl.createVertexArray(), 'particle vertex array')
    this.particleBuffer = required(gl.createBuffer(), 'particle vertex buffer')
    gl.bindVertexArray(this.particleVao)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.particleBuffer)
    gl.enableVertexAttribArray(0)
    gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 8 * 4, 0)
    gl.enableVertexAttribArray(1)
    gl.vertexAttribPointer(1, 4, gl.FLOAT, false, 8 * 4, 3 * 4)
    gl.enableVertexAttribArray(2)
    gl.vertexAttribPointer(2, 1, gl.FLOAT, false, 8 * 4, 7 * 4)
    gl.bindVertexArray(null)
    this.texture = gl.createTexture() ?? undefined
    gl.enable(gl.DEPTH_TEST)
    gl.enable(gl.CULL_FACE)
    gl.cullFace(gl.BACK)
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA)
    this.resize()
    this.uploadAll()
  }

  private uploadAll(): void {
    const gl = this.gl
    if (!gl || !this.program) return
    if (this.atlas && this.texture) {
      gl.bindTexture(gl.TEXTURE_2D, this.texture)
      gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1)
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, this.atlas.width, this.atlas.height, 0, gl.RGBA, gl.UNSIGNED_BYTE, this.atlas.rgba)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
    }
    if (this.blockCpu) {
      deleteSection(gl, this.blocks)
      this.blocks = uploadSection(gl, this.blockCpu)
    }
    if (this.entityCpu) {
      deleteSection(gl, this.entities)
      this.entities = uploadSection(gl, this.entityCpu)
    }
  }

  private start(): void {
    const draw = (now: number) => {
      if (this.disposed) return
      this.animationFrame = requestAnimationFrame(draw)
      const minimumInterval = 1_000 / this.options.targetFps
      if (now - this.lastDrawAt + 0.1 < minimumInterval) return
      const deltaSeconds = this.lastFrameAt === 0 ? 0 : Math.min(0.1, (now - this.lastFrameAt) / 1_000)
      this.lastFrameAt = now
      this.lastDrawAt = now
      this.updateCamera(deltaSeconds)
      if (!this.lost) this.draw(now)
      this.recordStats(now, deltaSeconds * 1_000)
    }
    this.animationFrame = requestAnimationFrame(draw)
  }

  private draw(now: number): void {
    const gl = this.gl
    const program = this.program
    if (!gl || !program) return
    this.resize()
    gl.viewport(0, 0, this.canvas.width, this.canvas.height)
    gl.clearColor(0.055, 0.085, 0.075, 1)
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT)
    gl.useProgram(program)
    const aspect = this.canvas.width / Math.max(1, this.canvas.height)
    const projection = perspective(this.options.fieldOfView * Math.PI / 180, aspect, 0.05, 1_024)
    const direction = cameraDirection(this.camera.yaw, this.camera.pitch)
    const target = add3(this.camera.position, direction)
    const view = lookAt(this.camera.position, target, [0, 1, 0])
    const facing = normalize3([-direction[0], -direction[1], -direction[2]])
    const cameraRight = normalize3(cross3([0, 1, 0], facing))
    const cameraUp = normalize3(cross3(facing, cameraRight))
    gl.uniformMatrix4fv(gl.getUniformLocation(program, 'u_projection'), false, projection)
    gl.uniformMatrix4fv(gl.getUniformLocation(program, 'u_view'), false, view)
    gl.uniform3fv(gl.getUniformLocation(program, 'u_billboardFacing'), facing)
    gl.uniform3fv(gl.getUniformLocation(program, 'u_billboardRight'), cameraRight)
    gl.uniform3fv(gl.getUniformLocation(program, 'u_billboardUp'), cameraUp)
    gl.uniform1i(gl.getUniformLocation(program, 'u_texture'), 0)
    gl.activeTexture(gl.TEXTURE0)
    gl.bindTexture(gl.TEXTURE_2D, this.texture ?? null)
    this.drawSection(this.blocks, 1)
    const interpolation = clamp((now - this.sceneReceivedAt) / (1_000 / this.tickRate), 0, 1)
    this.drawSection(this.entities, interpolation)
    this.drawParticles(projection, view, now)
  }

  private drawParticles(projection: Float32Array, view: Float32Array, now: number): void {
    const gl = this.gl
    const program = this.particleProgram
    if (!gl || !program || !this.particleVao || !this.particleBuffer || this.particles.length === 0) return
    this.particles = this.particles.filter((particle) => now - particle.bornAt < particle.lifetime)
    if (this.particles.length === 0) return
    const data = new Float32Array(this.particles.length * 8)
    let offset = 0
    for (const particle of this.particles) {
      const age = Math.max(0, (now - particle.bornAt) / 1_000)
      const alpha = 1 - (now - particle.bornAt) / particle.lifetime
      data[offset++] = particle.position[0] + particle.velocity[0] * age
      data[offset++] = particle.position[1] + particle.velocity[1] * age - particle.gravity * age * age * 0.5
      data[offset++] = particle.position[2] + particle.velocity[2] * age
      data[offset++] = particle.color[0]
      data[offset++] = particle.color[1]
      data[offset++] = particle.color[2]
      data[offset++] = particle.color[3] * alpha
      data[offset++] = particle.size * this.pixelRatio
    }
    gl.useProgram(program)
    gl.uniformMatrix4fv(gl.getUniformLocation(program, 'u_projection'), false, projection)
    gl.uniformMatrix4fv(gl.getUniformLocation(program, 'u_view'), false, view)
    gl.bindVertexArray(this.particleVao)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.particleBuffer)
    gl.bufferData(gl.ARRAY_BUFFER, data, gl.DYNAMIC_DRAW)
    gl.enable(gl.DEPTH_TEST)
    gl.enable(gl.BLEND)
    gl.depthMask(false)
    gl.drawArrays(gl.POINTS, 0, this.particles.length)
    gl.depthMask(true)
    gl.disable(gl.BLEND)
    gl.bindVertexArray(null)
    gl.useProgram(null)
  }

  private drawSection(section: GpuSection | undefined, interpolation: number): void {
    const gl = this.gl
    const program = this.program
    if (!gl || !program || !section || section.indexCount === 0) return
    gl.bindVertexArray(section.vao)
    gl.uniform1f(gl.getUniformLocation(program, 'u_interpolation'), interpolation)
    for (const batch of section.batches) {
      const translucent = batch.pass === 'translucent'
      gl.uniform1f(gl.getUniformLocation(program, 'u_alphaCutoff'), batch.pass === 'cutout' ? 0.1 : 0)
      if (translucent) {
        gl.enable(gl.BLEND)
        gl.depthMask(false)
      } else {
        gl.disable(gl.BLEND)
        gl.depthMask(true)
      }
      if (batch.seeThrough) gl.disable(gl.DEPTH_TEST)
      else gl.enable(gl.DEPTH_TEST)
      gl.drawElements(gl.TRIANGLES, batch.indexCount, gl.UNSIGNED_INT, batch.indexOffset * 4)
    }
    gl.depthMask(true)
    gl.enable(gl.DEPTH_TEST)
    gl.disable(gl.BLEND)
    gl.bindVertexArray(null)
  }

  private updateCamera(deltaSeconds: number): void {
    if (deltaSeconds <= 0 || (this.movement.forward === 0 && this.movement.right === 0 && this.movement.up === 0)) return
    this.automatic = false
    const yaw = this.camera.yaw * Math.PI / 180
    const forward: [number, number, number] = [-Math.sin(yaw), 0, Math.cos(yaw)]
    const right: [number, number, number] = [Math.cos(yaw), 0, Math.sin(yaw)]
    const scale = this.speed * deltaSeconds
    this.camera.position = [
      this.camera.position[0] + (forward[0] * this.movement.forward + right[0] * this.movement.right) * scale,
      this.camera.position[1] + this.movement.up * scale,
      this.camera.position[2] + (forward[2] * this.movement.forward + right[2] * this.movement.right) * scale,
    ]
    this.emitCamera()
  }

  private applyAutomaticCamera(scene: PlaygroundViewportScene): void {
    this.camera = {
      position: [...scene.camera.position],
      yaw: scene.camera.yaw,
      pitch: scene.camera.pitch,
      speed: this.speed,
      automatic: true,
    }
    this.emitCamera()
  }

  private emitCamera(): void {
    this.camera.speed = this.speed
    this.camera.automatic = this.automatic
    this.callbacks.cameraChange({ ...this.camera, position: [...this.camera.position] })
  }

  private resize(): void {
    const width = Math.max(1, Math.round(this.canvas.clientWidth * this.pixelRatio))
    const height = Math.max(1, Math.round(this.canvas.clientHeight * this.pixelRatio))
    if (this.canvas.width !== width) this.canvas.width = width
    if (this.canvas.height !== height) this.canvas.height = height
  }

  private recordStats(now: number, frameTime: number): void {
    if (this.statStartedAt === 0) this.statStartedAt = now
    this.statFrames += 1
    this.statFrameTime += frameTime
    const elapsed = now - this.statStartedAt
    if (elapsed < 500) return
    const fps = this.statFrames * 1_000 / elapsed
    const average = this.statFrameTime / Math.max(1, this.statFrames)
    if (average > 1_000 / 28 && this.pixelRatio > this.options.minimumPixelRatio) {
      this.pixelRatio = Math.max(this.options.minimumPixelRatio, this.pixelRatio - 0.1)
    } else if (average < 1_000 / Math.max(30, this.options.targetFps) * 0.72 && this.pixelRatio < this.options.maximumPixelRatio) {
      this.pixelRatio = Math.min(this.options.maximumPixelRatio, this.pixelRatio + 0.05)
    }
    const triangles = ((this.blocks?.indexCount ?? 0) + (this.entities?.indexCount ?? 0)) / 3
    this.callbacks.frameStats({ fps, frameTimeMs: average, pixelRatio: this.pixelRatio, triangles, revision: this.revision })
    this.statStartedAt = now
    this.statFrames = 0
    this.statFrameTime = 0
  }

  private readonly onContextLost = (event: Event) => {
    event.preventDefault()
    this.lost = true
    this.callbacks.contextLost(true)
  }

  private readonly onContextRestored = () => {
    this.lost = false
    this.blocks = undefined
    this.entities = undefined
      this.texture = undefined
      this.program = undefined
      this.particleProgram = undefined
      this.particleVao = undefined
      this.particleBuffer = undefined
    try {
      this.initialize()
      this.callbacks.contextLost(false)
    } catch {
      this.lost = true
      this.callbacks.contextLost(true)
    }
  }

  private deleteResources(): void {
    const gl = this.gl
    if (!gl) return
    deleteSection(gl, this.blocks)
    deleteSection(gl, this.entities)
    if (this.texture) gl.deleteTexture(this.texture)
    if (this.program) gl.deleteProgram(this.program)
    if (this.particleBuffer) gl.deleteBuffer(this.particleBuffer)
    if (this.particleVao) gl.deleteVertexArray(this.particleVao)
    if (this.particleProgram) gl.deleteProgram(this.particleProgram)
  }
}

function nextCpuSection(
  previous: CpuSection | undefined,
  source: PlaygroundSceneSection,
  interpolate: boolean,
  stride: number,
): CpuSection {
  const vertices = new Float32Array(source.vertices)
  const prior = interpolate && previous?.vertices.length === vertices.length ? previous.vertices : vertices
  return {
    vertices,
    previous: prior,
    indices: new Uint32Array(source.indices),
    batches: source.batches,
    stride,
  }
}

function uploadSection(gl: WebGL2RenderingContext, source: CpuSection): GpuSection {
  const vao = required(gl.createVertexArray(), 'vertex array')
  const current = required(gl.createBuffer(), 'vertex buffer')
  const previous = required(gl.createBuffer(), 'previous vertex buffer')
  const indices = required(gl.createBuffer(), 'index buffer')
  gl.bindVertexArray(vao)
  bindVertexBuffer(gl, current, source.vertices, false, source.stride)
  bindVertexBuffer(gl, previous, source.previous, true, source.stride)
  gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, indices)
  gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, source.indices, gl.STATIC_DRAW)
  gl.bindVertexArray(null)
  return { vao, current, previous, indices, indexCount: source.indices.length, batches: source.batches }
}

function bindVertexBuffer(
  gl: WebGL2RenderingContext,
  buffer: WebGLBuffer,
  data: Float32Array,
  previous: boolean,
  floatStride: number,
): void {
  gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
  gl.bufferData(gl.ARRAY_BUFFER, data, gl.DYNAMIC_DRAW)
  const stride = floatStride * 4
  if (previous) {
    gl.enableVertexAttribArray(1)
    gl.vertexAttribPointer(1, 3, gl.FLOAT, false, stride, 0)
    if (floatStride >= 19) {
      gl.enableVertexAttribArray(8)
      gl.vertexAttribPointer(8, 3, gl.FLOAT, false, stride, 12 * 4)
      gl.enableVertexAttribArray(9)
      gl.vertexAttribPointer(9, 3, gl.FLOAT, false, stride, 15 * 4)
    } else {
      gl.disableVertexAttribArray(8)
      gl.disableVertexAttribArray(9)
      gl.vertexAttrib3f(8, 0, 0, 0)
      gl.vertexAttrib3f(9, 0, 0, 0)
    }
    return
  }
  gl.enableVertexAttribArray(0)
  gl.vertexAttribPointer(0, 3, gl.FLOAT, false, stride, 0)
  gl.enableVertexAttribArray(2)
  gl.vertexAttribPointer(2, 2, gl.FLOAT, false, stride, 3 * 4)
  gl.enableVertexAttribArray(3)
  gl.vertexAttribPointer(3, 3, gl.FLOAT, false, stride, 5 * 4)
  gl.enableVertexAttribArray(4)
  gl.vertexAttribPointer(4, 4, gl.FLOAT, false, stride, 8 * 4)
  if (floatStride >= 19) {
    gl.enableVertexAttribArray(5)
    gl.vertexAttribPointer(5, 3, gl.FLOAT, false, stride, 12 * 4)
    gl.enableVertexAttribArray(6)
    gl.vertexAttribPointer(6, 3, gl.FLOAT, false, stride, 15 * 4)
    gl.enableVertexAttribArray(7)
    gl.vertexAttribPointer(7, 1, gl.FLOAT, false, stride, 18 * 4)
  } else {
    gl.disableVertexAttribArray(5)
    gl.disableVertexAttribArray(6)
    gl.disableVertexAttribArray(7)
    gl.vertexAttrib3f(5, 0, 0, 0)
    gl.vertexAttrib3f(6, 0, 0, 0)
    gl.vertexAttrib1f(7, 0)
  }
}

function deleteSection(gl: WebGL2RenderingContext, section?: GpuSection): void {
  if (!section) return
  gl.deleteVertexArray(section.vao)
  gl.deleteBuffer(section.current)
  gl.deleteBuffer(section.previous)
  gl.deleteBuffer(section.indices)
}

function createProgram(gl: WebGL2RenderingContext, vertexSource: string, fragmentSource: string): WebGLProgram {
  const vertex = compileShader(gl, gl.VERTEX_SHADER, vertexSource)
  const fragment = compileShader(gl, gl.FRAGMENT_SHADER, fragmentSource)
  const program = required(gl.createProgram(), 'shader program')
  gl.attachShader(program, vertex)
  gl.attachShader(program, fragment)
  gl.linkProgram(program)
  gl.deleteShader(vertex)
  gl.deleteShader(fragment)
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    const message = gl.getProgramInfoLog(program) || 'WebGL program link failed'
    gl.deleteProgram(program)
    throw new Error(message)
  }
  return program
}

function compileShader(gl: WebGL2RenderingContext, type: number, source: string): WebGLShader {
  const shader = required(gl.createShader(type), 'shader')
  gl.shaderSource(shader, source)
  gl.compileShader(shader)
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    const message = gl.getShaderInfoLog(shader) || 'WebGL shader compilation failed'
    gl.deleteShader(shader)
    throw new Error(message)
  }
  return shader
}

function required<T>(value: T | null, name: string): T {
  if (!value) throw new Error(`Unable to create WebGL ${name}`)
  return value
}

function perspective(fieldOfView: number, aspect: number, near: number, far: number): Float32Array {
  const f = 1 / Math.tan(fieldOfView / 2)
  const range = 1 / (near - far)
  return new Float32Array([
    f / aspect, 0, 0, 0,
    0, f, 0, 0,
    0, 0, (far + near) * range, -1,
    0, 0, 2 * far * near * range, 0,
  ])
}

function lookAt(eye: number[], target: number[], up: number[]): Float32Array {
  const z = normalize3(subtract3(eye, target))
  const x = normalize3(cross3(up, z))
  const y = cross3(z, x)
  return new Float32Array([
    x[0], y[0], z[0], 0,
    x[1], y[1], z[1], 0,
    x[2], y[2], z[2], 0,
    -dot3(x, eye), -dot3(y, eye), -dot3(z, eye), 1,
  ])
}

function cameraDirection(yawDegrees: number, pitchDegrees: number): [number, number, number] {
  const yaw = yawDegrees * Math.PI / 180
  const pitch = pitchDegrees * Math.PI / 180
  return [-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)]
}

function add3(a: number[], b: number[]): [number, number, number] {
  return [a[0] + b[0], a[1] + b[1], a[2] + b[2]]
}

function subtract3(a: number[], b: number[]): [number, number, number] {
  return [a[0] - b[0], a[1] - b[1], a[2] - b[2]]
}

function cross3(a: number[], b: number[]): [number, number, number] {
  return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
}

function dot3(a: number[], b: number[]): number {
  return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}

function normalize3(value: number[]): [number, number, number] {
  const length = Math.hypot(value[0], value[1], value[2]) || 1
  return [value[0] / length, value[1] / length, value[2] / length]
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(maximum, Math.max(minimum, value))
}

function normalizeDegrees(value: number): number {
  return ((value + 180) % 360 + 360) % 360 - 180
}

function particleStyle(name: string): {
  color: [number, number, number, number]
  size: number
  lifetime: number
  gravity: number
  rise: number
} {
  if (name.includes('soul_fire')) return { color: [0.44, 0.91, 1, 1], size: 10, lifetime: 1_100, gravity: 0, rise: 0.18 }
  if (name.includes('flame')) return { color: [1, 0.63, 0.15, 1], size: 11, lifetime: 1_000, gravity: 0, rise: 0.22 }
  if (name.includes('large_smoke')) return { color: [0.44, 0.44, 0.44, 0.8], size: 15, lifetime: 1_700, gravity: 0, rise: 0.12 }
  if (name.includes('smoke')) return { color: [0.54, 0.54, 0.54, 0.8], size: 10, lifetime: 1_400, gravity: 0, rise: 0.1 }
  if (name.includes('portal')) return { color: [0.63, 0.27, 0.85, 1], size: 10, lifetime: 1_500, gravity: 0, rise: 0 }
  if (name.includes('happy_villager')) return { color: [0.35, 0.86, 0.38, 1], size: 10, lifetime: 1_200, gravity: 0, rise: 0.1 }
  if (name.includes('heart')) return { color: [1, 0.25, 0.38, 1], size: 14, lifetime: 1_400, gravity: 0, rise: 0.12 }
  if (name.includes('dust')) return { color: [0.84, 0.25, 0.21, 1], size: 9, lifetime: 1_250, gravity: 0.04, rise: 0 }
  if (name.includes('block')) return { color: [0.6, 0.52, 0.39, 1], size: 9, lifetime: 1_000, gravity: 0.45, rise: 0 }
  return { color: [0.89, 0.93, 0.96, 1], size: 9, lifetime: 1_200, gravity: 0.08, rise: 0 }
}

function centered(random: () => number): number {
  return random() * 2 - 1
}

function hashString(value: string): number {
  let hash = 0x811c9dc5
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index)
    hash = Math.imul(hash, 0x01000193)
  }
  return hash >>> 0
}

function seededRandom(initial: number): () => number {
  let state = initial || 0x6d2b79f5
  return () => {
    state += 0x6d2b79f5
    let value = state
    value = Math.imul(value ^ value >>> 15, value | 1)
    value ^= value + Math.imul(value ^ value >>> 7, value | 61)
    return ((value ^ value >>> 14) >>> 0) / 4_294_967_296
  }
}

const PARTICLE_VERTEX_SHADER = `#version 300 es
layout(location = 0) in vec3 a_position;
layout(location = 1) in vec4 a_color;
layout(location = 2) in float a_size;
uniform mat4 u_projection;
uniform mat4 u_view;
out vec4 v_color;
void main() {
  gl_Position = u_projection * u_view * vec4(a_position, 1.0);
  gl_PointSize = a_size;
  v_color = a_color;
}`

const PARTICLE_FRAGMENT_SHADER = `#version 300 es
precision highp float;
in vec4 v_color;
out vec4 outColor;
void main() {
  vec2 centered = gl_PointCoord * 2.0 - 1.0;
  float radius = dot(centered, centered);
  if (radius > 1.0) discard;
  float edge = 1.0 - smoothstep(0.55, 1.0, radius);
  outColor = vec4(v_color.rgb, v_color.a * edge);
}`

const VERTEX_SHADER = `#version 300 es
layout(location = 0) in vec3 a_position;
layout(location = 1) in vec3 a_previousPosition;
layout(location = 2) in vec2 a_uv;
layout(location = 3) in vec3 a_normal;
layout(location = 4) in vec4 a_tint;
layout(location = 5) in vec3 a_billboardLocal;
layout(location = 6) in vec3 a_billboardPivot;
layout(location = 7) in float a_billboardMode;
layout(location = 8) in vec3 a_previousBillboardLocal;
layout(location = 9) in vec3 a_previousBillboardPivot;
uniform mat4 u_projection;
uniform mat4 u_view;
uniform float u_interpolation;
uniform vec3 u_billboardFacing;
uniform vec3 u_billboardRight;
uniform vec3 u_billboardUp;
out vec2 v_uv;
out vec3 v_normal;
out vec4 v_tint;
void main() {
  vec3 position = mix(a_previousPosition, a_position, u_interpolation);
  if (a_billboardMode > 0.5) {
    vec3 local = mix(a_previousBillboardLocal, a_billboardLocal, u_interpolation);
    vec3 pivot = mix(a_previousBillboardPivot, a_billboardPivot, u_interpolation);
    vec3 up = a_billboardMode < 1.5 ? vec3(0.0, 1.0, 0.0) : u_billboardUp;
    position = pivot
      + u_billboardRight * local.x
      + up * local.y
      + u_billboardFacing * local.z;
  }
  gl_Position = u_projection * u_view * vec4(position, 1.0);
  v_uv = a_uv;
  v_normal = a_normal;
  v_tint = a_tint;
}`

const FRAGMENT_SHADER = `#version 300 es
precision highp float;
uniform sampler2D u_texture;
uniform float u_alphaCutoff;
in vec2 v_uv;
in vec3 v_normal;
in vec4 v_tint;
out vec4 outColor;
void main() {
  vec4 sampled = texture(u_texture, v_uv);
  if (sampled.a <= u_alphaCutoff) discard;
  float directional = 0.48 + 0.52 * max(dot(normalize(v_normal), normalize(vec3(0.35, 0.85, 0.4))), 0.0);
  float light = mix(directional, 1.0, v_tint.a);
  outColor = vec4(sampled.rgb * v_tint.rgb * light, sampled.a);
}`
