package app.floatphone.shell

import android.content.Context
import android.net.Uri

/**
 * 站点地址配置。
 *
 * 构建时可通过 SHELL_SITE_URL 注入默认地址；没有注入时，首次启动会让用户
 * 在 App 内填写，并保存在壳自己的 SharedPreferences 里。这样 CI 不需要
 * 仓库变量也能产出可用 APK。
 */
object ShellConfig {

    private const val PREFS = "float_shell"
    private const val KEY_SITE_URL = "site_url"

    /** 只接受 https://host[/path]，去掉末尾斜杠；空值或非法地址返回 null。 */
    fun normalize(raw: String?): String? {
        val value = raw?.trim()?.trimEnd('/') ?: return null
        if (value.isEmpty()) return null
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank()) return null
        return value
    }

    /** 当前生效地址：用户填写优先，其次构建时注入。 */
    fun resolve(context: Context): String? {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SITE_URL, null)
        return normalize(saved) ?: normalize(BuildConfig.SITE_URL)
    }

    /** 校验并保存；成功返回规范化后的地址，失败返回 null。 */
    fun save(context: Context, raw: String): String? {
        val value = normalize(raw) ?: return null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SITE_URL, value)
            .apply()
        return value
    }

    /** 仅清除用户填写值，回退到构建时地址（如有）。 */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SITE_URL)
            .apply()
    }
}
