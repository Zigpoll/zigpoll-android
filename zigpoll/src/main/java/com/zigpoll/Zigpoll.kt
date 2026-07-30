package com.zigpoll

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.fragment.app.FragmentActivity

/**
 * Zigpoll mobile SDK.
 *
 * Surveys are configured in the Zigpoll dashboard with delivery set to
 * **API**, then presented on demand:
 *
 * ```kotlin
 * Zigpoll.configure(context, "YOUR_ACCOUNT_ID")
 * Zigpoll.identify("user_123", mapOf("email" to "jane@example.com"))
 * Zigpoll.trigger("SURVEY_ID", activity)
 * ```
 *
 * Call all methods on the main thread.
 */
object Zigpoll {

    private const val TAG = "Zigpoll"
    internal const val SDK_VERSION = "0.1.0"

    /** Called when the survey has loaded and is visible. */
    @JvmStatic var onLoad: (() -> Unit)? = null

    /** Called once when the respondent completes the survey. */
    @JvmStatic var onComplete: ((responses: List<Map<String, Any?>>) -> Unit)? = null

    /** Called when the survey sheet is dismissed, with responses so far. */
    @JvmStatic var onClose: ((responses: List<Map<String, Any?>>) -> Unit)? = null

    /** Called when the survey fails to load. */
    @JvmStatic var onError: ((error: String) -> Unit)? = null

    internal var accountId: String? = null
    internal var preview = false
    internal var baseUrl = "https://survey.zigpoll.com"
    internal var identifiedId: String? = null
    internal var identifiedMetadata: Map<String, String> = emptyMap()
    internal var customMetadata: MutableMap<String, String> = mutableMapOf()
    internal var appVersion: String? = null
    private var currentFragment: ZigpollSurveyFragment? = null

    /**
     * Configure the SDK. Call once, early in the app lifecycle.
     *
     * @param accountId Your Zigpoll account id (Dashboard -> Installation).
     * @param preview When true, surveys open normally but responses are not
     *   billed or counted in analytics. Use in development/QA builds.
     * @param baseUrl Override the survey host (development only).
     */
    @JvmStatic
    @JvmOverloads
    fun configure(context: Context, accountId: String, preview: Boolean = false, baseUrl: String? = null) {
        this.accountId = accountId
        this.preview = preview
        if (!baseUrl.isNullOrEmpty()) {
            this.baseUrl = baseUrl.trimEnd('/')
        }
        ZigpollStorage.initialize(context.applicationContext)
        identifiedId = ZigpollStorage.loadIdentifiedId()
        appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Associate the current app user with survey responses. Identified users
     * keep survey state across sessions and devices.
     */
    @JvmStatic
    @JvmOverloads
    fun identify(id: String, metadata: Map<String, String> = emptyMap()) {
        identifiedId = id
        identifiedMetadata = metadata
        ZigpollStorage.saveIdentifiedId(id)
    }

    /** Attach metadata to every subsequent response. Merged over prior values. */
    @JvmStatic
    fun setMetadata(metadata: Map<String, String>) {
        customMetadata.putAll(metadata)
    }

    /** Clear the identified user; an anonymous device-persistent id is used. */
    @JvmStatic
    fun logout() {
        identifiedId = null
        identifiedMetadata = emptyMap()
        customMetadata.clear()
        ZigpollStorage.clearIdentifiedId()
    }

    /**
     * Present a survey in a bottom sheet.
     *
     * @return false when the SDK is unconfigured or a survey is already showing.
     */
    @JvmStatic
    fun trigger(pollId: String, activity: FragmentActivity): Boolean {
        val account = accountId
        if (account.isNullOrEmpty()) {
            Log.w(TAG, "trigger() called before configure()")
            return false
        }
        if (currentFragment?.isAdded == true) {
            Log.w(TAG, "a survey is already presented")
            return false
        }

        val fragment = ZigpollSurveyFragment.newInstance(surveyUrl(account, pollId))
        currentFragment = fragment
        fragment.show(activity.supportFragmentManager, "zigpoll-survey")
        return true
    }

    /** Programmatically dismiss the presented survey, if any. */
    @JvmStatic
    fun dismiss() {
        currentFragment?.closeSurvey()
    }

    internal fun surveyDismissed() {
        currentFragment = null
    }

    /** The participant id: the identified user id or a persistent anonymous id. */
    internal val participantId: String
        get() = identifiedId?.takeIf { it.isNotEmpty() } ?: ZigpollStorage.anonymousId()

    /**
     * `https://survey.zigpoll.com/sdk/<account>/<poll>?uid=...[&preview=1]#<metadata>`
     *
     * uid rides the query string (the server uses it to restore prior
     * responses); metadata rides the URL fragment, which never reaches the
     * server or its logs. Stable per poll + user so deduplication works.
     */
    internal fun surveyUrl(accountId: String, pollId: String): String {
        val builder = Uri.parse(baseUrl).buildUpon()
            .appendPath("sdk")
            .appendPath(accountId)
            .appendPath(pollId)
            .appendQueryParameter("uid", participantId)
        if (preview) {
            builder.appendQueryParameter("preview", "1")
        }

        val metadata = sortedMapOf<String, String>()
        metadata["source"] = "mobile-sdk"
        metadata["platform"] = "android"
        metadata["sdk_version"] = SDK_VERSION
        appVersion?.let { metadata["app_version"] = it }
        metadata.putAll(identifiedMetadata)
        metadata.putAll(customMetadata)

        val fragment = metadata.entries.joinToString("&") { (key, value) ->
            Uri.encode(key) + "=" + Uri.encode(value)
        }
        return builder.build().toString() + "#" + fragment
    }
}
