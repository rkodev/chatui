import { describe, expect, it, vi } from 'vitest'
import { downloadTextFile } from './download'

describe('downloadTextFile', () => {
  it('downloads a Blob with the artifact name and revokes the object URL', async () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const createObjectURL = vi.fn<(blob: Blob) => string>(() => 'blob:chatui-export')
    const revokeObjectURL = vi.fn()

    downloadTextFile(
      {
        filename: 'chatui-export.json',
        mimeType: 'application/json',
        content: '{"format":"chatui.export"}\n',
      },
      document,
      { createObjectURL, revokeObjectURL },
    )

    expect(click).toHaveBeenCalledTimes(1)
    expect(createObjectURL).toHaveBeenCalledTimes(1)
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:chatui-export')
    expect(document.querySelector('a')).toBeNull()

    const blob = createObjectURL.mock.calls[0][0]
    expect(blob.type).toBe('application/json;charset=utf-8')
    expect(await blob.text()).toBe('{"format":"chatui.export"}\n')
  })
})
