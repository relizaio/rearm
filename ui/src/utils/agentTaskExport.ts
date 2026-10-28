/**
 * The JSON export of a board task: the task object as the API returns it, with the page's whole
 * read and every other task field (see AGENT_TASK_EXPORT_EXTRA in store.ts), wrapped with what the
 * file is and when it was made. The timeline is the task's statusHistory; its rounds are signOffs,
 * returns and reopens.
 */
export const TASK_EXPORT_FORMAT = 'rearm-agent-task'
export const TASK_EXPORT_FORMAT_VERSION = 1

export function taskExportDocument (task: any, exportedAt: Date = new Date()): Record<string, any> {
    return {
        format: TASK_EXPORT_FORMAT,
        formatVersion: TASK_EXPORT_FORMAT_VERSION,
        exportedAt: exportedAt.toISOString(),
        task
    }
}

/** `<key or uuid>-task.json`, with anything but letters, digits, dot, dash and underscore made a dash. */
export function taskExportFileName (task: any): string {
    const base = String(task?.key || task?.uuid || 'task').replace(/[^A-Za-z0-9._-]+/g, '-')
    return `${base}-task.json`
}

/** Saves `doc` as a pretty-printed JSON file named `fileName`, through a temporary object URL. */
export function downloadJson (fileName: string, doc: unknown): void {
    const blob = new Blob([JSON.stringify(doc, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = fileName
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
}
