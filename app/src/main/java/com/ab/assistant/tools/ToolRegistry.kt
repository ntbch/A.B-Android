package com.ab.assistant.tools

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.AlarmClock
import android.telephony.SmsManager
import android.view.KeyEvent
import com.ab.assistant.contacts.ContactMatch
import com.ab.assistant.contacts.ContactLookup
import com.ab.assistant.notifications.AbNotificationListenerService
import com.ab.assistant.notifications.NotificationStore
import com.ab.assistant.web.BingRssSearchClient
import com.ab.assistant.web.WebSearchClient
import com.ab.assistant.web.WebSearchResponse
import java.text.Normalizer
import java.util.Locale

class ToolRegistry(
    private val context: Context,
    private val flashlight: FlashlightController = FlashlightController(context),
    private val contactLookup: ContactLookup = ContactLookup(context),
    private val webSearchClient: WebSearchClient = BingRssSearchClient(),
) : ToolExecutor {
    override fun requiredPermission(command: ToolCommand): String? = when (command) {
        ToolCommand.FlashlightOn, ToolCommand.FlashlightOff -> Manifest.permission.CAMERA
        is ToolCommand.FindContact -> Manifest.permission.READ_CONTACTS
        is ToolCommand.SendSms -> Manifest.permission.SEND_SMS
        is ToolCommand.DialContact -> if (isPhoneNumber(command.recipient)) null else Manifest.permission.READ_CONTACTS
        else -> null
    }

    override fun requiredPermissions(command: ToolCommand): List<String> = when (command) {
        is ToolCommand.SendSms -> buildList {
            if (!isPhoneNumber(command.recipient)) add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.SEND_SMS)
        }
        else -> super.requiredPermissions(command)
    }

    override fun permissionMessage(command: ToolCommand, permission: String): String = when (command) {
        ToolCommand.FlashlightOn, ToolCommand.FlashlightOff -> "Cần cấp quyền Camera để điều khiển đèn pin."
        is ToolCommand.FindContact -> "Cần cấp quyền Danh bạ để tìm liên hệ."
        is ToolCommand.DialContact -> "Cần cấp quyền Danh bạ để tìm số điện thoại."
        is ToolCommand.SendSms -> if (permission == Manifest.permission.READ_CONTACTS) {
            "Cần cấp quyền Danh bạ để tìm người nhận."
        } else {
            "Cần cấp quyền SMS để gửi tin nhắn sau khi bạn xác nhận."
        }
        else -> "Cần cấp quyền để thực hiện yêu cầu này."
    }

    override fun isAvailable(command: ToolCommand): Boolean = when (command) {
        ToolCommand.FlashlightOn, ToolCommand.FlashlightOff -> flashlight.isAvailable()
        is ToolCommand.OpenApp -> launchableActivities().isNotEmpty()
        is ToolCommand.SetVolume, is ToolCommand.Media -> audioManager() != null
        is ToolCommand.SetTimer -> hasHandler(Intent(AlarmClock.ACTION_SET_TIMER))
        is ToolCommand.SetAlarm -> hasHandler(Intent(AlarmClock.ACTION_SET_ALARM))
        is ToolCommand.ReadNotifications -> AbNotificationListenerService.isAccessEnabled(context)
        is ToolCommand.FindContact -> true
        is ToolCommand.WebSearch -> isNetworkAvailable()
        is ToolCommand.SendSms -> smsManager() != null
        is ToolCommand.DialContact -> hasHandler(Intent(Intent.ACTION_DIAL))
    }

    override fun unavailableMessage(command: ToolCommand): String = when (command) {
        ToolCommand.FlashlightOn, ToolCommand.FlashlightOff -> "Thiết bị không có đèn flash khả dụng."
        is ToolCommand.OpenApp -> "Không tìm thấy ứng dụng có thể mở."
        is ToolCommand.SetVolume, is ToolCommand.Media -> "Không có dịch vụ âm thanh khả dụng."
        is ToolCommand.SetTimer -> "Không tìm thấy ứng dụng Đồng hồ hỗ trợ hẹn giờ."
        is ToolCommand.SetAlarm -> "Không tìm thấy ứng dụng Đồng hồ hỗ trợ báo thức."
        is ToolCommand.ReadNotifications -> "Chưa bật quyền Truy cập thông báo. Hãy mở Khả năng thiết bị để bật quyền này."
        is ToolCommand.FindContact -> "Không thể truy cập Danh bạ."
        is ToolCommand.WebSearch -> "Không có kết nối mạng để tìm kiếm."
        is ToolCommand.SendSms -> "Thiết bị không hỗ trợ gửi SMS."
        is ToolCommand.DialContact -> "Thiết bị không có ứng dụng quay số khả dụng."
    }

    override fun execute(command: ToolCommand): ToolExecutionResult = when (command) {
        ToolCommand.FlashlightOn, ToolCommand.FlashlightOff -> ToolExecutionResult(flashlight.execute(command))
        is ToolCommand.OpenApp -> openApp(command.appName)
        is ToolCommand.SetVolume -> setVolume(command)
        is ToolCommand.Media -> media(command.action)
        is ToolCommand.SetTimer -> setTimer(command.durationMinutes)
        is ToolCommand.SetAlarm -> setAlarm(command)
        is ToolCommand.ReadNotifications -> readNotifications(command.filter)
        is ToolCommand.FindContact -> findContact(command.name)
        is ToolCommand.WebSearch -> webSearch(command.query)
        is ToolCommand.SendSms -> sendSms(command)
        is ToolCommand.DialContact -> dialContact(command.recipient)
    }

    override fun requiresConfirmation(command: ToolCommand): Boolean = command is ToolCommand.SendSms

    override fun confirmationMessage(command: ToolCommand): String = when (command) {
        is ToolCommand.SendSms -> "Xác nhận gửi SMS tới “${command.recipient}”:\n${command.message}"
        else -> super.confirmationMessage(command)
    }

    fun capabilityStatus(modelBackend: String): String = buildString {
        appendLine("Trạng thái Phase 4")
        appendLine("Mô hình: $modelBackend")
        appendLine("Đèn pin: ${if (flashlight.isAvailable()) "sẵn sàng (cần quyền Camera)" else "không khả dụng"}")
        appendLine("Mở ứng dụng: ${launchableActivities().size} ứng dụng launcher nhìn thấy")
        appendLine("Âm lượng: ${if (audioManager() != null) "sẵn sàng" else "không khả dụng"}")
        appendLine("Media: ${if (audioManager() != null) "gửi lệnh tới trình phát hiện tại" else "không khả dụng"}")
        appendLine("Hẹn giờ: ${if (hasHandler(Intent(AlarmClock.ACTION_SET_TIMER))) "sẵn sàng" else "không có app hỗ trợ"}")
        appendLine("Báo thức: ${if (hasHandler(Intent(AlarmClock.ACTION_SET_ALARM))) "sẵn sàng" else "không có app hỗ trợ"}")
        appendLine("Thông báo: ${if (AbNotificationListenerService.isAccessEnabled(context)) "đã cấp quyền" else "cần bật Notification access"}")
        appendLine("Danh bạ: ${if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) "đã cấp quyền" else "cần cấp quyền khi dùng"}")
        appendLine("SMS: ${if (smsManager() != null) "sẵn sàng sau xác nhận và cấp quyền" else "không khả dụng"}")
        appendLine("Gọi: ${if (hasHandler(Intent(Intent.ACTION_DIAL))) "mở trình quay số sau khi tìm liên hệ" else "không khả dụng"}")
        append("Web: ${if (isNetworkAvailable()) "có mạng" else "ngoại tuyến"}")
    }

    private fun openApp(requestedName: String): ToolExecutionResult {
        val query = normalize(requestedName)
        if (query.isBlank()) return ToolExecutionResult("Tên ứng dụng không hợp lệ.")
        val activities = launchableActivities()
        val exactMatches = activities.filter { activity ->
            normalize(activity.label) == query || normalize(activity.packageName) == query
        }
        val candidates = if (exactMatches.isNotEmpty()) exactMatches else activities.filter { activity ->
            normalize(activity.label).contains(query) || normalize(activity.packageName).contains(query)
        }
        val target = candidates.distinctBy { it.packageName }.singleOrNull()
            ?: return if (candidates.isEmpty()) {
                ToolExecutionResult("Không tìm thấy ứng dụng “$requestedName”.")
            } else {
                ToolExecutionResult("Có nhiều ứng dụng khớp “$requestedName”; hãy nói rõ tên hơn.")
            }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(target.packageName)
            ?: Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(target.packageName, target.activityName))
        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            ToolExecutionResult("Đã mở ${target.label}.")
        } catch (_: Exception) {
            ToolExecutionResult("Không thể mở ${target.label}.")
        }
    }

    private fun setVolume(command: ToolCommand.SetVolume): ToolExecutionResult {
        val manager = audioManager() ?: return ToolExecutionResult(unavailableMessage(command))
        val stream = when (command.stream) {
            VolumeStream.MUSIC -> AudioManager.STREAM_MUSIC
            VolumeStream.RING -> AudioManager.STREAM_RING
            VolumeStream.ALARM -> AudioManager.STREAM_ALARM
            VolumeStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        }
        val maximum = manager.getStreamMaxVolume(stream)
        val target = (maximum * command.level / 100.0).toInt().coerceIn(0, maximum)
        return try {
            manager.setStreamVolume(stream, target, 0)
            ToolExecutionResult("Đã đặt âm lượng ${command.stream.name.lowercase(Locale.ROOT)} thành ${command.level}%.")
        } catch (_: SecurityException) {
            ToolExecutionResult("Không có quyền thay đổi âm lượng.")
        }
    }

    private fun media(action: MediaAction): ToolExecutionResult {
        val manager = audioManager() ?: return ToolExecutionResult(unavailableMessage(ToolCommand.Media(action)))
        val keyCode = when (action) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        return try {
            manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            ToolExecutionResult("Đã gửi lệnh ${action.name.lowercase(Locale.ROOT)} tới trình phát hiện tại.")
        } catch (_: SecurityException) {
            ToolExecutionResult("Hệ thống không cho phép gửi lệnh media này.")
        }
    }

    private fun setTimer(minutes: Int): ToolExecutionResult {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchClockIntent(intent, "Đã gửi yêu cầu đặt hẹn giờ $minutes phút.")
    }

    private fun setAlarm(command: ToolCommand.SetAlarm): ToolExecutionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, command.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, command.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, command.label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchClockIntent(intent, "Đã gửi yêu cầu đặt báo thức lúc %02d:%02d.".format(command.hour, command.minute))
    }

    private fun launchClockIntent(intent: Intent, success: String): ToolExecutionResult = try {
        if (!hasHandler(intent)) ToolExecutionResult("Ứng dụng Đồng hồ không hỗ trợ yêu cầu này.")
        else {
            context.startActivity(intent)
            ToolExecutionResult(success)
        }
    } catch (_: Exception) {
        ToolExecutionResult("Không thể mở ứng dụng Đồng hồ.")
    }

    private fun readNotifications(filter: String?): ToolExecutionResult {
        val notifications = NotificationStore.read(filter)
        if (notifications.isEmpty()) {
            val suffix = filter?.let { " khớp “$it”" }.orEmpty()
            return ToolExecutionResult("Không có thông báo đang hoạt động$suffix.", code = ToolResultCode.NOT_FOUND)
        }
        val summary = notifications.joinToString("\n") { item ->
            buildString {
                append("• ").append(item.appName)
                if (item.title.isNotBlank()) append(": ").append(item.title)
                if (item.text.isNotBlank()) append(" — ").append(item.text.take(MAX_NOTIFICATION_TEXT))
            }
        }
        return ToolExecutionResult("Thông báo gần đây:\n$summary")
    }

    private fun findContact(name: String): ToolExecutionResult = try {
        val matches = contactLookup.find(name)
        when (matches.size) {
            0 -> ToolExecutionResult("Không tìm thấy liên hệ “$name”.", code = ToolResultCode.NOT_FOUND)
            1 -> ToolExecutionResult("Đã tìm thấy ${matches.single().displayName}: ${matches.single().phoneNumber}.")
            else -> ToolExecutionResult(
                "Có ${matches.size} liên hệ khớp “$name”:\n" + matches.joinToString("\n") { "• ${it.displayName}: ${it.phoneNumber}" },
                code = ToolResultCode.AMBIGUOUS,
            )
        }
    } catch (_: SecurityException) {
        ToolExecutionResult("Cần cấp quyền Danh bạ để tìm liên hệ.", ok = false, code = ToolResultCode.PERMISSION_MISSING)
    }

    private fun webSearch(query: String): ToolExecutionResult = when (val response = webSearchClient.search(query)) {
        is WebSearchResponse.Success -> ToolExecutionResult(
            "Kết quả tìm kiếm cho “$query”\n" + response.entries.mapIndexed { index, entry ->
                "${index + 1}. ${entry.title}${entry.snippet.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()}"
            }.joinToString("\n"),
        )
        is WebSearchResponse.Failure -> ToolExecutionResult(
            response.message,
            ok = false,
            code = if (response.message.startsWith("Không có kết nối")) {
                ToolResultCode.NETWORK_UNAVAILABLE
            } else {
                ToolResultCode.NETWORK_ERROR
            },
        )
    }

    private fun sendSms(command: ToolCommand.SendSms): ToolExecutionResult = when (val recipient = resolveRecipient(command.recipient)) {
        is RecipientResolution.NotFound -> ToolExecutionResult(
            "Không tìm thấy liên hệ “${command.recipient}” để gửi SMS.",
            code = ToolResultCode.NOT_FOUND,
        )
        is RecipientResolution.Ambiguous -> ToolExecutionResult(
            "Có ${recipient.matches.size} liên hệ khớp “${command.recipient}”; hãy nói rõ tên hơn trước khi gửi: " +
                recipient.matches.joinToString { it.displayName },
            code = ToolResultCode.AMBIGUOUS,
        )
        is RecipientResolution.Single -> try {
            val manager = smsManager()
                ?: return ToolExecutionResult("Thiết bị không hỗ trợ gửi SMS.", ok = false, code = ToolResultCode.NOT_AVAILABLE)
            val parts = manager.divideMessage(command.message)
            if (parts.size > 1) {
                manager.sendMultipartTextMessage(recipient.match.phoneNumber, null, parts, null, null)
            } else {
                manager.sendTextMessage(recipient.match.phoneNumber, null, command.message, null, null)
            }
            ToolExecutionResult("Đã gửi SMS tới ${recipient.match.displayName}.")
        } catch (_: SecurityException) {
            ToolExecutionResult("Cần cấp quyền SMS để gửi tin nhắn.", ok = false, code = ToolResultCode.PERMISSION_MISSING)
        } catch (_: IllegalArgumentException) {
            ToolExecutionResult("Số điện thoại hoặc nội dung SMS không hợp lệ.", ok = false, code = ToolResultCode.NOT_AVAILABLE)
        }
    }

    private fun dialContact(requestedRecipient: String): ToolExecutionResult = when (val recipient = resolveRecipient(requestedRecipient)) {
        is RecipientResolution.NotFound -> ToolExecutionResult(
            "Không tìm thấy liên hệ “$requestedRecipient” để gọi.",
            code = ToolResultCode.NOT_FOUND,
        )
        is RecipientResolution.Ambiguous -> ToolExecutionResult(
            "Có ${recipient.matches.size} liên hệ khớp “$requestedRecipient”; hãy nói rõ tên hơn: " +
                recipient.matches.joinToString { it.displayName },
            code = ToolResultCode.AMBIGUOUS,
        )
        is RecipientResolution.Single -> try {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", recipient.match.phoneNumber, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            ToolExecutionResult("Đã mở trình quay số cho ${recipient.match.displayName}.")
        } catch (_: Exception) {
            ToolExecutionResult("Không thể mở trình quay số.", ok = false, code = ToolResultCode.NOT_AVAILABLE)
        }
    }

    private fun resolveRecipient(value: String): RecipientResolution {
        val phoneNumber = normalizePhoneNumber(value)
        if (phoneNumber != null) return RecipientResolution.Single(ContactMatch(phoneNumber, phoneNumber))
        val matches = contactLookup.find(value)
        return when (matches.size) {
            0 -> RecipientResolution.NotFound
            1 -> RecipientResolution.Single(matches.single())
            else -> RecipientResolution.Ambiguous(matches)
        }
    }

    private fun isPhoneNumber(value: String): Boolean = normalizePhoneNumber(value) != null

    private fun normalizePhoneNumber(value: String): String? {
        val compact = value.trim().replace(Regex("[\\s().-]"), "")
        return compact.takeIf { Regex("^\\+?\\d{6,20}$").matches(it) }
    }

    private fun audioManager(): AudioManager? = context.getSystemService(AudioManager::class.java)

    private fun smsManager(): SmsManager? = context.getSystemService(SmsManager::class.java)

    private fun isNetworkAvailable(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun hasHandler(intent: Intent): Boolean = intent.resolveActivity(context.packageManager) != null

    private fun launchableActivities(): List<LaunchableActivity> = context.packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_DEFAULT_ONLY)
        .map { info ->
            LaunchableActivity(
                label = info.loadLabel(context.packageManager).toString(),
                packageName = info.activityInfo.packageName,
                activityName = info.activityInfo.name,
            )
        }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .lowercase(Locale.ROOT)
        .replace('đ', 'd')
        .trim()

    private data class LaunchableActivity(val label: String, val packageName: String, val activityName: String)

    private sealed interface RecipientResolution {
        data object NotFound : RecipientResolution
        data class Single(val match: ContactMatch) : RecipientResolution
        data class Ambiguous(val matches: List<ContactMatch>) : RecipientResolution
    }

    private companion object {
        const val MAX_NOTIFICATION_TEXT = 180
    }
}
