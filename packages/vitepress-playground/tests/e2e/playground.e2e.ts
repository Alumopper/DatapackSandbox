import { expect, test } from '@playwright/test'

test('runs a real cell and renders a screenshot', async ({ page }, testInfo) => {
  await page.goto('/')
  await expect(page.getByText('Minecraft 26.2')).toBeVisible()
  await page.getByRole('button', { name: 'Run', exact: true }).click()
  await expect(page.getByText(/Executed 1 command/)).toBeVisible()
  const render = page.locator('img.dps-render')
  await expect(render).toBeVisible()
  await expect(render).toHaveAttribute('src', /^data:image\/png;base64,/)
  const restore = page.getByRole('button', { name: 'Restore example' })
  await expect(restore).toBeEnabled()
  await restore.click()
  await expect(page.locator('.dps-output')).toHaveCount(0)
  await expect(page.locator('.cm-content')).toContainText('setblock 0 0 2 minecraft:stone')
  await page.locator('.dps-playground').screenshot({ path: testInfo.outputPath('playground-ready.png') })
})

test('shows the explicit API failure state', async ({ page }, testInfo) => {
  await page.goto('/?offline=1')
  await expect(page.getByText('Playground unavailable', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Retry connection' })).toBeVisible()
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
