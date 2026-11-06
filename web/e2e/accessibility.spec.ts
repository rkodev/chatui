import AxeBuilder from '@axe-core/playwright'
import { completeOnboarding, expect, gotoOnboarding, test } from './fixtures/app'

test.describe('accessibility smoke', () => {
  test('onboarding has no critical axe violations', async ({ page }) => {
    await gotoOnboarding(page)

    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa'])
      .analyze()
    const critical = results.violations.filter((violation) => violation.impact === 'critical')

    expect(critical).toEqual([])
  })

  test('chat has no critical axe violations', async ({ page }) => {
    await completeOnboarding(page)

    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa'])
      .analyze()
    const critical = results.violations.filter((violation) => violation.impact === 'critical')

    expect(critical).toEqual([])
  })
})
