import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { usePreferredDark } from '@/utils/media'
import { syncAppThemeColor } from '@/utils/themeColor'
import { isLight } from '@/utils/color'
import { appThemeKey, migrateAppThemeKey, parseAppThemeData, appThemeVars, presetAppThemes, type AppTheme } from '@/utils/appTheme'
import { t } from '@/i18n'

type ThemeMode = 'auto' | 'light' | 'dark'

function load<T>(key: string, def: T): T {
  try {
    const v = localStorage.getItem(key)
    return v ? (JSON.parse(v) as T) : def
  } catch {
    return def
  }
}

function save(key: string, v: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(v))
  } catch {
    /* ignore */
  }
}

const VAR_NAMES: Record<keyof ReturnType<typeof appThemeVars>, string> = {
  bg: '--app-bg',
  surface: '--app-surface',
  border: '--app-border',
  text: '--app-text',
  text2: '--app-text-2',
  accent: '--app-accent',
  bar: '--app-bar',
  barText: '--app-bar-text',
  barText2: '--app-bar-text-2',
  barAccent: '--app-bar-accent',
  nav: '--app-nav',
  navText: '--app-nav-text',
  navAccent: '--app-nav-accent',
  onAccent: '--app-on-accent',
  hover: '--app-accent-hover',
  pressed: '--app-accent-pressed',
}

/**
 * 与阅读 App 一样分日间、夜间两套主题：深色模式（或跟随系统切到深色）时用夜间主题。
 * 主题保存为名称，名称在内置和自定义主题里都找不到时回退到默认主题
 */
export const useUiStore = defineStore('ui', () => {
  const themeMode = ref<ThemeMode>(load('legado_theme', 'auto'))
  const systemDark = usePreferredDark()
  const isDark = computed(() => themeMode.value === 'dark' || (themeMode.value === 'auto' && systemDark.value))

  const storedThemes = load<unknown>('legado_app_themes', [])
  const customAppThemes = ref<AppTheme[]>(Array.isArray(storedThemes) ? parseAppThemeData(storedThemes) : [])
  const savedDay = load<unknown>('legado_app_theme_day', '')
  const savedNight = load<unknown>('legado_app_theme_night', '')
  const dayThemeName = ref(typeof savedDay === 'string' && savedDay ? migrateAppThemeKey(savedDay) : appThemeKey(presetAppThemes[0]))
  const nightThemeName = ref(typeof savedNight === 'string' && savedNight ? migrateAppThemeKey(savedNight) : appThemeKey(presetAppThemes[1]))

  const allAppThemes = computed(() => [...presetAppThemes, ...customAppThemes.value])
  const findTheme = (name: string, night: boolean) =>
    allAppThemes.value.find((theme) => appThemeKey(theme) === name) ?? presetAppThemes[night ? 1 : 0]
  const activeAppTheme = computed(() =>
    isDark.value ? findTheme(nightThemeName.value, true) : findTheme(dayThemeName.value, false),
  )
  const appVars = computed(() => appThemeVars(activeAppTheme.value))
  /** 界面实际是否为深色：看主题背景色，而不是日间 / 夜间模式（有的日间主题背景也很深） */
  const appDark = computed(() => !isLight(activeAppTheme.value.backgroundColor))

  watch(themeMode, (v) => save('legado_theme', v))
  watch(customAppThemes, (v) => save('legado_app_themes', v), { deep: true })
  watch(dayThemeName, (v) => save('legado_app_theme_day', v))
  watch(nightThemeName, (v) => save('legado_app_theme_night', v))
  watch(
    [appDark, appVars],
    ([d, vars]) => {
      const root = document.documentElement
      root.dataset.theme = d ? 'dark' : 'light'
      for (const [k, name] of Object.entries(VAR_NAMES)) root.style.setProperty(name, vars[k as keyof typeof vars])
      syncAppThemeColor()
    },
    { immediate: true },
  )

  /** 选用主题：夜间主题放到夜间槽位，并切到对应的模式，让用户马上看到效果 */
  function applyAppTheme(theme: AppTheme) {
    if (theme.isNightTheme) nightThemeName.value = appThemeKey(theme)
    else dayThemeName.value = appThemeKey(theme)
    if (themeMode.value !== 'auto' || systemDark.value !== theme.isNightTheme) {
      themeMode.value = theme.isNightTheme ? 'dark' : 'light'
    }
  }

  /** 保存自定义主题：与内置主题重名时加后缀，与已有自定义主题重名时覆盖 */
  function saveCustomAppTheme(theme: AppTheme, replaceName?: string) {
    let name = theme.themeName
    while (presetAppThemes.some((p) => p.themeName === name)) name += t('·自定义')
    const saved = { ...theme, id: undefined, themeName: name }
    const list = customAppThemes.value.filter((c) => c.themeName !== name && c.themeName !== replaceName)
    customAppThemes.value = [...list, saved]
    if (replaceName && replaceName !== name) {
      if (dayThemeName.value === `custom:${replaceName}`) dayThemeName.value = appThemeKey(saved)
      if (nightThemeName.value === `custom:${replaceName}`) nightThemeName.value = appThemeKey(saved)
    }
    return saved
  }

  function removeCustomAppTheme(name: string) {
    customAppThemes.value = customAppThemes.value.filter((c) => c.themeName !== name)
    if (dayThemeName.value === `custom:${name}`) dayThemeName.value = appThemeKey(presetAppThemes[0])
    if (nightThemeName.value === `custom:${name}`) nightThemeName.value = appThemeKey(presetAppThemes[1])
  }

  /** 搜索时选择的书源分组 */
  const searchGroup = ref<string>(load('legado_search_group', ''))
  watch(searchGroup, (v) => save('legado_search_group', v))

  /** 搜索历史（最近的在前，只存在本机） */
  const searchHistory = ref<string[]>(load('legado_search_history', []))
  watch(searchHistory, (v) => save('legado_search_history', v), { deep: true })
  function addSearchHistory(key: string) {
    searchHistory.value = [key, ...searchHistory.value.filter((k) => k !== key)].slice(0, 20)
  }
  function removeSearchHistory(key: string) {
    searchHistory.value = searchHistory.value.filter((k) => k !== key)
  }

  return {
    themeMode, isDark, appDark, searchGroup, searchHistory, addSearchHistory, removeSearchHistory,
    customAppThemes, dayThemeName, nightThemeName, allAppThemes, activeAppTheme, appVars,
    applyAppTheme, saveCustomAppTheme, removeCustomAppTheme,
  }
})
