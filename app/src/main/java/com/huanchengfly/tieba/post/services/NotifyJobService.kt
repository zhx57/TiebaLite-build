package com.huanchengfly.tieba.post.services

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.chat.ws.ChatWebSocketClient
import com.huanchengfly.tieba.post.api.models.MsgBean
import com.huanchengfly.tieba.post.pendingIntentFlagImmutable
import com.huanchengfly.tieba.post.ui.common.theme.utils.ThemeUtils
import com.huanchengfly.tieba.post.utils.AccountUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class NotifyJobService : JobService() {
    var notificationManager: NotificationManager? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun createChannel(id: String, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                id,
                name, NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.group = CHANNEL_GROUP
            channel.setShowBadge(true)
            notificationManager!!.createNotificationChannel(channel)
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        Log.i(TAG, "onStartJob")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channelGroup = NotificationChannelGroup(CHANNEL_GROUP, CHANNEL_GROUP_NAME)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    channelGroup.description = "贴吧的各种消息通知"
                }
                notificationManager!!.createNotificationChannelGroup(channelGroup)
                createChannel(CHANNEL_REPLY, CHANNEL_REPLY_NAME)
                createChannel(CHANNEL_AT, CHANNEL_AT_NAME)
                createChannel(CHANNEL_PRIVATE, CHANNEL_PRIVATE_NAME)
            }
        }
        TiebaApi.getInstance().msg().enqueue(object : Callback<MsgBean> {
            override fun onFailure(call: Call<MsgBean>, t: Throwable) {
                // 常规消息接口失败时仍要尝试私信通知，避免私信完全静默
                checkPrivateMessages(params, 0)
            }

            override fun onResponse(call: Call<MsgBean>, response: Response<MsgBean>) {
                val msgBean = response.body()
                var total = 0
                if (notificationManager != null && msgBean != null) {
                    if ("0" != msgBean.message?.replyMe) {
                        val replyCount = msgBean.message?.replyMe?.let { Integer.valueOf(it) }
                        if (replyCount != null) {
                            total += replyCount
                        }
                        sendBroadcast(
                            Intent()
                                .setAction(ACTION_NEW_MESSAGE)
                                .putExtra("channel", CHANNEL_REPLY)
                                .putExtra("count", replyCount)
                        )
                        updateNotification(
                            getString(
                                R.string.tips_message_reply,
                                msgBean.message?.replyMe
                            ),
                            ID_REPLY,
                            CHANNEL_REPLY,
                            CHANNEL_REPLY_NAME,
                            Intent(ACTION_VIEW, Uri.parse("tblite://notifications/0"))
                        )
                    }
                    if ("0" != msgBean.message?.atMe) {
                        val atCount = msgBean.message?.atMe?.let { Integer.valueOf(it) }
                        if (atCount != null) {
                            total += atCount
                        }
                        sendBroadcast(
                            Intent()
                                .setAction(ACTION_NEW_MESSAGE)
                                .putExtra("channel", CHANNEL_AT)
                                .putExtra("count", msgBean.message?.atMe)
                        )
                        updateNotification(
                            getString(
                                R.string.tips_message_at,
                                msgBean.message?.atMe
                            ),
                            ID_AT,
                            CHANNEL_AT,
                            CHANNEL_AT_NAME,
                            Intent(ACTION_VIEW, Uri.parse("tblite://notifications/1"))
                        )
                    }
                }
                checkPrivateMessages(params, total)
            }
        })
        return true
    }

    /**
     * 拉取私信未读并通知，完成后结束任务。
     *
     * 群聊通知接口不返回私信数量，只能走 IM 协议：握手后以上次记录的游标为基准
     * 拉取增量，把对方发来的新消息计入总数与角标。首次运行只建立基线，不把历史消息当新消息。
     */
    private fun checkPrivateMessages(params: JobParameters, baseTotal: Int) {
        serviceScope.launch {
            val unread = runCatching {
                val client = ChatWebSocketClient()
                try {
                    client.connect(AccountUtil.getBduss().orEmpty())
                    val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    val lastSeen = prefs.getLong(KEY_PRIVATE_LAST_MSG_ID, 0L)
                    val latest = client.privateLastMsgId
                    if (lastSeen <= 0L) {
                        // 首次运行：只记录当前最新游标，避免把全部历史私信当成新消息推送
                        prefs.edit().putLong(KEY_PRIVATE_LAST_MSG_ID, latest).apply()
                        0
                    } else {
                        val since = client.fetchPrivateMessagesSince(lastSeen)
                        if (latest > lastSeen) {
                            prefs.edit().putLong(KEY_PRIVATE_LAST_MSG_ID, latest).apply()
                        }
                        since.count { !it.isSelf && it.peerUid != 0L }
                    }
                } finally {
                    client.close()
                }
            }.getOrDefault(0)
            val total = baseTotal + unread
            if (notificationManager != null) {
                if (unread > 0) {
                    sendBroadcast(
                        Intent()
                            .setAction(ACTION_NEW_MESSAGE)
                            .putExtra("channel", CHANNEL_PRIVATE)
                            .putExtra("count", unread)
                    )
                    updateNotification(
                        getString(R.string.tips_message_private, unread),
                        ID_PRIVATE,
                        CHANNEL_PRIVATE,
                        CHANNEL_PRIVATE_NAME,
                        Intent(ACTION_VIEW, Uri.parse("tblite://notifications/2"))
                    )
                }
                sendBroadcast(
                    Intent()
                        .setAction(ACTION_NEW_MESSAGE)
                        .putExtra("channel", CHANNEL_TOTAL)
                        .putExtra("count", total)
                )
            }
            jobFinished(params, false)
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        return true
    }

    private fun updateNotification(
        text: String,
        id: Int,
        channel: String,
        channelName: String,
        intent: Intent
    ) {
        val notification = NotificationCompat.Builder(this, channel)
            .setSubText(channelName)
            .setContentText(getString(R.string.tip_touch_to_view))
            .setContentTitle(text)
            .setSmallIcon(R.drawable.ic_round_drafts)
            .setWhen(System.currentTimeMillis())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    pendingIntentFlagImmutable()
                )
            )
            .setColor(ThemeUtils.getColorByAttr(this, R.attr.colorPrimary))
            .build()
        notificationManager!!.notify(id, notification)
    }

    companion object {
        val TAG = NotifyJobService::class.java.simpleName
        const val ACTION_NEW_MESSAGE = "com.huanchengfly.tieba.post.action.NEW_MESSAGE"
        const val CHANNEL_GROUP = "20"
        const val CHANNEL_AT = "3"
        const val CHANNEL_AT_NAME = "提到我的"
        const val CHANNEL_PRIVATE = "4"
        const val CHANNEL_PRIVATE_NAME = "私信"
        const val CHANNEL_TOTAL = "total"
        const val ID_REPLY = 20
        const val ID_AT = 21
        const val ID_PRIVATE = 22
        private const val CHANNEL_GROUP_NAME = "消息通知"
        private const val CHANNEL_REPLY = "2"
        private const val CHANNEL_REPLY_NAME = "回复我的"
        private const val PREF_NAME = "notify_job_service"
        private const val KEY_PRIVATE_LAST_MSG_ID = "private_last_msg_id"
    }
}