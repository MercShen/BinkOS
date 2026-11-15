package com.github.gezimos.inkos.helper

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Process
import android.os.UserHandle
import android.util.Log
import androidx.core.content.edit
import com.github.gezimos.inkos.data.AppListItem
import com.github.gezimos.inkos.data.Prefs
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
data class PinnedShortcutData(
    val packageName: String,
    val shortcutId: String,
    val label: String,
    val userHandle: String
)
object PinnedShortcutUtility {
    private const val TAG = "PinnedShortcutUtility"
    private const val PINNED_SHORTCUTS_KEY = "PINNED_SHORTCUTS"
    
    private val gson = Gson()
    fun getPinnedShortcuts(context: Context): Set<PinnedShortcutData> {
        val prefs = Prefs(context)
        val json = prefs.sharedPrefs.getString(PINNED_SHORTCUTS_KEY, null) ?: return emptySet()
        return try {
            val type = object : TypeToken<Set<PinnedShortcutData>>() {}.type
            gson.fromJson(json, type) ?: emptySet()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing pinned shortcuts", e)
            emptySet()
        }
    }
    private fun savePinnedShortcuts(context: Context, shortcuts: Set<PinnedShortcutData>) {
        val prefs = Prefs(context)
        val json = gson.toJson(shortcuts)
        prefs.sharedPrefs.edit { putString(PINNED_SHORTCUTS_KEY, json) }
    }
    fun addPinnedShortcut(
        context: Context,
        packageName: String,
        shortcutId: String,
        label: String,
        userHandle: UserHandle = Process.myUserHandle()
    ): Boolean {
        val shortcuts = getPinnedShortcuts(context).toMutableSet()
        val newShortcut = PinnedShortcutData(
            packageName = packageName,
            shortcutId = shortcutId,
            label = label,
            userHandle = userHandle.toString()
        )
        
        // Check if already exists
        if (shortcuts.any { it.packageName == packageName && it.shortcutId == shortcutId && it.userHandle == userHandle.toString() }) {
            Log.d(TAG, "Shortcut already pinned: $packageName/$shortcutId")
            return false
        }
        
        shortcuts.add(newShortcut)
        savePinnedShortcuts(context, shortcuts)
        Log.d(TAG, "Pinned shortcut added: $packageName/$shortcutId")

        val prefs = Prefs(context)
        if (prefs.hasSelectedAppShortcutsBeenSet()) {
            val key = "$packageName|$shortcutId|${userHandle.toString()}"
            prefs.selectedAppShortcuts = (prefs.selectedAppShortcuts.toMutableSet().apply { add(key) })
        }
        prefs.notifyPinnedShortcutsChanged()

        return true
    }
    fun addPinnedShortcut(context: Context, shortcutInfo: ShortcutInfo): Boolean {
        val label = shortcutInfo.shortLabel?.toString()
            ?: shortcutInfo.longLabel?.toString()
            ?: shortcutInfo.id
        
        return addPinnedShortcut(
            context = context,
            packageName = shortcutInfo.`package`,
            shortcutId = shortcutInfo.id,
            label = label,
            userHandle = shortcutInfo.userHandle
        )
    }
    fun removePinnedShortcut(
        context: Context,
        packageName: String,
        shortcutId: String,
        userHandle: UserHandle = Process.myUserHandle()
    ): Boolean {
        val shortcuts = getPinnedShortcuts(context).toMutableSet()
        val userHandleStr = userHandle.toString()
        
        val removed = shortcuts.removeIf { 
            it.packageName == packageName && 
            it.shortcutId == shortcutId && 
            it.userHandle == userHandleStr 
        }
        
        if (removed) {
            savePinnedShortcuts(context, shortcuts)
            Log.d(TAG, "Pinned shortcut removed from our storage: $packageName/$shortcutId")
            
            val prefs = Prefs(context)
            if (prefs.hasSelectedAppShortcutsBeenSet()) {
                val key = "$packageName|$shortcutId|$userHandleStr"
                val selected = prefs.selectedAppShortcuts.toMutableSet()
                selected.remove(key)
                prefs.selectedAppShortcuts = selected
            }
            // Also remove any custom alias for this shortcut
            prefs.removeAppAlias("${packageName}_$shortcutId")
            
            unpinFromSystem(context, packageName, shortcutId, userHandle)
            
            // Notify preference change to refresh app list
            prefs.notifyPinnedShortcutsChanged()
        }
        
        return removed
    }
    private fun unpinFromSystem(
        context: Context,
        packageName: String,
        shortcutId: String,
        userHandle: UserHandle
    ) {
        try {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            if (launcherApps == null || !launcherApps.hasShortcutHostPermission()) {
                Log.d(TAG, "No shortcut host permission, cannot unpin from system")
                return
            }
            
            // Get current pinned shortcuts for this package
            val query = LauncherApps.ShortcutQuery().apply {
                setPackage(packageName)
                setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            }
            
            val currentPinned = launcherApps.getShortcuts(query, userHandle)
            val remainingIds = currentPinned
                ?.filter { it.id != shortcutId }
                ?.map { it.id }
                ?: emptyList()
            
            launcherApps.pinShortcuts(packageName, remainingIds, userHandle)
            Log.d(TAG, "Unpinned shortcut from system: $packageName/$shortcutId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unpin from system: $packageName/$shortcutId", e)
        }
    }
    fun isPinned(
        context: Context,
        packageName: String,
        shortcutId: String,
        userHandle: UserHandle = Process.myUserHandle()
    ): Boolean {
        val shortcuts = getPinnedShortcuts(context)
        val userHandleStr = userHandle.toString()
        return shortcuts.any { 
            it.packageName == packageName && 
            it.shortcutId == shortcutId && 
            it.userHandle == userHandleStr 
        }
    }
    fun isPinned(context: Context, app: AppListItem): Boolean {
        if (app.shortcutId == null) return false
        return isPinned(context, app.activityPackage, app.shortcutId, app.user)
    }
    fun getPinnedShortcutsAsAppItems(context: Context): List<AppListItem> {
        val prefs = Prefs(context)
        val pinnedShortcuts = getPinnedShortcuts(context)
        val validShortcuts = mutableListOf<AppListItem>()
        val invalidShortcuts = mutableListOf<PinnedShortcutData>()
        
        for (shortcut in pinnedShortcuts) {
            val userHandle = getUserHandleFromString(context, shortcut.userHandle)
            
            val appListItem = getShortcutAsAppListItem(
                context = context,
                packageName = shortcut.packageName,
                shortcutId = shortcut.shortcutId,
                userHandle = userHandle
            )
            
            if (appListItem != null) {
                // Apply any custom label
                val customLabel = prefs.getAppAlias("app_alias_${shortcut.packageName}_${shortcut.shortcutId}")
                if (customLabel.isNotEmpty()) {
                    appListItem.customLabel = customLabel
                }
                validShortcuts.add(appListItem)
            } else {
                // Shortcut no longer exists, mark for removal
                Log.d(TAG, "Pinned shortcut no longer exists: ${shortcut.packageName}/${shortcut.shortcutId}")
                invalidShortcuts.add(shortcut)
            }
        }
        
        // 只过滤展示，不改用户数据：检索为空/未授权/locked profile 都不等于“永久失效”，
        // 无效条目保留在存储里，等用户显式 unpin 再删。
        if (invalidShortcuts.isNotEmpty()) {
            Log.d(TAG, "Filtered ${invalidShortcuts.size} unresolvable pinned shortcuts from UI (kept in storage)")
        }
        
        return validShortcuts
    }
    /**
     * Accept a shortcut pin request.
     * This is called when another app requests to pin a shortcut to this launcher.
     */
    fun acceptPinRequest(context: Context, request: LauncherApps.PinItemRequest): Boolean {
        val shortcutInfo = request.shortcutInfo
        val alreadyStored = if (shortcutInfo != null) {
            isPinned(context, shortcutInfo.`package`, shortcutInfo.id, shortcutInfo.userHandle)
        } else false
        val decision = PinnedShortcutPolicy.acceptDecision(
            isValid = request.isValid,
            hasShortcutInfo = shortcutInfo != null,
            alreadyStored = alreadyStored
        )
        if (decision == PinnedShortcutPolicy.AcceptDecision.REJECT) {
            return false
        }

        // 先让系统 accept（且只 accept 一次）；失败则绝不落本地数据
        val accepted = try {
            request.accept()
        } catch (e: Exception) {
            Log.e(TAG, "Pin request accept failed", e)
            false
        }
        if (!accepted) {
            return false
        }
        if (decision == PinnedShortcutPolicy.AcceptDecision.ACCEPT_DUPLICATE || shortcutInfo == null) {
            return true
        }

        try {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            if (launcherApps != null) {
                val density = context.resources.displayMetrics.densityDpi
                val drawable = launcherApps.getShortcutIconDrawable(shortcutInfo, density)
                if (drawable != null) {
                    IconUtility.savePinnedShortcutIcon(
                        context, shortcutInfo.`package`, shortcutInfo.id, drawable
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not pre-save shortcut icon for ${shortcutInfo.id}", e)
        }

        // 系统已 accept，才写入本地存储与选中集合
        return addPinnedShortcut(context, shortcutInfo)
    }
}
