import { expect, test } from '@playwright/test'
import { strToU8, zipSync } from 'fflate'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname } from 'node:path'

test('shares a realtime WebGL viewport with playback, input, and context recovery', async ({ page }) => {
  await page.goto('/?viewport')
  const viewport = page.locator('.dps-viewport')
  const canvas = viewport.locator('canvas')
  await expect(canvas).toBeVisible()
  await expect(viewport).toHaveAttribute('data-state', 'ready')

  await page.getByRole('button', { name: 'Run all' }).click()
  await expect(page.getByText(/Executed 1 command/)).toBeVisible()
  await viewport.getByRole('button', { name: 'Play' }).click()
  await expect(viewport.getByRole('button', { name: 'Pause' })).toBeVisible()
  await page.waitForTimeout(150)
  await viewport.getByRole('button', { name: 'Pause' }).click()
  await viewport.getByRole('button', { name: 'Step' }).click()

  await canvas.focus()
  await page.keyboard.down('w')
  await page.waitForTimeout(60)
  await page.keyboard.up('w')

  const canLoseContext = await canvas.evaluate((element) => {
    const gl = (element as HTMLCanvasElement).getContext('webgl2')
    const extension = gl?.getExtension('WEBGL_lose_context')
    ;(element as HTMLCanvasElement & { __loseContext?: WEBGL_lose_context }).__loseContext = extension ?? undefined
    extension?.loseContext()
    return Boolean(extension)
  })
  if (canLoseContext) {
    await expect(viewport).toHaveAttribute('data-state', 'context-lost')
    await canvas.evaluate((element) => {
      ;(element as HTMLCanvasElement & { __loseContext?: WEBGL_lose_context }).__loseContext?.restoreContext()
    })
    await expect(viewport).toHaveAttribute('data-state', 'ready')
  }
})

test('runs a real cell and renders a screenshot', async ({ page }, testInfo) => {
  const apiRequests: string[] = []
  page.on('request', (request) => {
    if (/\/v1\/playground|\/health$/.test(request.url()) || request.resourceType() === 'websocket') apiRequests.push(request.url())
  })
  await page.goto('/')
  await expect(page.getByText('Minecraft 26.2')).toBeVisible()
  await page.getByRole('button', { name: 'Run', exact: true }).click()
  await expect(page.getByText(/Executed 1 command/)).toBeVisible()
  const render = page.locator('img.dps-render')
  await expect(render).toBeVisible()
  await expect(render).toHaveAttribute('src', /^blob:/)
  await page.locator('.dps-playground').screenshot({ path: testInfo.outputPath('playground-ready.png') })
  const restore = page.getByRole('button', { name: 'Restore example' })
  await expect(restore).toBeEnabled()
  await restore.click()
  await expect(page.locator('.dps-output')).toHaveCount(0)
  await expect(page.locator('.cm-content')).toContainText('setblock 0 0 2 minecraft:stone')
  expect(apiRequests).toEqual([])
})

test('embeds the lightweight single-cell surface without notebook controls', async ({ page }) => {
  await page.goto('/?cell=1')
  const cell = page.locator('.dps-cell-space')
  await expect(cell).toHaveAttribute('data-state', 'ready', { timeout: 15_000 })
  await expect(cell.locator('.dps-toolbar')).toHaveCount(0)
  await expect(cell.getByRole('button')).toHaveCount(7)
  await cell.getByRole('button', { name: 'Run', exact: true }).click()
  await expect(cell.getByText(/Executed 1 command/)).toBeVisible()
  await expect(cell.locator('img.dps-render')).toHaveCount(0)
  await cell.getByRole('button', { name: 'Render', exact: true }).click()
  await expect(cell.locator('img.dps-render')).toBeVisible()
  await cell.getByRole('button', { name: 'Reset example', exact: true }).click()
  await expect(cell.locator('.dps-output')).toHaveCount(0)
  await expect(cell.locator('img.dps-render')).toHaveCount(0)
})

test('restores a lightweight checkpoint and downloads a real animated GIF', async ({ page }, testInfo) => {
  await page.goto('/?cell=1')
  const cell = page.locator('.dps-cell-space')
  await expect(cell).toHaveAttribute('data-state', 'ready', { timeout: 15_000 })
  const editor = cell.locator('.cm-content')

  await editor.click()
  await editor.press(process.platform === 'darwin' ? 'Meta+A' : 'Control+A')
  await editor.pressSequentially('scoreboard objectives add runs dummy\nscoreboard players set #branch runs 1')
  await cell.getByRole('button', { name: 'Run', exact: true }).click()
  await expect(cell.getByText(/Executed 2 commands/)).toBeVisible()
  await cell.getByRole('button', { name: 'Save point', exact: true }).click()
  await expect(cell.getByRole('button', { name: 'Return', exact: true })).toBeEnabled()

  await editor.click()
  await editor.press(process.platform === 'darwin' ? 'Meta+A' : 'Control+A')
  await editor.pressSequentially('scoreboard players add #branch runs 9')
  await cell.getByRole('button', { name: 'Rerun', exact: true }).click()
  await expect(cell.getByText(/Executed 1 command/)).toBeVisible()
  await cell.getByRole('button', { name: 'Return', exact: true }).click()
  await expect(cell.locator('img.dps-render')).toBeVisible()

  await editor.click()
  await editor.press(process.platform === 'darwin' ? 'Meta+A' : 'Control+A')
  await editor.pressSequentially('scoreboard players get #branch runs')
  await cell.getByRole('button', { name: 'Run', exact: true }).click()
  await expect(cell.locator('.dps-output pre')).toContainText('"text": "1"')
  await cell.getByRole('button', { name: /Add frame/ }).click()

  const downloadPromise = page.waitForEvent('download')
  await cell.getByRole('button', { name: 'Export GIF', exact: true }).click()
  const download = await downloadPromise
  const path = testInfo.outputPath('lightweight-animation.gif')
  await download.saveAs(path)
  const bytes = await readFile(path)
  expect(bytes.subarray(0, 6).toString('ascii')).toBe('GIF89a')
  expect(bytes[bytes.length - 1]).toBe(0x3b)
  expect(download.suggestedFilename()).toBe('example.gif')
})

test('loads lightweight dependencies before ready and keeps them after reset', async ({ page }) => {
  await page.goto('/?cell=1&dependencies=1')
  const cell = page.locator('.dps-cell-space')
  await expect(cell).toHaveAttribute('data-state', 'ready', { timeout: 15_000 })
  await cell.getByRole('button', { name: 'Run', exact: true }).click()
  await expect(cell.getByText(/Executed 2 commands/)).toBeVisible()
  await cell.getByRole('button', { name: 'Reset example', exact: true }).click()
  await cell.getByRole('button', { name: 'Run', exact: true }).click()
  await expect(cell.getByText(/Executed 2 commands/)).toBeVisible()
})

test('shows the explicit Worker failure state', async ({ page }, testInfo) => {
  await page.goto('/?offline=1')
  await expect(page.locator('.dps-unavailable strong')).toHaveText('Local sandbox unavailable')
  await expect(page.getByRole('button', { name: 'Restart sandbox' })).toBeVisible()
  await page.locator('.dps-playground').screenshot({ path: testInfo.outputPath('playground-unavailable.png') })
})

test('keeps controls usable in a dark mobile layout', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/?dark=1')
  const playground = page.locator('.dps-playground')
  await expect(playground).toHaveClass(/dps-theme-dark/)
  await expect(page.getByRole('button', { name: 'Run all' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Reset sandbox' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Restore example' })).toBeVisible()
  await playground.screenshot({ path: testInfo.outputPath('playground-mobile-dark.png') })
})

test('imports a datapack ZIP into memory and executes its function', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByText('Minecraft 26.2 · Local Worker')).toBeVisible()
  const archive = zipSync({
    'pack.mcmeta': strToU8('{"pack":{"pack_format":107.1,"description":"browser"}}'),
    'data/demo/function/main.mcfunction': strToU8('say imported locally'),
  })
  await page.locator('.dps-file-input').first().setInputFiles({
    name: 'browser-pack.zip',
    mimeType: 'application/zip',
    buffer: Buffer.from(archive),
  })
  await expect(page.locator('.dps-import-choice')).toBeVisible()
  await page.getByRole('button', { name: 'Import locally' }).click()
  await expect(page.getByText(/Imported 2 files \(1 functions\)/)).toBeVisible()

  const editor = page.locator('.cm-content')
  await editor.click()
  await editor.press(process.platform === 'darwin' ? 'Meta+A' : 'Control+A')
  await editor.pressSequentially('function demo:main')
  await page.getByRole('button', { name: 'Run', exact: true }).click()
  await expect(page.getByText(/Executed 2 commands/)).toBeVisible()
})

test('uses a resource-pack texture in the Worker renderer', async ({ page }) => {
  await page.goto('/')
  const pngBytes = await page.evaluate(async () => {
    const canvas = document.createElement('canvas')
    canvas.width = 2
    canvas.height = 2
    const context = canvas.getContext('2d')!
    context.fillStyle = '#ff0000'
    context.fillRect(0, 0, 2, 2)
    const blob = await new Promise<Blob>((resolve) => canvas.toBlob((value) => resolve(value!), 'image/png'))
    return [...new Uint8Array(await blob.arrayBuffer())]
  })
  const archive = zipSync({
    'pack.mcmeta': strToU8('{"pack":{"pack_format":107.1,"description":"render texture"}}'),
    'assets/minecraft/blockstates/stone.json': strToU8('{"variants":{"":{"model":"test:block/red_stone"}}}'),
    'assets/test/models/block/red_stone.json': strToU8('{"textures":{"all":"minecraft:block/stone"},"elements":[{"from":[0,0,0],"to":[16,8,16],"faces":{"down":{"texture":"#all"},"up":{"texture":"#all"},"north":{"texture":"#all"},"south":{"texture":"#all"},"west":{"texture":"#all"},"east":{"texture":"#all"}}}]}'),
    'assets/minecraft/textures/block/stone.png': Uint8Array.from(pngBytes),
  })
  await page.locator('.dps-file-input').first().setInputFiles({
    name: 'red-stone.zip',
    mimeType: 'application/zip',
    buffer: Buffer.from(archive),
  })
  await page.locator('.dps-import-choice select').selectOption('resource-pack')
  await page.getByRole('button', { name: 'Import locally' }).click()
  await expect(page.getByText(/Imported 4 files/)).toBeVisible()
  await page.getByRole('button', { name: 'Run', exact: true }).click()
  const render = page.locator('img.dps-render')
  await expect(render).toBeVisible()
  const redBounds = await render.evaluate(async (image: HTMLImageElement) => {
    await image.decode()
    const canvas = document.createElement('canvas')
    canvas.width = image.naturalWidth
    canvas.height = image.naturalHeight
    const context = canvas.getContext('2d')!
    context.drawImage(image, 0, 0)
    const rgba = context.getImageData(0, 0, canvas.width, canvas.height).data
    let count = 0
    let minX = canvas.width
    let minY = canvas.height
    let maxX = -1
    let maxY = -1
    for (let index = 0; index < rgba.length; index += 4) {
      if (rgba[index] > rgba[index + 1] + 20 && rgba[index] > rgba[index + 2] + 20) {
        const pixel = index / 4
        const x = pixel % canvas.width
        const y = Math.floor(pixel / canvas.width)
        count += 1
        minX = Math.min(minX, x)
        minY = Math.min(minY, y)
        maxX = Math.max(maxX, x)
        maxY = Math.max(maxY, y)
      }
    }
    return { count, width: maxX - minX + 1, height: maxY - minY + 1 }
  })
  expect(redBounds.count).toBeGreaterThan(100)
  expect(redBounds.height).toBeLessThan(redBounds.width)
})

test('renders block item and styled text display entities in the local Worker', async ({ page }, testInfo) => {
  await page.goto('/?cell=1')
  const cell = page.locator('.dps-cell-space')
  await expect(cell).toHaveAttribute('data-state', 'ready', { timeout: 15_000 })
  const source = [
    'summon minecraft:block_display -1 0 2 {block_state:{Name:"minecraft:stone"},transformation:{translation:[0f,0.25f,0f],scale:[0.8f,1.4f,0.8f]},brightness:{sky:15,block:15}}',
    'summon minecraft:item_display 1 1 2 {item:{id:"minecraft:diamond",count:1},item_display:"fixed",billboard:"vertical",brightness:{sky:15,block:15}}',
    'summon minecraft:text_display 0 2 2 {text:\'{"text":"DISPLAY","color":"yellow"}\',billboard:"center",background:-13421773,text_opacity:255,shadow:1b,see_through:1b,brightness:{sky:15,block:15}}',
  ].join('\n')
  const editor = cell.locator('.cm-content')
  await editor.click()
  await editor.press(process.platform === 'darwin' ? 'Meta+A' : 'Control+A')
  await editor.pressSequentially(source)
  await cell.getByRole('button', { name: 'Run', exact: true }).click()
  await expect(cell.getByText(/Executed 3 commands/)).toBeVisible()
  await cell.getByRole('button', { name: 'Render', exact: true }).click()
  const render = cell.locator('img.dps-render')
  await expect(render).toBeVisible()
  const pixels = await render.evaluate(async (image: HTMLImageElement) => {
    await image.decode()
    const canvas = document.createElement('canvas')
    canvas.width = image.naturalWidth
    canvas.height = image.naturalHeight
    const context = canvas.getContext('2d')!
    context.drawImage(image, 0, 0)
    const rgba = context.getImageData(0, 0, canvas.width, canvas.height).data
    let yellow = 0
    let saturated = 0
    for (let index = 0; index < rgba.length; index += 4) {
      const red = rgba[index]
      const green = rgba[index + 1]
      const blue = rgba[index + 2]
      if (red > 175 && green > 175 && blue < 150) yellow += 1
      if (Math.max(red, green, blue) - Math.min(red, green, blue) > 45) saturated += 1
    }
    return { width: canvas.width, height: canvas.height, yellow, saturated }
  })
  expect(pixels).toMatchObject({ width: 960, height: 540 })
  expect(pixels.yellow).toBeGreaterThan(8)
  expect(pixels.saturated).toBeGreaterThan(100)
  await cell.screenshot({ path: testInfo.outputPath('display-entities.png') })
})

test('resolves a modern item definition and model for item display rendering', async ({ page }) => {
  await page.goto('/')
  const pngBytes = await page.evaluate(async () => {
    const canvas = document.createElement('canvas')
    canvas.width = 4
    canvas.height = 4
    const context = canvas.getContext('2d')!
    context.fillStyle = '#ff2020'
    context.fillRect(0, 0, 4, 4)
    const blob = await new Promise<Blob>((resolve) => canvas.toBlob((value) => resolve(value!), 'image/png'))
    return [...new Uint8Array(await blob.arrayBuffer())]
  })
  const archive = zipSync({
    'pack.mcmeta': strToU8('{"pack":{"pack_format":107.1,"description":"item display model"}}'),
    'assets/demo/items/display_gem.json': strToU8('{"model":{"type":"minecraft:model","model":"demo:item/display_gem"}}'),
    'assets/demo/models/item/display_gem.json': strToU8('{"parent":"minecraft:item/generated","textures":{"layer0":"demo:item/gem_layer"},"display":{"fixed":{"rotation":[15,30,20],"translation":[3,1,0],"scale":[0.8,0.6,0.4]}}}'),
    'assets/demo/textures/item/gem_layer.png': Uint8Array.from(pngBytes),
  })
  await page.locator('.dps-file-input').first().setInputFiles({
    name: 'display-item.zip',
    mimeType: 'application/zip',
    buffer: Buffer.from(archive),
  })
  await page.locator('.dps-import-choice select').selectOption('resource-pack')
  await page.getByRole('button', { name: 'Import locally' }).click()
  const editor = page.locator('.cm-content')
  await editor.click()
  await editor.press(process.platform === 'darwin' ? 'Meta+A' : 'Control+A')
  await editor.pressSequentially('summon minecraft:item_display 0 0 2 {item:{id:"demo:display_gem",count:1},item_display:"fixed",billboard:"center",brightness:{sky:15,block:15}}')
  await page.getByRole('button', { name: 'Run', exact: true }).click()
  const render = page.locator('img.dps-render')
  await expect(render).toBeVisible()
  const red = await render.evaluate(async (image: HTMLImageElement) => {
    await image.decode()
    const canvas = document.createElement('canvas')
    canvas.width = image.naturalWidth
    canvas.height = image.naturalHeight
    const context = canvas.getContext('2d')!
    context.drawImage(image, 0, 0)
    const rgba = context.getImageData(0, 0, canvas.width, canvas.height).data
    let count = 0
    for (let index = 0; index < rgba.length; index += 4) {
      if (rgba[index] > 175 && rgba[index + 1] < 100 && rgba[index + 2] < 100) count += 1
    }
    return count
  })
  expect(red).toBeGreaterThan(30)
})

test('imports an external client jar and exports its rendered PNG', async ({ page }, testInfo) => {
  const clientJar = process.env.DPS_CLIENT_JAR
  test.skip(!clientJar, 'Set DPS_CLIENT_JAR to run the real-client asset render')
  test.setTimeout(120_000)
  await page.goto('/?version=26.1.2&width=960&height=720')
  await expect(page.getByText('Minecraft 26.1.2 · Local Worker')).toBeVisible({ timeout: 30_000 })
  await page.locator('.dps-file-input').first().setInputFiles(clientJar!)
  await expect(page.getByText(/Imported .* files/)).toBeVisible({ timeout: 60_000 })
  const source = [
    'time set noon',
    'summon minecraft:block_display 0 0 1.25 {block_state:{Name:"minecraft:stone"},transformation:{translation:[-1.5f,-0.25f,-0.6f],scale:[3f,0.25f,1.2f]},brightness:{sky:15,block:15}}',
    'summon minecraft:block_display -0.65 0 1.25 {block_state:{Name:"minecraft:diamond_block"},transformation:{translation:[0f,0.1f,0f],left_rotation:[0f,0.258819f,0f,0.965926f],scale:[0.75f,1.1f,0.75f],right_rotation:[0f,0f,0f,1f]},brightness:{sky:15,block:15}}',
    'summon minecraft:item_display 0.75 0.8 1.25 {item:{id:"minecraft:diamond",count:1},item_display:"fixed",billboard:"vertical",brightness:{sky:15,block:15}}',
    'summon minecraft:text_display 0 1.7 1.25 {text:\'{"text":"VANILLA 26.1.2","color":"aqua"}\',billboard:"center",background:-14540254,text_opacity:255,shadow:1b,see_through:1b,brightness:{sky:15,block:15}}',
  ].join('\n')
  const editor = page.locator('.cm-content')
  await editor.click()
  await editor.press(process.platform === 'darwin' ? 'Meta+A' : 'Control+A')
  await editor.pressSequentially(source)
  await page.getByRole('button', { name: 'Run', exact: true }).click()
  await expect(page.getByText(/Executed 5 commands/)).toBeVisible({ timeout: 30_000 })
  const render = page.locator('img.dps-render')
  await expect(render).toBeVisible({ timeout: 30_000 })
  const png = await render.evaluate(async (image: HTMLImageElement) => {
    await image.decode()
    return [...new Uint8Array(await (await fetch(image.src)).arrayBuffer())]
  })
  const output = process.env.DPS_RENDER_OUTPUT ?? testInfo.outputPath('client-jar-display-entities.png')
  await mkdir(dirname(output), { recursive: true })
  await writeFile(output, Buffer.from(png))
  await testInfo.attach('client-jar-display-entities', { path: output, contentType: 'image/png' })
})

test('exports a real-client display entity interpolation GIF', async ({ page }, testInfo) => {
  const clientJar = process.env.DPS_CLIENT_JAR
  test.skip(!clientJar, 'Set DPS_CLIENT_JAR to run the real-client display animation')
  test.setTimeout(180_000)
  await page.goto('/?version=26.1.2&width=640&height=480&animationWidth=640&animationHeight=480&animationDelayMs=80')
  const connection = page.locator('.dps-connection')
  await expect(connection).toHaveAttribute('data-state', 'ready', { timeout: 30_000 })
  await expect(connection).toContainText('Minecraft 26.1.2')
  await page.locator('.dps-file-input').first().setInputFiles(clientJar!)
  await expect(page.getByText(/Imported .* files/)).toBeVisible({ timeout: 60_000 })

  const editor = page.locator('.cm-content')
  const run = page.locator('.dps-cell-actions .dps-button-primary')
  const frameButton = page.locator('[data-action="capture-frame"]')
  let frameCount = 0
  const runFrame = async (source: string) => {
    await editor.fill(source)
    await run.click()
    frameCount += 1
    await expect(frameButton).toHaveText(`Add frame (${frameCount})`, { timeout: 30_000 })
  }

  await runFrame([
    'time set noon',
    'summon minecraft:block_display 0 0 1.25 {Tags:["pedestal"],block_state:{Name:"minecraft:stone"},transformation:{translation:[-1.6f,-0.25f,-0.75f],left_rotation:[0f,0f,0f,1f],scale:[3.2f,0.25f,1.5f],right_rotation:[0f,0f,0f,1f]},brightness:{sky:15,block:15}}',
    'summon minecraft:block_display 0 0.3 1.25 {Tags:["core"],block_state:{Name:"minecraft:diamond_block"},interpolation_duration:8,transformation:{translation:[-0.5f,0f,-0.5f],left_rotation:[0f,0f,0f,1f],scale:[0.85f,0.85f,0.85f],right_rotation:[0f,0f,0f,1f]},brightness:{sky:15,block:15}}',
    'summon minecraft:item_display -1.2 0.85 1.25 {Tags:["orbit_a"],item:{id:"minecraft:diamond",count:1},item_display:"fixed",billboard:"center",teleport_duration:8,brightness:{sky:15,block:15}}',
    'summon minecraft:item_display 1.2 0.85 1.25 {Tags:["orbit_b"],item:{id:"minecraft:emerald",count:1},item_display:"fixed",billboard:"center",teleport_duration:8,brightness:{sky:15,block:15}}',
    'summon minecraft:text_display 0 1.9 1.25 {Tags:["title"],text:\'{"text":"DISPLAY ENTITY MOTION","color":"aqua","bold":true}\',billboard:"center",background:-14540254,text_opacity:255,shadow:1b,see_through:1b,brightness:{sky:15,block:15}}',
  ].join('\n'))

  const orbit = [
    [-1.2, 0.85, 1.25],
    [0, 1.45, 0.55],
    [1.2, 0.85, 1.25],
    [0, 0.35, 1.95],
  ]
  for (let segment = 1; segment <= 4; segment += 1) {
    const angle = segment * Math.PI / 2
    const y = Math.sin(angle / 2).toFixed(6)
    const w = Math.cos(angle / 2).toFixed(6)
    const scale = segment % 2 === 0 ? '0.85' : '1.10'
    const a = orbit[segment % orbit.length]
    const b = orbit[(segment + 2) % orbit.length]
    await runFrame([
      `data merge entity @e[tag=core,limit=1] {start_interpolation:0,interpolation_duration:8,transformation:{translation:[-0.5f,0f,-0.5f],left_rotation:[0f,${y}f,0f,${w}f],scale:[${scale}f,${scale}f,${scale}f],right_rotation:[0f,0f,0f,1f]}}`,
      `tp @e[tag=orbit_a,limit=1] ${a[0]} ${a[1]} ${a[2]}`,
      `tp @e[tag=orbit_b,limit=1] ${b[0]} ${b[1]} ${b[2]}`,
    ].join('\n'))
    for (let tick = 0; tick < 8; tick += 1) await runFrame('tick 1')
  }

  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Export GIF', exact: true }).click()
  const download = await downloadPromise
  const output = process.env.DPS_GIF_OUTPUT ?? testInfo.outputPath('client-jar-display-animation.gif')
  await mkdir(dirname(output), { recursive: true })
  await download.saveAs(output)
  const bytes = await readFile(output)
  expect(bytes.subarray(0, 6).toString('ascii')).toBe('GIF89a')
  expect(bytes[bytes.length - 1]).toBe(0x3b)
  expect(frameCount).toBe(37)
  await testInfo.attach('client-jar-display-animation', { path: output, contentType: 'image/gif' })
})

test('exports a stabilized long display entity physics GIF', async ({ page }, testInfo) => {
  const clientJar = process.env.DPS_CLIENT_JAR
  test.skip(!clientJar, 'Set DPS_CLIENT_JAR to run the real-client display physics animation')
  test.setTimeout(240_000)
  await page.goto('/?version=26.1.2&width=768&height=576&animationWidth=512&animationHeight=384&animationDelayMs=90&captureOnExecute=false')
  const connection = page.locator('.dps-connection')
  await expect(connection).toHaveAttribute('data-state', 'ready', { timeout: 30_000 })
  await expect(connection).toContainText('Minecraft 26.1.2')
  await page.locator('.dps-file-input').first().setInputFiles(clientJar!)
  await expect(page.getByText(/Imported .* files/)).toBeVisible({ timeout: 60_000 })

  const editor = page.locator('.cm-content')
  const run = page.locator('.dps-cell-actions .dps-button-primary')
  const frameButton = page.locator('[data-action="capture-frame"]')
  let frameCount = 0
  const execute = async (source: string) => {
    await editor.fill(source)
    await run.click()
  }
  const capture = async () => {
    await frameButton.click()
    frameCount += 1
    await expect(frameButton).toHaveText(`Add frame (${frameCount})`, { timeout: 30_000 })
  }

  type Body = {
    tag: string
    item: string
    scale: number
    floor: number
    height: number
    bounceCycles: number
    bouncePhase: number
    xAmplitude: number
    xCycles: number
    xPhase: number
    zAmplitude: number
    zCycles: number
    zPhase: number
    turns: number
    axis: [number, number, number]
  }
  const bodies: Body[] = [
    { tag: 'body_diamond', item: 'minecraft:diamond', scale: 0.92, floor: 0.38, height: 1.35, bounceCycles: 2, bouncePhase: 0, xAmplitude: 1.25, xCycles: 1, xPhase: 0, zAmplitude: 0.48, zCycles: 2, zPhase: 0.25, turns: 3, axis: [0.707107, 0.707107, 0] },
    { tag: 'body_emerald', item: 'minecraft:emerald', scale: 0.88, floor: 0.36, height: 1.18, bounceCycles: 3, bouncePhase: 0.34, xAmplitude: 1.08, xCycles: 2, xPhase: 0.5, zAmplitude: 0.56, zCycles: 1, zPhase: 0.1, turns: -4, axis: [0.267261, 0.534522, 0.801784] },
    { tag: 'body_gold', item: 'minecraft:gold_ingot', scale: 0.96, floor: 0.34, height: 1.46, bounceCycles: 2, bouncePhase: 0.67, xAmplitude: 0.88, xCycles: 3, xPhase: 0.2, zAmplitude: 0.62, zCycles: 2, zPhase: 0.65, turns: 5, axis: [0.408248, 0.816497, 0.408248] },
  ]
  const wrap = (value: number) => ((value % 1) + 1) % 1
  const triangle = (value: number) => 1 - 4 * Math.abs(wrap(value) - 0.5)
  const sample = (body: Body, time: number) => {
    const bounce = wrap(time * body.bounceCycles + body.bouncePhase)
    const angle = Math.PI * 2 * body.turns * time
    const halfSin = Math.sin(angle / 2)
    return {
      x: body.xAmplitude * triangle(time * body.xCycles + body.xPhase),
      y: body.floor + body.height * 4 * bounce * (1 - bounce),
      z: 1.25 + body.zAmplitude * triangle(time * body.zCycles + body.zPhase),
      quaternion: [body.axis[0] * halfSin, body.axis[1] * halfSin, body.axis[2] * halfSin, Math.cos(angle / 2)],
    }
  }
  const number = (value: number) => `${Math.abs(value) < 0.0000005 ? 0 : value.toFixed(6)}f`
  const summonBody = (body: Body) => {
    const pose = sample(body, 0)
    return `summon minecraft:item_display ${pose.x.toFixed(6)} ${pose.y.toFixed(6)} ${pose.z.toFixed(6)} {Tags:["${body.tag}"],item:{id:"${body.item}",count:1},item_display:"fixed",billboard:"fixed",teleport_duration:2,interpolation_duration:2,transformation:{translation:[0f,0f,0f],left_rotation:[${pose.quaternion.map(number).join(',')}],scale:[${body.scale}f,${body.scale}f,${body.scale}f],right_rotation:[0f,0f,0f,1f]},brightness:{sky:15,block:15}}`
  }

  await execute([
    'time set noon',
    'summon minecraft:block_display 0 0 1.25 {Tags:["arena"],block_state:{Name:"minecraft:stone"},transformation:{translation:[-1.7f,-0.16f,-0.85f],left_rotation:[0f,0f,0f,1f],scale:[3.4f,0.16f,1.7f],right_rotation:[0f,0f,0f,1f]},brightness:{sky:15,block:15}}',
    ...bodies.map(summonBody),
    'summon minecraft:text_display 0 2.2 1.25 {Tags:["title"],text:\'{"text":"DISPLAY PHYSICS | LERP + SLERP","color":"aqua","bold":true}\',billboard:"center",background:-14540254,text_opacity:255,shadow:1b,see_through:1b,brightness:{sky:15,block:15}}',
    'summon minecraft:text_display -1.35 0 0.45 {Tags:["camera_anchor"],text:"",billboard:"fixed",background:0,text_opacity:0,see_through:1b}',
    'summon minecraft:text_display 1.35 2.2 2.05 {Tags:["camera_anchor"],text:"",billboard:"fixed",background:0,text_opacity:0,see_through:1b}',
  ].join('\n'))
  await capture()

  const segments = 40
  for (let segment = 1; segment <= segments; segment += 1) {
    const time = segment / segments
    const targets = bodies.flatMap((body) => {
      const pose = sample(body, time)
      return [
        `data merge entity @e[tag=${body.tag},limit=1] {start_interpolation:0,interpolation_duration:2,transformation:{translation:[0f,0f,0f],left_rotation:[${pose.quaternion.map(number).join(',')}],scale:[${body.scale}f,${body.scale}f,${body.scale}f],right_rotation:[0f,0f,0f,1f]}}`,
        `tp @e[tag=${body.tag},limit=1] ${pose.x.toFixed(6)} ${pose.y.toFixed(6)} ${pose.z.toFixed(6)}`,
      ]
    })
    await execute([...targets, 'tick 1'].join('\n'))
    await capture()
    await execute('tick 1')
    await capture()
  }

  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Export GIF', exact: true }).click()
  const download = await downloadPromise
  const output = process.env.DPS_PHYSICS_GIF_OUTPUT ?? testInfo.outputPath('client-jar-display-physics.gif')
  await mkdir(dirname(output), { recursive: true })
  await download.saveAs(output)
  const bytes = await readFile(output)
  expect(bytes.subarray(0, 6).toString('ascii')).toBe('GIF89a')
  expect(bytes[bytes.length - 1]).toBe(0x3b)
  expect(frameCount).toBe(81)
  await testInfo.attach('client-jar-display-physics', { path: output, contentType: 'image/gif' })
})

test('exports a stabilized many-block display explosion GIF', async ({ page }, testInfo) => {
  const clientJar = process.env.DPS_CLIENT_JAR
  test.skip(!clientJar, 'Set DPS_CLIENT_JAR to run the real-client block explosion animation')
  test.setTimeout(600_000)
  await page.goto('/?version=26.1.2&width=768&height=576&animationWidth=512&animationHeight=384&animationDelayMs=70&captureOnExecute=false')
  const connection = page.locator('.dps-connection')
  await expect(connection).toHaveAttribute('data-state', 'ready', { timeout: 30_000 })
  await expect(connection).toContainText('Minecraft 26.1.2')
  await page.locator('.dps-file-input').first().setInputFiles(clientJar!)
  await expect(page.getByText(/Imported .* files/)).toBeVisible({ timeout: 60_000 })

  const editor = page.locator('.cm-content')
  const run = page.locator('.dps-cell-actions .dps-button-primary')
  const frameButton = page.locator('[data-action="capture-frame"]')
  let frameCount = 0
  const execute = async (source: string) => {
    await editor.fill(source)
    await run.click()
  }
  const capture = async () => {
    await frameButton.click()
    frameCount += 1
    await expect(frameButton).toHaveText(`Add frame (${frameCount})`, { timeout: 60_000 })
  }

  type Debris = {
    tag: string
    block: string
    scale: number
    x: number
    y: number
    z: number
    vx: number
    vy: number
    vz: number
    angle: number
    angularVelocity: number
    axis: [number, number, number]
  }
  type DebrisPose = { x: number; y: number; z: number; quaternion: number[] }
  const random = (seed: number) => {
    const value = Math.sin(seed * 12.9898 + 78.233) * 43758.5453
    return value - Math.floor(value)
  }
  const normalize = (x: number, y: number, z: number): [number, number, number] => {
    const length = Math.hypot(x, y, z) || 1
    return [x / length, y / length, z / length]
  }
  const blockTypes = [
    'minecraft:stone_bricks',
    'minecraft:cobblestone',
    'minecraft:bricks',
    'minecraft:oak_planks',
    'minecraft:copper_block',
    'minecraft:deepslate_tiles',
  ]
  const scale = 0.44
  const blastCenter = { x: 0, y: 1.045, z: 1.25 }
  const debris: Debris[] = []
  let debrisIndex = 0
  for (let xIndex = 0; xIndex < 5; xIndex += 1) {
    for (let yIndex = 0; yIndex < 4; yIndex += 1) {
      for (let zIndex = 0; zIndex < 3; zIndex += 1) {
        const x = (xIndex - 2) * 0.48
        const y = 0.34 + yIndex * 0.47
        const z = 1.25 + (zIndex - 1) * 0.47
        const dx = x - blastCenter.x
        const dy = y - blastCenter.y
        const dz = z - blastCenter.z
        const direction = normalize(dx, dy * 0.82, dz)
        const distance = Math.hypot(dx, dy, dz)
        const strength = 1.15 + (1 - Math.min(distance / 1.5, 1)) * 0.9 + random(debrisIndex + 1) * 0.45
        const axis = normalize(
          random(debrisIndex + 101) * 2 - 1,
          random(debrisIndex + 211) * 2 - 1,
          random(debrisIndex + 307) * 2 - 1,
        )
        const centerTnt = xIndex === 2 && zIndex === 1 && (yIndex === 1 || yIndex === 2)
        debris.push({
          tag: `debris_${debrisIndex}`,
          block: centerTnt ? 'minecraft:tnt' : blockTypes[(xIndex + yIndex * 2 + zIndex * 3) % blockTypes.length],
          scale,
          x,
          y,
          z,
          vx: direction[0] * strength + (random(debrisIndex + 401) - 0.5) * 0.35,
          vy: 2.45 + direction[1] * 1.15 + random(debrisIndex + 503) * 1.15,
          vz: direction[2] * strength + (random(debrisIndex + 601) - 0.5) * 0.35,
          angle: 0,
          angularVelocity: (4.5 + random(debrisIndex + 701) * 8) * (random(debrisIndex + 809) < 0.5 ? -1 : 1),
          axis,
        })
        debrisIndex += 1
      }
    }
  }

  const pose = (body: Debris): DebrisPose => {
    const halfSin = Math.sin(body.angle / 2)
    return {
      x: body.x,
      y: body.y,
      z: body.z,
      quaternion: [body.axis[0] * halfSin, body.axis[1] * halfSin, body.axis[2] * halfSin, Math.cos(body.angle / 2)],
    }
  }
  const keyframes: DebrisPose[][] = []
  const allPoses = debris.map(pose)
  const floorHeight = 0.12 + scale / 2
  const deltaTime = 0.075
  for (let segment = 0; segment < 33; segment += 1) {
    debris.forEach((body) => {
      body.vy -= 7.4 * deltaTime
      body.x += body.vx * deltaTime
      body.y += body.vy * deltaTime
      body.z += body.vz * deltaTime
      body.angle += body.angularVelocity * deltaTime
      body.vx *= 0.995
      body.vz *= 0.995
      if (body.y < floorHeight) {
        body.y = floorHeight + (floorHeight - body.y) * 0.12
        body.vy = Math.abs(body.vy) * 0.42
        body.vx *= 0.76
        body.vz *= 0.76
        body.angularVelocity *= 0.82
        if (body.vy < 0.12) body.vy = 0
      }
    })
    const poses = debris.map(pose)
    keyframes.push(poses)
    allPoses.push(...poses)
  }

  const minimum = {
    x: Math.min(...allPoses.map((value) => value.x)) - 0.5,
    y: 0,
    z: Math.min(...allPoses.map((value) => value.z)) - 0.5,
  }
  const maximum = {
    x: Math.max(...allPoses.map((value) => value.x)) + 0.5,
    y: Math.max(...allPoses.map((value) => value.y)) + 0.5,
    z: Math.max(...allPoses.map((value) => value.z)) + 0.5,
  }
  const floorWidth = maximum.x - minimum.x
  const floorDepth = maximum.z - minimum.z
  const number = (value: number) => `${Math.abs(value) < 0.0000005 ? 0 : value.toFixed(6)}f`
  const summonDebris = (body: Debris, initial: DebrisPose) =>
    `summon minecraft:block_display ${initial.x.toFixed(6)} ${initial.y.toFixed(6)} ${initial.z.toFixed(6)} {Tags:["${body.tag}"],block_state:{Name:"${body.block}"},teleport_duration:2,interpolation_duration:2,transformation:{translation:[${number(-scale / 2)},${number(-scale / 2)},${number(-scale / 2)}],left_rotation:[${initial.quaternion.map(number).join(',')}],scale:[${scale}f,${scale}f,${scale}f],right_rotation:[0f,0f,0f,1f]},brightness:{sky:15,block:15}}`

  await execute([
    'time set noon',
    `summon minecraft:block_display 0 0 1.25 {Tags:["blast_floor"],block_state:{Name:"minecraft:polished_deepslate"},transformation:{translation:[${number(minimum.x)},-0.12f,${number(minimum.z - 1.25)}],left_rotation:[0f,0f,0f,1f],scale:[${number(floorWidth)},0.12f,${number(floorDepth)}],right_rotation:[0f,0f,0f,1f]},brightness:{sky:15,block:15}}`,
    `summon minecraft:text_display ${minimum.x.toFixed(6)} 0 ${minimum.z.toFixed(6)} {Tags:["camera_anchor"],text:"",billboard:"fixed",background:0,text_opacity:0,see_through:1b}`,
    `summon minecraft:text_display ${maximum.x.toFixed(6)} ${maximum.y.toFixed(6)} ${maximum.z.toFixed(6)} {Tags:["camera_anchor"],text:"",billboard:"fixed",background:0,text_opacity:0,see_through:1b}`,
    ...debris.map((body, index) => summonDebris(body, allPoses[index])),
  ].join('\n'))
  await capture()
  for (let hold = 1; hold < 6; hold += 1) await capture()

  for (let segment = 0; segment < keyframes.length; segment += 1) {
    const targets = debris.flatMap((body, index) => {
      const target = keyframes[segment][index]
      return [
        `data merge entity @e[tag=${body.tag},limit=1] {start_interpolation:0,interpolation_duration:2,transformation:{translation:[${number(-scale / 2)},${number(-scale / 2)},${number(-scale / 2)}],left_rotation:[${target.quaternion.map(number).join(',')}],scale:[${scale}f,${scale}f,${scale}f],right_rotation:[0f,0f,0f,1f]}}`,
        `tp @e[tag=${body.tag},limit=1] ${target.x.toFixed(6)} ${target.y.toFixed(6)} ${target.z.toFixed(6)}`,
      ]
    })
    await execute([...targets, 'tick 1'].join('\n'))
    await capture()
    await execute('tick 1')
    await capture()
  }
  for (let hold = 0; hold < 8; hold += 1) await capture()

  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Export GIF', exact: true }).click()
  const download = await downloadPromise
  const output = process.env.DPS_EXPLOSION_GIF_OUTPUT ?? testInfo.outputPath('client-jar-display-explosion.gif')
  await mkdir(dirname(output), { recursive: true })
  await download.saveAs(output)
  const bytes = await readFile(output)
  expect(bytes.subarray(0, 6).toString('ascii')).toBe('GIF89a')
  expect(bytes[bytes.length - 1]).toBe(0x3b)
  expect(frameCount).toBe(80)
  await testInfo.attach('client-jar-display-explosion', { path: output, contentType: 'image/gif' })
})
