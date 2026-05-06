package io.heckel.ntfy.msg

import android.app.*
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import io.heckel.ntfy.R
import io.heckel.ntfy.db.*
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.ui.Colors
import io.heckel.ntfy.ui.DetailActivity
import io.heckel.ntfy.ui.MainActivity
import io.heckel.ntfy.util.*
import java.util.*
import androidx.core.net.toUri
import kotlinx.coroutines.launch

import android.widget.RemoteViews
class NotificationService(val context: Context) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val repository = Repository.getInstance(context)
    private val markwon = MarkwonFactory.createForNotification(context)
    private val appBaseUrl = context.getString(R.string.app_base_url)

    fun display(subscription: Subscription, notification: Notification) {
        Log.d(TAG, "Displaying notification $notification")
        displayInternal(subscription, notification)
    }

    fun update(subscription: Subscription, notification: Notification) {
        val active = notificationManager.activeNotifications.find { it.id == notification.notificationId } != null
        if (active) {
            Log.d(TAG, "Updating notification $notification")
            displayInternal(subscription, notification, update = true)
        }
    }

    fun cancel(notification: Notification) {
        if (notification.notificationId != 0) {
            Log.d(TAG, "Cancelling notification ${notification.id}: ${decodeMessage(notification)}")
            notificationManager.cancel(notification.notificationId)
        }
    }

    fun cancel(notificationId: Int) {
        if (notificationId != 0) {
            Log.d(TAG, "Cancelling notification $notificationId")
            notificationManager.cancel(notificationId)
        }
    }

    fun createDefaultNotificationChannels() {
        maybeCreateNotificationGroup(DEFAULT_GROUP, context.getString(R.string.channel_notifications_group_default_name))
        ALL_PRIORITIES.forEach { priority -> maybeCreateNotificationChannel(DEFAULT_GROUP, priority) }
    }

    fun createSubscriptionNotificationChannels(subscription: Subscription) {
        val groupId = subscriptionGroupId(subscription)
        maybeCreateNotificationGroup(groupId, subscriptionGroupName(subscription))
        ALL_PRIORITIES.forEach { priority -> maybeCreateNotificationChannel(groupId, priority) }
    }

    fun deleteSubscriptionNotificationChannels(subscription: Subscription) {
        val groupId = subscriptionGroupId(subscription)
        ALL_PRIORITIES.forEach { priority -> maybeDeleteNotificationChannel(groupId, priority) }
        maybeDeleteNotificationGroup(groupId)
    }

    private fun subscriptionGroupId(subscription: Subscription): String {
        return SUBSCRIPTION_GROUP_PREFIX + subscription.id.toString()
    }

    private fun subscriptionGroupName(subscription: Subscription): String {
        return subscription.displayName ?: subscriptionTopicShortUrl(subscription)
    }

    private fun displayInternal(subscription: Subscription, notification: Notification, update: Boolean = false) {
        val title = formatTitle(appBaseUrl, subscription, notification)
        val groupId = if (subscription.dedicatedChannels) subscriptionGroupId(subscription) else DEFAULT_GROUP
        val channelId = toChannelId(groupId, notification.priority)
        val insistent = notification.priority == PRIORITY_MAX &&
                (repository.getInsistentMaxPriorityEnabled() || subscription.insistent == Repository.INSISTENT_MAX_PRIORITY_ENABLED)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(Colors.notificationIcon(context))
            .setContentTitle(title)
            .setWhen(notification.timestamp * 1000) // Set timestamp (convert seconds to millis)
            .setShowWhen(true)
            .setOnlyAlertOnce(true) // Do not vibrate or play sound if already showing (updates!)
            .setAutoCancel(true) // Cancel when notification is clicked
        // LiveUpdate is handled by applyLiveUpdateSettings() below
        setStyleAndText(builder, subscription, notification) // Preview picture or big text style
        setClickAction(builder, subscription, notification)
        maybeSetDeleteIntent(builder, insistent)
        maybeSetSound(builder, insistent, update)
        maybeSetProgress(builder, notification)
        applyLiveUpdateSettings(builder, subscription, notification, insistent)
        maybeAddOpenAction(builder, notification)
        maybeAddBrowseAction(builder, notification)
        maybeAddDownloadAction(builder, notification)
        maybeAddCancelAction(builder, notification)
        maybeAddUserActions(builder, notification)

        maybeCreateNotificationGroup(groupId, subscriptionGroupName(subscription))
        maybeCreateNotificationChannel(groupId, notification.priority)
        maybePlayInsistentSound(groupId, insistent)

        notificationManager.notify(notification.notificationId, builder.build())
    }

    private fun maybeSetDeleteIntent(builder: NotificationCompat.Builder, insistent: Boolean) {
        if (!insistent) {
            return
        }
        val intent = Intent(context, DeleteBroadcastReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(context, Random().nextInt(), intent, PendingIntent.FLAG_IMMUTABLE)
        builder.setDeleteIntent(pendingIntent)
    }

    private fun maybeSetSound(builder: NotificationCompat.Builder, insistent: Boolean, update: Boolean) {
        // Note that the sound setting is ignored in Android => O (26) in favor of notification channels
        val hasSound = !update && !insistent
        if (hasSound) {
            val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            builder.setSound(defaultSoundUri)
        } else {
            builder.setSound(null)
        }
    }

    private fun setStyleAndText(builder: NotificationCompat.Builder, subscription: Subscription, notification: Notification) {
        val contentUri = notification.attachment?.contentUri
        val isSupportedImage = supportedImage(notification.attachment?.type)
        val subscriptionIcon = subscription.icon?.readBitmapFromUriOrNull(context)
        val notificationIcon = notification.icon?.contentUri?.readBitmapFromUriOrNull(context)
        val largeIcon = notificationIcon ?: subscriptionIcon
        if (contentUri != null && isSupportedImage) {
            try {
                val attachmentBitmap = contentUri.readBitmapFromUri(context)
                builder
                    .setContentText(maybeAppendActionErrors(maybeMarkdown(formatMessage(notification), notification), notification))
                    .setLargeIcon(attachmentBitmap)
                    .setStyle(NotificationCompat.BigPictureStyle()
                        .bigPicture(attachmentBitmap)
                        .bigLargeIcon(largeIcon)) // May be null
            } catch (_: Exception) {
                val message = maybeAppendActionErrors(formatMessageMaybeWithAttachmentInfos(notification), notification)
                builder
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            }
        } else {
            val message = maybeAppendActionErrors(formatMessageMaybeWithAttachmentInfos(notification), notification)
            builder
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setLargeIcon(largeIcon) // May be null
        }
    }

    private fun formatMessageMaybeWithAttachmentInfos(notification: Notification): CharSequence {
        val message = maybeMarkdown(formatMessage(notification), notification)
        val attachment = notification.attachment ?: return message
        val attachmentInfos = if (attachment.size != null) {
            "${attachment.name}, ${formatBytes(attachment.size)}"
        } else {
            attachment.name
        }
        if (attachment.progress in 0..99) {
            return context.getString(R.string.notification_popup_file_downloading, attachmentInfos, attachment.progress, message)
        }
        if (attachment.progress == ATTACHMENT_PROGRESS_DONE) {
            return context.getString(R.string.notification_popup_file_download_successful, message, attachmentInfos)
        }
        if (attachment.progress == ATTACHMENT_PROGRESS_FAILED) {
            return context.getString(R.string.notification_popup_file_download_failed, message, attachmentInfos)
        }
        return context.getString(R.string.notification_popup_file, message, attachmentInfos)
    }

    private fun setClickAction(builder: NotificationCompat.Builder, subscription: Subscription, notification: Notification) {
        if (notification.click == "") {
            builder.setContentIntent(detailActivityIntent(subscription))
        } else {
            try {
                val uri = notification.click.toUri()
                val viewIntent = PendingIntent.getActivity(context, Random().nextInt(), Intent(Intent.ACTION_VIEW, uri), PendingIntent.FLAG_IMMUTABLE)
                builder.setContentIntent(viewIntent)
            } catch (_: Exception) {
                builder.setContentIntent(detailActivityIntent(subscription))
            }
        }
    }

    private fun maybeSetProgress(builder: NotificationCompat.Builder, notification: Notification) {
        val progress = notification.attachment?.progress
        if (progress in 0..99) {
            builder.setProgress(100, progress!!, false)
        } else {
            builder.setProgress(0, 0, false) // Remove progress bar
        }
    }

    /**
     * Apply LiveUpdate settings for Android 16+ (ColorOS/OriginOS/Pixel/native).
     *
     * Two parallel paths are supported:
     *
     * 1) Native Android 16 path (Pixel, etc.):
     *    - POST_PROMOTED_NOTIFICATIONS permission declared in manifest
     *    - NotificationChannel.setLiveUpdateAllowed(true) via reflection (channel-level)
     *    - NotificationCompat.Builder.setLiveUpdateEnabled(true) via reflection
     *    - setCategory(CATEGORY_PROGRESS)
     *    - setOngoing(true)
     *    - requestPromotedOngoing() via reflection (Notification.Builder method)
     *
     * 2) ColorOS私有通道 (ColorOS 15/16, OriginOS, etc.):
     *    - setOngoing(true)
     *    - android.requestPromotedOngoing extra in Notification extras bundle (private API)
     *    - Custom RemoteViews via setCustomBigContentView/setCustomContentView
     *
     * The standard path requires BigTextStyle (no RemoteViews), but since ntfy uses RemoteViews
     * for rich UI (download progress, actions), we use the ColorOS private extra for ColorOS
     * devices, while the standard path ensures compatibility with native Android 16.
     * RemoteViews still work on ColorOS (fluid pill) via the private extra override.
     */
    private fun applyLiveUpdateSettings(builder: NotificationCompat.Builder, subscription: Subscription, notification: Notification, insistent: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return
        }
        val hasProgress = notification.attachment?.progress in 0..99
        val hasTag1LiveUpdate = splitTags(notification.tags).contains("1")
        val shouldBeOngoing = insistent || hasProgress || hasTag1LiveUpdate
        val isLiveUpdateEligible = hasProgress || insistent || hasTag1LiveUpdate

        // Tell the system this notification should be shown as a LiveUpdate (Android 16+).
        // AndroidX exposes setLiveUpdateEnabled() on NotificationCompat.Builder.
        try {
            val method = builder.javaClass.getMethod("setLiveUpdateEnabled", Boolean::class.javaPrimitiveType)
            method.invoke(builder, true)
        } catch (_: Exception) {
            // Silently ignore if method not available
        }

        // For native Android 16+ LiveUpdate (Pixel, etc.)
        // setCategory(CATEGORY_PROGRESS) + setOngoing(true) + requestPromotedOngoing() trigger
        // the notification to be promoted to the Live Update UI.
        if (isLiveUpdateEligible) {
            builder.setCategory(NotificationCompat.CATEGORY_PROGRESS)
        }
        builder.setOngoing(shouldBeOngoing)

        // Apply custom LiveUpdate views when a progress bar is shown
        if (hasProgress || hasTag1LiveUpdate) {
            applyLiveUpdateCustomViews(builder, subscription, notification)
        }

        // Android 16 LiveUpdate promotion requires calling on the framework Notification.Builder
        // (not just NotificationCompat.Builder), because ColorOS reads from the framework builder's
        // extras bundle. We need to get the internal NotificationCompatBuilder and call the
        // framework builder methods directly.
        val frameworkBuilder = try {
            val compatBuilderClass = Class.forName("androidx.core.app.NotificationCompatBuilder")
            val getBuilderMethod = compatBuilderClass.getMethod("getBuilder")
            getBuilderMethod.invoke(builder) as? android.app.Notification.Builder
        } catch (_: Exception) {
            null
        }

        if (frameworkBuilder != null && isLiveUpdateEligible) {
            // setRequestPromotedOngoing() on the framework builder (not just compat).
            try {
                val method = frameworkBuilder.javaClass.getMethod("requestPromotedOngoing")
                method.invoke(frameworkBuilder)
            } catch (_: Exception) {
                // Fallback to compat builder
                try {
                    val method = builder.javaClass.getMethod("setRequestPromotedOngoing")
                    method.invoke(builder)
                } catch (_: Exception) {
                    // Silently ignore
                }
            }

            // setShortCriticalText(null) on the framework builder.
            // This is what Cmd2Gui calls in its g.a() method.
            try {
                val method = frameworkBuilder.javaClass.getMethod("setShortCriticalText", CharSequence::class.java)
                method.invoke(frameworkBuilder, null)
            } catch (_: Exception) {
                // Fallback to compat builder
                try {
                    val method = builder.javaClass.getMethod("setShortCriticalText", CharSequence::class.java)
                    method.invoke(builder, null)
                } catch (_: Exception) {
                    // Silently ignore
                }
            }

            // ColorOS-specific: android.requestPromotedOngoing is a private bundle extra
            // that ColorOS reads from the framework builder's extras to promote the notification.
            try {
                val extrasField = frameworkBuilder.javaClass.getMethod("getExtras")
                val extras = extrasField.invoke(frameworkBuilder) as android.os.Bundle
                extras.putBoolean("android.requestPromotedOngoing", true)
            } catch (_: Exception) {
                // Fallback to compat builder extras
                try {
                    builder.extras.putBoolean("android.requestPromotedOngoing", true)
                } catch (_: Exception) {
                    // Silently ignore
                }
            }
        } else if (isLiveUpdateEligible) {
            // Fallback: call on NotificationCompat.Builder directly (may not propagate to framework builder)
            try {
                val method = builder.javaClass.getMethod("setRequestPromotedOngoing")
                method.invoke(builder)
            } catch (_: Exception) {
                // Silently ignore
            }
            try {
                val method = builder.javaClass.getMethod("setShortCriticalText", CharSequence::class.java)
                method.invoke(builder, null)
            } catch (_: Exception) {
                // Silently ignore
            }
            try {
                builder.extras.putBoolean("android.requestPromotedOngoing", true)
            } catch (_: Exception) {
                // Silently ignore
            }
        }
    }

    /**
     * Apply custom RemoteViews for LiveUpdate expanded/pill content.
     * Shows: topic name, message title, optional download progress bar,
     * optional click-to-URL button.
     * This enriches the LiveUpdate UI beyond the default system rendering.
     */
    private fun applyLiveUpdateCustomViews(builder: NotificationCompat.Builder, subscription: Subscription, notification: Notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return
        }
        try {
            val hasProgress = notification.attachment?.progress in 0..99

            // RemoteViews for expanded (expanded content view / pill expanded)
            val expandedViews = RemoteViews(context.packageName, R.layout.layout_liveupdate_expanded)
            // Tags: show emoji tags when available
            val tagsText = splitTags(notification.tags).joinToString(" ")
            if (tagsText.isNotEmpty()) {
                expandedViews.setTextViewText(R.id.liveupdate_tags, tagsText)
                expandedViews.setViewVisibility(R.id.liveupdate_tags, android.view.View.VISIBLE)
            } else {
                expandedViews.setViewVisibility(R.id.liveupdate_tags, android.view.View.GONE)
            }

            // Priority star: show for max priority (priority=5)
            if (notification.priority == PRIORITY_MAX) {
                expandedViews.setViewVisibility(R.id.liveupdate_priority_star, android.view.View.VISIBLE)
            } else {
                expandedViews.setViewVisibility(R.id.liveupdate_priority_star, android.view.View.GONE)
            }

            expandedViews.setTextViewText(R.id.liveupdate_title, subscription.displayName)
            expandedViews.setTextViewText(R.id.liveupdate_message, notification.title ?: "")

            // Progress bar + row: only visible for download progress notifications
            if (hasProgress) {
                val progress = notification.attachment?.progress ?: 0
                expandedViews.setProgressBar(R.id.liveupdate_progress, 100, progress, false)
                expandedViews.setViewVisibility(R.id.liveupdate_progress, android.view.View.VISIBLE)
                expandedViews.setViewVisibility(R.id.liveupdate_progress_row, android.view.View.VISIBLE)

                val progressText = context.getString(R.string.liveupdate_progress_format, progress)
                expandedViews.setTextViewText(R.id.liveupdate_progress_text, progressText)

                notification.attachment?.size?.let { size ->
                    expandedViews.setTextViewText(R.id.liveupdate_size, formatFileSize(size))
                }
            } else {
                expandedViews.setViewVisibility(R.id.liveupdate_progress, android.view.View.GONE)
                expandedViews.setViewVisibility(R.id.liveupdate_progress_row, android.view.View.GONE)
            }

            // Actions row: click button + ntfy user actions
            val actionsRow = buildActionsRow(notification)
            if (actionsRow != null) {
                expandedViews.addView(R.id.liveupdate_actions, actionsRow)
            }

            builder.setCustomBigContentView(expandedViews)

            // RemoteViews for collapsed (pill view / notification header)
            val collapsedViews = RemoteViews(context.packageName, R.layout.layout_liveupdate_collapsed)
            collapsedViews.setTextViewText(R.id.liveupdate_collapsed_title, subscription.displayName)
            val collapsedTagsText = splitTags(notification.tags).joinToString(" ")
            if (collapsedTagsText.isNotEmpty()) {
                collapsedViews.setTextViewText(R.id.liveupdate_collapsed_tags, collapsedTagsText)
                collapsedViews.setViewVisibility(R.id.liveupdate_collapsed_tags, android.view.View.VISIBLE)
            } else {
                collapsedViews.setViewVisibility(R.id.liveupdate_collapsed_tags, android.view.View.GONE)
            }
            if (notification.priority == PRIORITY_MAX) {
                collapsedViews.setViewVisibility(R.id.liveupdate_collapsed_star, android.view.View.VISIBLE)
            } else {
                collapsedViews.setViewVisibility(R.id.liveupdate_collapsed_star, android.view.View.GONE)
            }
            builder.setCustomContentView(collapsedViews)

            builder.setContentInfo(if (hasProgress) "${notification.attachment?.progress ?: 0}%" else "")
        } catch (_: Exception) {
            // If custom views fail, notification renders with default style
        }
    }

    /**
     * Builds a RemoteViews row containing the click button (if set),
     * ntfy user action buttons (view/broadcast/http/copy),
     * and ntfy attachment action buttons (open/browse/download/cancel).
     * Returns null if nothing to show.
     */
    private fun buildActionsRow(notification: Notification): RemoteViews? {
        val hasClick = notification.click.isNotEmpty()
        val hasActions = !notification.actions.isNullOrEmpty()
        val attachment = notification.attachment
        val hasAttachmentContentUri = attachment?.contentUri != null
        val hasAttachmentProgress = attachment?.progress in 0..99
        val hasAttachmentFailed = attachment?.progress == ATTACHMENT_PROGRESS_FAILED
        val hasAttachmentNone = attachment?.progress == ATTACHMENT_PROGRESS_NONE
        val canOpen = hasAttachmentContentUri && canOpenAttachment(attachment)
        val canBrowse = hasAttachmentContentUri
        val canDownload = !hasAttachmentContentUri && (hasAttachmentNone || hasAttachmentFailed)
        val canCancel = !hasAttachmentContentUri && hasAttachmentProgress

        if (!hasClick && !hasActions && !canOpen && !canBrowse && !canDownload && !canCancel) return null

        // Container that holds all buttons in a horizontal row
        val container = RemoteViews(context.packageName, R.layout.liveupdate_actions_row)

        // Add click/Open button first
        if (hasClick) {
            val clickButton = RemoteViews(context.packageName, R.layout.liveupdate_action_button)
            clickButton.setTextViewText(R.id.liveupdate_action_btn, context.getString(R.string.notification_popup_action_open))
            clickButton.setOnClickFillInIntent(R.id.liveupdate_action_btn, Intent(Intent.ACTION_VIEW, notification.click.toUri()))
            container.addView(R.id.liveupdate_actions_container, clickButton)
        }

        // Open button: for completed downloads
        if (canOpen) {
            val openButton = RemoteViews(context.packageName, R.layout.liveupdate_action_button)
            openButton.setTextViewText(R.id.liveupdate_action_btn, context.getString(R.string.notification_popup_action_open))
            val openIntent = Intent(Intent.ACTION_VIEW, attachment.contentUri.toUri()).apply {
                setDataAndType(attachment.contentUri.toUri(), attachment.type ?: "application/octet-stream")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            openButton.setOnClickFillInIntent(R.id.liveupdate_action_btn, openIntent)
            container.addView(R.id.liveupdate_actions_container, openButton)
        }

        // Browse button: open system downloads
        if (canBrowse) {
            val browseButton = RemoteViews(context.packageName, R.layout.liveupdate_action_button)
            browseButton.setTextViewText(R.id.liveupdate_action_btn, context.getString(R.string.notification_popup_action_browse))
            val browseIntent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            browseButton.setOnClickFillInIntent(R.id.liveupdate_action_btn, browseIntent)
            container.addView(R.id.liveupdate_actions_container, browseButton)
        }

        // Download button: for failed/not-started attachments
        if (canDownload) {
            val downloadButton = RemoteViews(context.packageName, R.layout.liveupdate_action_button)
            downloadButton.setTextViewText(R.id.liveupdate_action_btn, context.getString(R.string.notification_popup_action_download))
            val downloadIntent = Intent(context, UserActionBroadcastReceiver::class.java).apply {
                putExtra(BROADCAST_EXTRA_TYPE, BROADCAST_TYPE_DOWNLOAD_START)
                putExtra(BROADCAST_EXTRA_NOTIFICATION_ID, notification.id)
            }
            downloadButton.setOnClickFillInIntent(R.id.liveupdate_action_btn, downloadIntent)
            container.addView(R.id.liveupdate_actions_container, downloadButton)
        }

        // Cancel button: for in-progress downloads
        if (canCancel) {
            val cancelButton = RemoteViews(context.packageName, R.layout.liveupdate_action_button)
            cancelButton.setTextViewText(R.id.liveupdate_action_btn, context.getString(R.string.notification_popup_action_cancel))
            val cancelIntent = Intent(context, UserActionBroadcastReceiver::class.java).apply {
                putExtra(BROADCAST_EXTRA_TYPE, BROADCAST_TYPE_DOWNLOAD_CANCEL)
                putExtra(BROADCAST_EXTRA_NOTIFICATION_ID, notification.id)
            }
            cancelButton.setOnClickFillInIntent(R.id.liveupdate_action_btn, cancelIntent)
            container.addView(R.id.liveupdate_actions_container, cancelButton)
        }

        // Add ntfy user action buttons
        if (hasActions) {
            notification.actions!!.forEach { action ->
                val label = formatActionLabel(action)
                val actionButton = RemoteViews(context.packageName, R.layout.liveupdate_action_button)
                actionButton.setTextViewText(R.id.liveupdate_action_btn, label)
                val actionIntent = Intent(context, UserActionBroadcastReceiver::class.java).apply {
                    putExtra(BROADCAST_EXTRA_TYPE, BROADCAST_TYPE_USER_ACTION)
                    putExtra(BROADCAST_EXTRA_NOTIFICATION_ID, notification.id)
                    putExtra(BROADCAST_EXTRA_ACTION_ID, action.id)
                }
                actionButton.setOnClickFillInIntent(R.id.liveupdate_action_btn, actionIntent)
                container.addView(R.id.liveupdate_actions_container, actionButton)
            }
        }

        return container
    }

    /**
     * Formats the label for a ntfy user action (view/broadcast/http/copy).
     * Falls back to the action type if label is empty.
     */
    private fun formatActionLabel(action: Action): String {
        if (action.label.isNotEmpty()) return action.label
        return when (action.action.lowercase(Locale.getDefault())) {
            "view" -> "View"
            "broadcast" -> "Broadcast"
            "http" -> "HTTP"
            "copy" -> "Copy"
            else -> action.action
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun maybeAddOpenAction(builder: NotificationCompat.Builder, notification: Notification) {
        if (!canOpenAttachment(notification.attachment)) {
            return
        }
        if (notification.attachment?.contentUri != null) {
            val contentUri = notification.attachment.contentUri.toUri()
            val intent = Intent(Intent.ACTION_VIEW, contentUri).apply {
                setDataAndType(contentUri, notification.attachment.type ?: "application/octet-stream") // Required for Android <= P
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pendingIntent = PendingIntent.getActivity(context, Random().nextInt(), intent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.notification_popup_action_open), pendingIntent).build())
        }
    }

    private fun maybeAddBrowseAction(builder: NotificationCompat.Builder, notification: Notification) {
        if (notification.attachment?.contentUri != null) {
            val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pendingIntent = PendingIntent.getActivity(context, Random().nextInt(), intent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.notification_popup_action_browse), pendingIntent).build())
        }
    }

    private fun maybeAddDownloadAction(builder: NotificationCompat.Builder, notification: Notification) {
        if (notification.attachment?.contentUri == null && listOf(ATTACHMENT_PROGRESS_NONE, ATTACHMENT_PROGRESS_FAILED).contains(notification.attachment?.progress)) {
            val intent = Intent(context, UserActionBroadcastReceiver::class.java).apply {
                putExtra(BROADCAST_EXTRA_TYPE, BROADCAST_TYPE_DOWNLOAD_START)
                putExtra(BROADCAST_EXTRA_NOTIFICATION_ID, notification.id)
            }
            val pendingIntent = PendingIntent.getBroadcast(context, Random().nextInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.notification_popup_action_download), pendingIntent).build())
        }
    }

    private fun maybeAddCancelAction(builder: NotificationCompat.Builder, notification: Notification) {
        if (notification.attachment?.contentUri == null && notification.attachment?.progress in 0..99) {
            val intent = Intent(context, UserActionBroadcastReceiver::class.java).apply {
                putExtra(BROADCAST_EXTRA_TYPE, BROADCAST_TYPE_DOWNLOAD_CANCEL)
                putExtra(BROADCAST_EXTRA_NOTIFICATION_ID, notification.id)
            }
            val pendingIntent = PendingIntent.getBroadcast(context, Random().nextInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.notification_popup_action_cancel), pendingIntent).build())
        }
    }

    private fun maybeAddUserActions(builder: NotificationCompat.Builder, notification: Notification) {
        notification.actions?.forEach { action ->
            val actionType = action.action.lowercase(Locale.getDefault())
            if (actionType == ACTION_VIEW) {
                // Hack: Action "view" with "clear=true" is a special case, because it's apparently impossible to start a
                // URL activity from PendingIntent.getActivity() and also close the notification. To clear it, we
                // launch our own Activity (ViewActionWithClearActivity) which then calls the actual activity

                if (action.clear == true) {
                    addViewUserActionWithClear(builder, notification, action)
                } else {
                    addViewUserActionWithoutClear(builder, action)
                }
            } else {
                addHttpBroadcastOrCopyUserAction(builder, notification, action)
            }
        }
    }

    /**
     * Open the URL and do NOT cancel the notification (clear=false). This uses a normal Intent with the given URL.
     * The other case is much more interesting.
     */
    private fun addViewUserActionWithoutClear(builder: NotificationCompat.Builder, action: Action) {
        try {
            val url = action.url ?: return
            val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            val pendingIntent = PendingIntent.getActivity(context, Random().nextInt(), intent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, action.label, pendingIntent).build())
        } catch (e: Exception) {
            Log.w(TAG, "Unable to add open user action", e)
        }
    }

    /**
     * HACK: Open the URL and CANCEL the notification (clear=true). This is a SPECIAL case with a horrible workaround.
     * We call our own activity ViewActionWithClearActivity and open the URL from there.
     */
    private fun addViewUserActionWithClear(builder: NotificationCompat.Builder, notification: Notification, action: Action) {
        try {
            val url = action.url ?: return
            val intent = Intent(context, ViewActionWithClearActivity::class.java).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(VIEW_ACTION_EXTRA_URL, url)
                putExtra(VIEW_ACTION_EXTRA_NOTIFICATION_ID, notification.notificationId)
                putExtra(VIEW_ACTION_EXTRA_SUBSCRIPTION_ID, notification.subscriptionId)
                putExtra(VIEW_ACTION_EXTRA_SEQUENCE_ID, notification.sequenceId)
            }
            val pendingIntent = PendingIntent.getActivity(context, Random().nextInt(), intent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, action.label, pendingIntent).build())
        } catch (e: Exception) {
            Log.w(TAG, "Unable to add open user action", e)
        }
    }

    private fun addHttpBroadcastOrCopyUserAction(builder: NotificationCompat.Builder, notification: Notification, action: Action) {
        val intent = Intent(context, UserActionBroadcastReceiver::class.java).apply {
            putExtra(BROADCAST_EXTRA_TYPE, BROADCAST_TYPE_USER_ACTION)
            putExtra(BROADCAST_EXTRA_NOTIFICATION_ID, notification.id)
            putExtra(BROADCAST_EXTRA_ACTION_ID, action.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(context, Random().nextInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val label = formatActionLabel(action)
        builder.addAction(NotificationCompat.Action.Builder(0, label, pendingIntent).build())
    }

    /**
     * Receives the broadcast from
     * - the "http", "broadcast", and "copy" action button (the "view" action is handled differently)
     * - the "download"/"cancel" action button
     *
     * Then queues a Worker via WorkManager to execute the action in the background
     */
    class UserActionBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val type = intent.getStringExtra(BROADCAST_EXTRA_TYPE) ?: return
            val notificationId = intent.getStringExtra(BROADCAST_EXTRA_NOTIFICATION_ID) ?: return
            when (type) {
                BROADCAST_TYPE_DOWNLOAD_START -> DownloadManager.enqueue(context, notificationId, userAction = true, DownloadType.ATTACHMENT)
                BROADCAST_TYPE_DOWNLOAD_CANCEL -> DownloadManager.cancel(context, notificationId)
                BROADCAST_TYPE_USER_ACTION -> {
                    val actionId = intent.getStringExtra(BROADCAST_EXTRA_ACTION_ID) ?: return
                    UserActionManager.enqueue(context, notificationId, actionId)
                }
            }
        }
    }

    /**
     * Receives a broadcast when a notification is swiped away. This is currently
     * only called for notifications with an insistent sound.
     */
    class DeleteBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Media player: Stopping insistent ring")
            val mediaPlayer = Repository.getInstance(context).mediaPlayer
            mediaPlayer.stop()
        }
    }

    private fun detailActivityIntent(subscription: Subscription): PendingIntent? {
        val intent = Intent(context, DetailActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, subscription.id)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_BASE_URL, subscription.baseUrl)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_TOPIC, subscription.topic)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_DISPLAY_NAME, displayName(appBaseUrl, subscription))
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_INSTANT, subscription.instant)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_MUTED_UNTIL, subscription.mutedUntil)
        }
        return TaskStackBuilder.create(context).run {
            addNextIntentWithParentStack(intent) // Add the intent, which inflates the back stack
            getPendingIntent(Random().nextInt(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) // Get the PendingIntent containing the entire back stack
        }
    }

    private fun maybeCreateNotificationChannel(group: String, priority: Int) {
        // Note: To change a notification channel, you must delete the old one and create a new one!

        val channelId = toChannelId(group, priority)
        val pause = 300L
        val channel = when (priority) {
            PRIORITY_MIN -> NotificationChannel(channelId, context.getString(R.string.common_priority_min_name), NotificationManager.IMPORTANCE_MIN)
            PRIORITY_LOW -> NotificationChannel(channelId, context.getString(R.string.common_priority_low_name), NotificationManager.IMPORTANCE_LOW)
            PRIORITY_HIGH -> {
                val channel = NotificationChannel(channelId, context.getString(R.string.common_priority_high_name), NotificationManager.IMPORTANCE_HIGH)
                channel.enableVibration(true)
                channel.vibrationPattern = longArrayOf(
                    pause, 100, pause, 100, pause, 100,
                    pause, 2000
                )
                channel
            }
            PRIORITY_MAX -> {
                val channel = NotificationChannel(channelId, context.getString(R.string.common_priority_max_name), NotificationManager.IMPORTANCE_HIGH) // IMPORTANCE_MAX does not exist
                channel.enableLights(true)
                channel.enableVibration(true)
                channel.setBypassDnd(true)
                channel.vibrationPattern = longArrayOf(
                    pause, 100, pause, 100, pause, 100,
                    pause, 2000,
                    pause, 100, pause, 100, pause, 100,
                    pause, 2000,
                    pause, 100, pause, 100, pause, 100,
                    pause, 2000
                )
                channel
            }
            else -> NotificationChannel(channelId, context.getString(R.string.common_priority_default_name), NotificationManager.IMPORTANCE_DEFAULT)
        }
        channel.group = group
        // LiveUpdate channel-level setting is handled by setLiveUpdateAllowed()
        // Enable LiveUpdate for Android 16+ (API 35+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // setLiveUpdateAllowed(true) enables channel-level LiveUpdate on Android 16+.
            // Use reflection for compatibility with SDK versions that don't have this method.
            try {
                val method = channel.javaClass.getMethod("setLiveUpdateAllowed", Boolean::class.java)
                method.invoke(channel, true)
            } catch (_: Exception) {
                // Silently ignore if method not available
            }
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun maybeDeleteNotificationChannel(group: String, priority: Int) {
        notificationManager.deleteNotificationChannel(toChannelId(group, priority))
    }

    private fun maybeCreateNotificationGroup(id: String, name: String) {
        notificationManager.createNotificationChannelGroup(NotificationChannelGroup(id, name))
    }

    private fun maybeDeleteNotificationGroup(id: String) {
        notificationManager.deleteNotificationChannelGroup(id)
    }

    fun toChannelId(groupId: String, priority: Int): String {
        return when (priority) {
            PRIORITY_MIN -> groupId + GROUP_SUFFIX_PRIORITY_MIN
            PRIORITY_LOW -> groupId + GROUP_SUFFIX_PRIORITY_LOW
            PRIORITY_HIGH -> groupId + GROUP_SUFFIX_PRIORITY_HIGH
            PRIORITY_MAX -> groupId + GROUP_SUFFIX_PRIORITY_MAX
            else -> groupId + GROUP_SUFFIX_PRIORITY_DEFAULT
        }
    }

    private fun maybePlayInsistentSound(groupId: String, insistent: Boolean) {
        if (!insistent) {
            return
        }
        try {
            val mediaPlayer = repository.mediaPlayer
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) != 0) {
                Log.d(TAG, "Media player: Playing insistent alarm on alarm channel")
                mediaPlayer.reset()
                mediaPlayer.setDataSource(context, getInsistentSound(groupId))
                mediaPlayer.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                mediaPlayer.isLooping = true
                mediaPlayer.prepare()
                mediaPlayer.start()
            } else {
                Log.d(TAG, "Media player: Alarm volume is 0; not playing insistent alarm")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Media player: Failed to play insistent alarm", e)
        }
    }

    private fun getInsistentSound(groupId: String): Uri {
        val channelId = toChannelId(groupId, PRIORITY_MAX)
        val channel = notificationManager.getNotificationChannel(channelId)
        return channel.sound
    }

    /**
     * Activity used to launch a URL.
     * .
     * Horrible hack: Action "view" with "clear=true" is a special case, because it's apparently impossible to start a
     * URL activity from PendingIntent.getActivity() and also close the notification. To clear it, we
     * launch this activity which then calls the actual activity.
     */
    class ViewActionWithClearActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            Log.d(TAG, "Created $this")
            val url = intent.getStringExtra(VIEW_ACTION_EXTRA_URL)
            val notificationId = intent.getIntExtra(VIEW_ACTION_EXTRA_NOTIFICATION_ID, 0)
            val subscriptionId = intent.getLongExtra(VIEW_ACTION_EXTRA_SUBSCRIPTION_ID, 0)
            val sequenceId = intent.getStringExtra(VIEW_ACTION_EXTRA_SEQUENCE_ID) ?: ""
            if (url == null) {
                finish()
                return
            }

            // Immediately start the actual activity
            try {
                val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start activity from URL $url", e)
                val message = if (e is ActivityNotFoundException) url else e.message
                Toast
                    .makeText(this, getString(R.string.detail_item_cannot_open_url, message), Toast.LENGTH_LONG)
                    .show()
            }

            // Cancel notification
            val notifier = NotificationService(this)
            notifier.cancel(notificationId)

            // Mark notification as read; We can't use lifecycleScope here, because we
            // call finish() right after, so we do this awkward ioScope thing.
            if (subscriptionId != 0L && sequenceId.isNotEmpty()) {
                val app = applicationContext as io.heckel.ntfy.app.Application
                app.ioScope.launch {
                    val repository = Repository.getInstance(app)
                    repository.markAsReadBySequenceId(subscriptionId, sequenceId)
                }
            }

            // Close this activity
            finish()
        }
    }

    private fun maybeMarkdown(message: String, notification: Notification): CharSequence {
        if (notification.contentType == "text/markdown") {
            return markwon.toMarkdown(message)
        }
        return message
    }

    companion object {
        const val ACTION_VIEW = "view"
        const val ACTION_HTTP = "http"
        const val ACTION_BROADCAST = "broadcast"
        const val ACTION_COPY = "copy"

        const val BROADCAST_EXTRA_TYPE = "type"
        const val BROADCAST_EXTRA_NOTIFICATION_ID = "notificationId"
        const val BROADCAST_EXTRA_ACTION_ID = "actionId"

        const val BROADCAST_TYPE_DOWNLOAD_START = "io.heckel.ntfy.DOWNLOAD_ACTION_START"
        const val BROADCAST_TYPE_DOWNLOAD_CANCEL = "io.heckel.ntfy.DOWNLOAD_ACTION_CANCEL"
        const val BROADCAST_TYPE_USER_ACTION = "io.heckel.ntfy.USER_ACTION_RUN"

        private const val TAG = "NtfyNotifService"

        const val DEFAULT_GROUP = "ntfy"
        private const val SUBSCRIPTION_GROUP_PREFIX = "ntfy-subscription-"
        private const val GROUP_SUFFIX_PRIORITY_MIN = "-min"
        private const val GROUP_SUFFIX_PRIORITY_LOW = "-low"
        private const val GROUP_SUFFIX_PRIORITY_DEFAULT = ""
        private const val GROUP_SUFFIX_PRIORITY_HIGH = "-high"
        private const val GROUP_SUFFIX_PRIORITY_MAX = "-max"

        private const val VIEW_ACTION_EXTRA_URL = "url"
        private const val VIEW_ACTION_EXTRA_NOTIFICATION_ID = "notificationId"
        private const val VIEW_ACTION_EXTRA_SUBSCRIPTION_ID = "subscriptionId"
        private const val VIEW_ACTION_EXTRA_SEQUENCE_ID = "sequenceId"
    }
}
