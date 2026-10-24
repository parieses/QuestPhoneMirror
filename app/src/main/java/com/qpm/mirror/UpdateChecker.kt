package com.qpm.mirror

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查更新：查询 GitHub Releases 最新版本，与本地版本比较，挑出可下载的 APK 直链。
 *
 * 数据源：https://api.github.com/repos/parieses/QuestPhoneMirror/releases/latest
 * 未认证限流 60 次/小时/IP，因此只做「用户手动点检查」+「12 小时一次自动检查」，
 * 不做启动即查。CI 每次打 v* tag 会把 APK 挂到对应 Release，本机版本号来自 BuildConfig。
 *
 * 下载走多源候选链（见 [buildDownloadCandidates]）：版本检测是轻量 JSON 仍走 GitHub API，
 * 3MB 级的 APK 下载优先走 Gitee 镜像 / 公共代理，国内提速明显。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    private const val REPO_OWNER = "parieses"
    private const val REPO_NAME = "QuestPhoneMirror"
    private val LATEST_RELEASE_URL =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    private val GITHUB_RELEASES_BASE =
        "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download"

    /** 下载到本机的固定文件名，重复下载直接覆盖。 */
    const val APK_FILE_NAME = "qpm-update.apk"

    /** Release Notes 截断长度，对话框里放太长没法看。 */
    private const val NOTES_MAX_LEN = 800

    data class Release(
        /** 远端版本号，形如 v0.10（保留原始前缀，展示用）。 */
        val versionName: String,
        /** Release Notes 原文（已截断）。 */
        val notes: String,
        /** APK 资产下载直链（browser_download_url，免 token），即 GitHub 官方链。 */
        val apkUrl: String,
        /** Release 页面，供「手动下载」兜底。 */
        val pageUrl: String,
        /**
         * 下载候选链：镜像/代理优先，GitHub 官方直链兜底。
         * 国内直连 GitHub 慢，公共代理随时可能挂，App 侧按顺序探测选第一个可用的。
         */
        val downloadUrls: List<String>
    )

    sealed class Result {
        /** 发现新版本。 */
        class Available(val release: Release) : Result()

        /** 本地已是最新。 */
        object UpToDate : Result()

        /** 检查失败，[message] 可直接展示给用户。 */
        class Failed(val message: String) : Result()
    }

    /**
     * 同步执行网络查询，调用方需放到子线程。
     * @param context 用于取本地化文案（检查失败原因直接展示给用户）
     * @param currentVersionName 本地版本号（BuildConfig.VERSION_NAME，如 v0.9 / 0.9）
     */
    fun check(context: Context, currentVersionName: String): Result {
        val body = try {
            httpGet(context, LATEST_RELEASE_URL, currentVersionName)
        } catch (e: GithubException) {
            // 404（无 Release / 仓库改私有）、403/429（限流）等可读原因
            return Result.Failed(e.userMessage)
        } catch (e: IOException) {
            Log.w(TAG, "network failed: ${e.message}")
            return Result.Failed(context.getString(R.string.update_net_fail))
        }

        return try {
            val json = JSONObject(body)
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) {
                // latest 接口本就不会返回草稿/预发布，双保险防手滑发布
                return Result.UpToDate
            }
            val tag = json.optString("tag_name")
                .ifBlank { return Result.Failed(context.getString(R.string.update_no_tag)) }
            val pageUrl = json.optString("html_url")
                .ifBlank { "https://github.com/$REPO_OWNER/$REPO_NAME/releases" }
            val apkUrl = pickApkAsset(json.optJSONArray("assets"))
                ?: return Result.Failed(context.getString(R.string.update_no_apk))

            if (!isNewer(tag, currentVersionName)) return Result.UpToDate

            Result.Available(
                Release(
                    versionName = tag,
                    notes = truncateNotes(context, json.optString("body").ifBlank { context.getString(R.string.update_no_notes) }),
                    apkUrl = apkUrl,
                    pageUrl = pageUrl,
                    downloadUrls = buildDownloadCandidates(apkUrl, tag)
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "parse failed: ${e.message}")
            Result.Failed(context.getString(R.string.update_parse_fail))
        }
    }

    /**
     * 构造下载候选链（按优先级）：
     * 1. Gitee 镜像仓库——国内直连最快；仓库未配置镜像时探测失败，自动跳过
     * 2. 公共加速代理——个人维护、随时可能挂，所以只做候选不做依赖
     * 3. GitHub 官方直链——保底，与 Release API 返回的 browser_download_url 一致
     *
     * 版本检测仍走 GitHub API（轻量 JSON），重活（APK 下载）才走镜像。
     */
    private fun buildDownloadCandidates(apkUrl: String, tag: String): List<String> {
        val fileName = apkUrl.substringAfterLast('/')
        val ghPath = "$GITHUB_RELEASES_BASE/$tag/$fileName"
        val chain = LinkedHashSet<String>()
        chain += "https://gitee.com/$REPO_OWNER/$REPO_NAME/releases/download/$tag/$fileName"
        chain += "https://gh-proxy.com$ghPath"
        chain += apkUrl
        return chain.toList()
    }

    /**
     * 多段数字版本比较：v1.2.3 与 v0.10 比逐段整数大小（10 > 9，不做字符串比较）。
     * 无法解析出数字段时视为相同，返回 false（宁可漏报不可误报）。
     */
    fun isNewer(remote: String, local: String): Boolean {
        val r = numericSegments(remote)
        val l = numericSegments(local)
        if (r.isEmpty() || l.isEmpty()) return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    /** 取版本号开头的数字段：v1.2.3 -> [1,2,3]；1.2-beta -> [1,2]；非数字开头 -> []。 */
    private fun numericSegments(version: String): List<Int> {
        val clean = version.trim().removePrefix("v").removePrefix("V")
        val out = ArrayList<Int>()
        for (seg in clean.split('.')) {
            val n = seg.takeWhile { it.isDigit() }.toIntOrNull() ?: break
            out += n
        }
        return out
    }

    /**
     * 从资产里挑 APK：优先 release 签名包（debug 包装到澎湃OS/MIUI 上会被拒授无障碍等
     * 敏感权限），找不到再退回任意 .apk。
     */
    private fun pickApkAsset(assets: org.json.JSONArray?): String? {
        if (assets == null) return null
        var anyApk: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (!url.startsWith("https://")) continue
            if (name.contains("release", ignoreCase = true)) return url
            if (anyApk == null) anyApk = url
        }
        return anyApk
    }

    private fun truncateNotes(context: Context, notes: String): String =
        if (notes.length <= NOTES_MAX_LEN) notes else notes.take(NOTES_MAX_LEN) + context.getString(R.string.update_notes_truncated)

    /** 带可读原因的 GitHub 接口异常，[userMessage] 直接展示给用户。 */
    private class GithubException(val userMessage: String) : IOException(userMessage)

    /** @throws GithubException 404 / 限流等可读原因；@throws IOException 网络层错误 */
    private fun httpGet(context: Context, url: String, currentVersion: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            // GitHub 强制要求 User-Agent，否则 403；Accept 指定 API 版本
            setRequestProperty("User-Agent", "QPM-Android/$currentVersion")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = conn.responseCode
            return when {
                code == HttpURLConnection.HTTP_NOT_FOUND ->
                    throw GithubException(context.getString(R.string.update_no_release))
                code == HttpURLConnection.HTTP_FORBIDDEN || code == 429 ->
                    throw GithubException(context.getString(R.string.update_ratelimit))
                code !in 200..299 ->
                    throw GithubException(context.getString(R.string.update_http_error, code))
                else -> conn.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            conn.disconnect()
        }
    }
}
