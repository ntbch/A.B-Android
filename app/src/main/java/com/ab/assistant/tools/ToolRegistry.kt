package com.ab.assistant.tools

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.telephony.SmsManager
import android.view.KeyEvent
import com.ab.assistant.contacts.ContactMatch
import com.ab.assistant.contacts.ContactLookup
import com.ab.assistant.accessibility.AbAccessibilityService
import com.ab.assistant.accessibility.SemanticRef
import com.ab.assistant.accessibility.UiAction
import com.ab.assistant.notifications.AbNotificationListenerService
import com.ab.assistant.notifications.NotificationStore
import com.ab.assistant.observability.AbLog
import com.ab.assistant.observability.AbLogCategory
import com.ab.assistant.web.BingRssSearchClient
import com.ab.assistant.web.WebSearchClient
import com.ab.assistant.web.WebSearchResponse
import com.ab.assistant.state.Capability
import com.ab.assistant.state.CapabilityCoordinator
import java.text.Normalizer
import java.util.Locale

class ToolRegistry(
    private val context: Context,
    private val flashlight: FlashlightController = FlashlightController(context),
    private val contactLookup: ContactLookup = ContactLookup(context),
    private val webSearchClient: WebSearchClient = BingRssSearchClient(),
    private val capabilityCoordinator: CapabilityCoordinator? = null,
) : ToolExecutor {
    /**
     * Publishes real platform availability to A.B's one capability authority.
     * Android runtime permissions remain a separate, requestable authorization gate.
     */
    fun refreshCapabilityStates() {
        val coordinator = capabilityCoordinator ?: return
        coordinator.set(Capability.CAMERA, availabilityState(flashlight.isAvailable()))
        coordinator.set(Capability.LAUNCHER, availabilityState(launchableActivities().isNotEmpty()))
        coordinator.set(Capability.AUDIO, availabilityState(audioManager() != null))
        coordinator.set(
            Capability.ALARM,
            availabilityState(
                hasHandler(Intent(AlarmClock.ACTION_SET_TIMER)) ||
                    hasHandler(Intent(AlarmClock.ACTION_SET_ALARM)),
            ),
        )
        coordinator.set(Capability.BATTERY, availabilityState(context.getSystemService(BatteryManager::class.java) != null))
        coordinator.set(Capability.SMS, availabilityState(smsManager() != null))
        coordinator.set(Capability.DIALER, availabilityState(hasHandler(Intent(Intent.ACTION_DIAL))))
    }

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

    override fun isAvailable(command: ToolCommand): Boolean {
        refreshCapabilityStates()
        val capabilitiesReady = ToolSpecCatalog.forCommand(command).requiredCapabilities.all { capability ->
            capabilityCoordinator?.isReady(capability) ?: true
        }
        return capabilitiesReady && directAvailability(command)
    }

    private fun directAvailability(command: ToolCommand): Boolean = when (command) {
        ToolCommand.FlashlightOn, ToolCommand.FlashlightOff -> flashlight.isAvailable()
        is ToolCommand.OpenApp -> launchableActivities().isNotEmpty()
        is ToolCommand.SetVolume, is ToolCommand.AdjustVolume, is ToolCommand.Media -> audioManager() != null
        is ToolCommand.SetTimer -> hasHandler(Intent(AlarmClock.ACTION_SET_TIMER))
        is ToolCommand.SetAlarm -> hasHandler(Intent(AlarmClock.ACTION_SET_ALARM))
        is ToolCommand.ReadNotifications -> capabilityCoordinator?.isReady(Capability.NOTIFICATIONS)
            ?: AbNotificationListenerService.isAccessEnabled(context)
        is ToolCommand.FindContact -> true
        is ToolCommand.WebSearch -> capabilityCoordinator?.isReady(Capability.NETWORK) ?: isNetworkAvailable()
        is ToolCommand.SendSms -> smsManager() != null
        is ToolCommand.DialContact -> hasHandler(Intent(Intent.ACTION_DIAL))
        ToolCommand.ReadDeviceState -> context.getSystemService(BatteryManager::class.java) != null
        ToolCommand.GetUiSnapshot -> AbAccessibilityService.isConnected()
        is ToolCommand.TapUi, is ToolCommand.InputUiText, is ToolCommand.ScrollUi -> AbAccessibilityService.isConnected()
    }

    override fun unavailableMessage(command: ToolCommand): String = when (command) {
        is ToolCommand.AdjustVolume -> "Audio service unavailable."
        ToolCommand.ReadDeviceState -> "Device state unavailable."
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
        ToolCommand.GetUiSnapshot -> "Accessibility chưa sẵn sàng."
        is ToolCommand.TapUi, is ToolCommand.InputUiText, is ToolCommand.ScrollUi -> "Accessibility chưa sẵn sàng."
    }

    override fun execute(command: ToolCommand): ToolExecutionResult {
        AbLog.event(AbLogCategory.TOOL, "execute", mapOf("tool" to command.toToolCall().name))
        val result = when (command) {
        ToolCommand.FlashlightOn, ToolCommand.FlashlightOff -> flashlightResult(command)
        is ToolCommand.OpenApp -> openApp(command.appName)
        is ToolCommand.SetVolume -> setVolume(command)
        is ToolCommand.AdjustVolume -> adjustVolume(command)
        is ToolCommand.Media -> media(command.action)
        is ToolCommand.SetTimer -> setTimer(command.durationMinutes)
        is ToolCommand.SetAlarm -> setAlarm(command)
        is ToolCommand.ReadNotifications -> readNotifications(command.filter)
        is ToolCommand.FindContact -> findContact(command.name)
        is ToolCommand.WebSearch -> webSearch(command.query)
        is ToolCommand.SendSms -> sendSms(command)
        is ToolCommand.DialContact -> dialContact(command.recipient)
        ToolCommand.ReadDeviceState -> readDeviceState()
        ToolCommand.GetUiSnapshot -> readUiSnapshot()
        is ToolCommand.TapUi -> executeUiAction(UiAction.Tap(SemanticRef(command.snapshotId, command.ref)))
        is ToolCommand.InputUiText -> executeUiAction(UiAction.SetText(SemanticRef(command.snapshotId, command.ref), command.text))
        is ToolCommand.ScrollUi -> executeUiAction(
            UiAction.Scroll(SemanticRef(command.snapshotId, command.ref), command.direction == UiScrollDirection.FORWARD),
        )
        }
        AbLog.event(
            AbLogCategory.TOOL,
            "result",
            mapOf("tool" to command.toToolCall().name, "ok" to result.ok, "code" to result.code.name, "verified" to result.verified),
        )
        return result
    }

    override fun requiresConfirmation(command: ToolCommand): Boolean =
        ToolSpecCatalog.forCommand(command).confirmation == ConfirmationPolicy.REQUIRED

    override fun confirmationMessage(command: ToolCommand): String = when (command) {
        is ToolCommand.SendSms -> "Xác nhận gửi SMS tới “${command.recipient}”:\n${command.message}"
        is ToolCommand.DialContact -> "Xác nhận mở trình quay số cho “${command.recipient}”."
        is ToolCommand.TapUi -> "Xác nhận chạm phần tử ${command.ref} trên màn hình hiện tại."
        is ToolCommand.InputUiText -> "Xác nhận nhập nội dung vào phần tử ${command.ref} trên màn hình hiện tại:\n${command.text}"
        else -> super.confirmationMessage(command)
    }

    private fun flashlightResult(command: ToolCommand): ToolExecutionResult {
        val message = flashlight.execute(command)
        if (!message.startsWith("ERROR:", ignoreCase = true)) {
            return ToolExecutionResult(message, verified = false)
        }
        val code = if (message.contains("permission", ignoreCase = true)) {
            ToolResultCode.PERMISSION_MISSING
        } else {
            ToolResultCode.NOT_AVAILABLE
        }
        return ToolExecutionResult(message, ok = false, code = code, verified = false)
    }

    fun capabilityStatus(modelBackend: String): String = capabilityCoordinator?.describe(modelBackend) ?: buildString {
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
        if (query.isBlank()) return ToolExecutionResult("Tên ứng dụng không hợp lệ.", ok = false, code = ToolResultCode.NOT_AVAILABLE)
        val activities = launchableActivities()
        val exactMatches = activities.filter { activity ->
            normalize(activity.label) == query || normalize(activity.packageName) == query
        }
        val candidates = if (exactMatches.isNotEmpty()) exactMatches else activities.filter { activity ->
            normalize(activity.label).contains(query) || normalize(activity.packageName).contains(query)
        }
        val target = candidates.distinctBy { it.packageName }.singleOrNull()
            ?: return if (candidates.isEmpty()) {
                ToolExecutionResult("Không tìm thấy ứng dụng “$requestedName”.", ok = false, code = ToolResultCode.NOT_FOUND)
            } else {
                ToolExecutionResult("Có nhiều ứng dụng khớp “$requestedName”; hãy nói rõ tên hơn.", ok = false, code = ToolResultCode.AMBIGUOUS)
            }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(target.packageName)
            ?: Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(target.packageName, target.activityName))
        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            ToolExecutionResult("Đã gửi yêu cầu mở ${target.label}.", verified = false)
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
            val verified = manager.getStreamVolume(stream) == target
            if (verified) {
                ToolExecutionResult("Đã đặt âm lượng ${command.stream.name.lowercase(Locale.ROOT)} thành ${command.level}%.")
            } else {
                ToolExecutionResult(
                    "Đã gửi yêu cầu đặt âm lượng nhưng chưa xác minh được mức đích.",
                    ok = false,
                    code = ToolResultCode.NOT_AVAILABLE,
                    verified = false,
                )
            }
        } catch (_: SecurityException) {
            ToolExecutionResult("Không có quyền thay đổi âm lượng.", ok = false, code = ToolResultCode.PERMISSION_MISSING)
        }
    }

    private fun adjustVolume(command: ToolCommand.AdjustVolume): ToolExecutionResult {
        val manager = audioManager() ?: return ToolExecutionResult(unavailableMessage(command))
        val stream = when (command.stream) {
            VolumeStream.MUSIC -> AudioManager.STREAM_MUSIC
            VolumeStream.RING -> AudioManager.STREAM_RING
            VolumeStream.ALARM -> AudioManager.STREAM_ALARM
            VolumeStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        }
        val direction = when (command.adjustment) {
            VolumeAdjustment.UP -> AudioManager.ADJUST_RAISE
            VolumeAdjustment.DOWN -> AudioManager.ADJUST_LOWER
        }
        return try {
            manager.adjustStreamVolume(stream, direction, 0)
            ToolExecutionResult("Volume ${command.adjustment.name.lowercase(Locale.ROOT)} sent.")
        } catch (_: SecurityException) {
            ToolExecutionResult("No permission to change volume.", ok = false)
        }
    }

    private fun readDeviceState(): ToolExecutionResult {
        val manager = context.getSystemService(BatteryManager::class.java)
            ?: return ToolExecutionResult(unavailableMessage(ToolCommand.ReadDeviceState), ok = false)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }
            ?: battery?.let {
                val current = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (current >= 0 && scale > 0) current * 100 / scale else -1
            }
        if (level == null || level < 0) return ToolExecutionResult("Battery percentage unavailable.", ok = false)
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return ToolExecutionResult("Battery $level%, ${if (charging) "charging" else "not charging"}.")
    }

    private fun readUiSnapshot(): ToolExecutionResult {
        val snapshot = AbAccessibilityService.latestSnapshot()
            ?: return ToolExecutionResult("Chưa có ảnh chụp semantic UI.", ok = false, code = ToolResultCode.NOT_AVAILABLE)
        val nodes = snapshot.nodes.joinToString("\n") { node ->
            "${node.ref} role=${node.role} text=${node.text.orEmpty().take(80)} desc=${node.contentDescription.orEmpty().take(80)} id=${node.resourceId.orEmpty()}"
        }
        return ToolExecutionResult(
            "UI snapshot=${snapshot.snapshotId}; package=${snapshot.packageName}; truncated=${snapshot.truncated}\n$nodes",
            verified = true,
        )
    }

    private fun executeUiAction(action: UiAction): ToolExecutionResult {
        val result = AbAccessibilityService.execute(action)
        val beforeSnapshotId = when (action) {
            is UiAction.Tap -> action.ref.snapshotId
            is UiAction.SetText -> action.ref.snapshotId
            is UiAction.Scroll -> action.ref.snapshotId
        }
        return if (result.dispatched) {
            val after = AbAccessibilityService.awaitSnapshotAfter(beforeSnapshotId, UI_POSTCONDITION_TIMEOUT_MS)
            if (com.ab.assistant.accessibility.UiActionPostcondition.screenChanged(beforeSnapshotId, after)) {
                ToolExecutionResult("Đã xác minh màn hình thay đổi sau UI action.", verified = true)
            } else {
                ToolExecutionResult(
                    "UI action đã gửi nhưng không quan sát được thay đổi màn hình.",
                    ok = false,
                    code = ToolResultCode.POSTCONDITION_FAILED,
                    verified = false,
                )
            }
        } else {
            ToolExecutionResult(
                result.error ?: "UI action bị hệ thống từ chối.",
                ok = false,
                code = ToolResultCode.NOT_AVAILABLE,
                verified = false,
            )
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
            ToolExecutionResult("Đã gửi lệnh ${action.name.lowercase(Locale.ROOT)} tới trình phát hiện tại.", verified = false)
        } catch (_: SecurityException) {
            ToolExecutionResult("Hệ thống không cho phép gửi lệnh media này.", ok = false, code = ToolResultCode.NOT_AVAILABLE)
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
        if (!hasHandler(intent)) ToolExecutionResult("Ứng dụng Đồng hồ không hỗ trợ yêu cầu này.", ok = false, code = ToolResultCode.NOT_AVAILABLE)
        else {
            context.startActivity(intent)
            ToolExecutionResult(success, verified = false)
        }
    } catch (_: Exception) {
        ToolExecutionResult("Không thể mở ứng dụng Đồng hồ.")
    }

    private fun readNotifications(filter: String?): ToolExecutionResult {
        val notifications = NotificationStore.read(filter)
        if (notifications.isEmpty()) {
            val suffix = filter?.let { " khớp “$it”" }.orEmpty()
            return ToolExecutionResult("Không có thông báo đang hoạt động$suffix.", ok = false, code = ToolResultCode.NOT_FOUND)
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
            0 -> ToolExecutionResult("Không tìm thấy liên hệ “$name”.", ok = false, code = ToolResultCode.NOT_FOUND)
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
                val id = entry.id.ifBlank { "search-${index + 1}" }
                val source = entry.sourceUrl.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()
                "${index + 1}. [$id] ${entry.title}${entry.snippet.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()}$source"
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
            ok = false,
            code = ToolResultCode.NOT_FOUND,
        )
        is RecipientResolution.Ambiguous -> ToolExecutionResult(
            "Có ${recipient.matches.size} liên hệ khớp “${command.recipient}”; hãy nói rõ tên hơn trước khi gửi: " +
                recipient.matches.joinToString { it.displayName },
            ok = false,
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
            ToolExecutionResult("Đã gửi yêu cầu SMS tới ${recipient.match.displayName}.", verified = false)
        } catch (_: SecurityException) {
            ToolExecutionResult("Cần cấp quyền SMS để gửi tin nhắn.", ok = false, code = ToolResultCode.PERMISSION_MISSING)
        } catch (_: IllegalArgumentException) {
            ToolExecutionResult("Số điện thoại hoặc nội dung SMS không hợp lệ.", ok = false, code = ToolResultCode.NOT_AVAILABLE)
        }
    }

    private fun dialContact(requestedRecipient: String): ToolExecutionResult = when (val recipient = resolveRecipient(requestedRecipient)) {
        is RecipientResolution.NotFound -> ToolExecutionResult(
            "Không tìm thấy liên hệ “$requestedRecipient” để gọi.",
            ok = false,
            code = ToolResultCode.NOT_FOUND,
        )
        is RecipientResolution.Ambiguous -> ToolExecutionResult(
            "Có ${recipient.matches.size} liên hệ khớp “$requestedRecipient”; hãy nói rõ tên hơn: " +
                recipient.matches.joinToString { it.displayName },
            ok = false,
            code = ToolResultCode.AMBIGUOUS,
        )
        is RecipientResolution.Single -> try {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", recipient.match.phoneNumber, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            ToolExecutionResult("Đã gửi yêu cầu mở trình quay số cho ${recipient.match.displayName}.", verified = false)
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

    private fun availabilityState(available: Boolean): com.ab.assistant.state.CapabilityState =
        if (available) com.ab.assistant.state.CapabilityState.READY else com.ab.assistant.state.CapabilityState.DEGRADED

    private data class LaunchableActivity(val label: String, val packageName: String, val activityName: String)

    private sealed interface RecipientResolution {
        data object NotFound : RecipientResolution
        data class Single(val match: ContactMatch) : RecipientResolution
        data class Ambiguous(val matches: List<ContactMatch>) : RecipientResolution
    }

    private companion object {
        const val MAX_NOTIFICATION_TEXT = 180
        const val UI_POSTCONDITION_TIMEOUT_MS = 1_500L
    }
}
