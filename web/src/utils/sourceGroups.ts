import type { SourceSummary } from '@/api/types'

/** 书源分组分隔符：中英文逗号、分号，以及换行 */
const GROUP_SEPARATOR = /[,，;；\n]/

/** 把 bookSourceGroup 字段拆成去空白、去重的分组名 */
export function splitSourceGroups(value: string | null | undefined): string[] {
  if (!value) return []
  const groups: string[] = []
  for (const part of value.split(GROUP_SEPARATOR)) {
    const name = part.trim()
    if (name && !groups.includes(name)) groups.push(name)
  }
  return groups
}

/** 汇总多个书源的分组名，按名称排序 */
export function collectSourceGroups(sources: Pick<SourceSummary, 'bookSourceGroup'>[]): string[] {
  const groups = new Set<string>()
  for (const source of sources) {
    for (const name of splitSourceGroups(source.bookSourceGroup)) groups.add(name)
  }
  return [...groups].sort((a, b) => a.localeCompare(b))
}

/** 书源是否属于指定分组（书源可以同时属于多个分组） */
export function belongsToGroup(source: Pick<SourceSummary, 'bookSourceGroup'>, group: string): boolean {
  return splitSourceGroups(source.bookSourceGroup).includes(group)
}
