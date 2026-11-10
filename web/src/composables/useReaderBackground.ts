import { ref, watch, type Ref } from 'vue'
import { useMessage } from 'naive-ui'
import { prefsApi } from '@/api'
import type { CustomTheme } from '@/stores/reader'

export function useReaderBackground(currentCustom: Readonly<Ref<CustomTheme | undefined>>) {
  const message = useMessage()
  const backgroundImage = ref('')
  watch(() => [currentCustom.value?.id, currentCustom.value?.image], async ([id, version], _, cleanup) => {
    let active = true
    cleanup(() => { active = false })
    backgroundImage.value = ''
    if (!id || !version) return
    try {
      const image = await prefsApi.get<unknown>(`readerBg_${id}`)
      if (active && typeof image === 'string' && image.startsWith('data:image/jpeg;base64,')) backgroundImage.value = image
    } catch (error) {
      if (!(error instanceof Error)) throw error
      message.error(error.message)
    }
  }, { immediate: true })
  return backgroundImage
}
