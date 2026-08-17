package cn.hllcloud.youji.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 权限管理工具
 *
 * 统一处理相机、图片访问、网络权限，兼容多Android版本与国产ROM（MIUI/EMUI/MagicOS/
 * ColorOS/OriginOS/OneUI等）的差异：
 *
 * 1. 区分Android 13+（READ_MEDIA_IMAGES）与Android 12及以下（READ_EXTERNAL_STORAGE）。
 * 2. 国产ROM在系统权限弹窗上常常带"仅在使用中允许/仅这一次允许"等额外选项，需要兼容
 *    shouldShowRequestPermissionRationale 返回false但实际已被永久拒绝的情况。
 * 3. 当用户永久拒绝时，引导跳转到应用详情页让用户手动开启。
 */
object PermissionUtil {

    /**
     * 拍照所需权限列表
     */
    fun getCameraPermissions(): List<String> {
        return listOf(Manifest.permission.CAMERA)
    }

    /**
     * 获取读取图片所需权限列表（按版本兼容）
     * - Android 14+ (API 34+): READ_MEDIA_IMAGES + READ_MEDIA_VISUAL_USER_SELECTED
     * - Android 13 (API 33): READ_MEDIA_IMAGES
     * - Android 12及以下: READ_EXTERNAL_STORAGE
     *
     * 注意：READ_MEDIA_VISUAL_USER_SELECTED 是部分国产ROM（如MIUI/ColorOS）在用户选择
     * "仅允许访问部分照片"时授予的权限，必须在Manifest中声明并按需请求。
     */
    fun getReadImagePermissions(): List<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                listOf(Manifest.permission.READ_MEDIA_IMAGES)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                listOf(Manifest.permission.READ_MEDIA_IMAGES)
            }
            else -> {
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    /**
     * 获取全部需要的权限（相机+读图）
     */
    fun getAllRequiredPermissions(): List<String> {
        return getCameraPermissions() + getReadImagePermissions()
    }

    /**
     * 检查单个权限是否已授予
     */
    fun isPermissionGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查权限列表是否全部已授予
     */
    fun areAllPermissionsGranted(context: Context, permissions: List<String>): Boolean {
        return permissions.all { isPermissionGranted(context, it) }
    }

    /**
     * 检查相机权限
     */
    fun hasCameraPermission(context: Context): Boolean {
        return isPermissionGranted(context, Manifest.permission.CAMERA)
    }

    /**
     * 检查图片读取权限
     * 国产ROM兼容：READ_MEDIA_VISUAL_USER_SELECTED（部分照片访问）也算作已授权
     */
    fun hasReadImagePermission(context: Context): Boolean {
        // 主权限已授权
        if (areAllPermissionsGranted(context, getReadImagePermissions())) return true
        // Android 14+ 部分国产ROM授予了USER_SELECTED也算通过
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return isPermissionGranted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
        return false
    }

    /**
     * 检查是否需要向用户解释为何需要此权限（用户曾拒绝过但未勾选"不再询问"）。
     *
     * 国产ROM兼容：部分系统会直接返回false，因此返回false并不代表用户首次看到弹窗。
     */
    fun shouldShowRationale(activity: Activity, permissions: List<String>): Boolean {
        return permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
    }

    /**
     * 判断权限是否被永久拒绝（即不能再通过系统弹窗请求，需要引导用户去设置开启）。
     *
     * 国产ROM兼容：用户首次未请求时也返回true（此时不应跳转设置），调用方需配合
     * 一个"已请求过"标记使用，或直接根据用户操作结果再次判断。
     */
    fun isPermissionPermanentlyDenied(activity: Activity, permissions: List<String>): Boolean {
        return permissions.any { permission ->
            !isPermissionGranted(activity, permission) &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }
    }

    /**
     * 跳转到当前应用的系统设置详情页，让用户手动开启权限。
     * 用于国产ROM永久拒绝场景。
     */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // 兜底：直接打开设置主页
            try {
                val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (_: Exception) {
            }
        }
    }
}
