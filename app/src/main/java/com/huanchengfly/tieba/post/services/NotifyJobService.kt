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
import com.huanchengfly.tieba.post.api.chat.http.ImSessionApi
import com.huanchengfly.tieba.post.api.models.MsgBean
import com.huanchengfly.tieba.post.pendingIntentFlagImmutable
import com.huanchengfly.tieba.post.ui.common.theme.utils.ThemeUtils
import com.huanchengfly.tieba.post.utils.AccountUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
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
     * 常规通知接口不返回私信数量，通过 HTTP fetch_session 获取会话未读数，
     * 更新私信通知与角标，不依赖长连接握手或消息游标。
     */
    private fun checkPrivateMessages(params: JobParameters, baseTotal: Int) {
        serviceScope.launch {
            try {
                if (AccountUtil.isLoggedIn()) {
                    consumePrivateMessages(this@NotifyJobService)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "私信通知检查失败", error)
            }
            // 常规通知总数不含私信，接收方分别维护，避免重复相加。
            sendBroadcast(
                Intent(ACTION_NEW_MESSAGE)
                    .setPackage(packageName)
                    .putExtra("channel", CHANNEL_TOTAL)
                    .putExtra("count", baseTotal)
            )
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
        const val ACTION_CLEAR_PRIVATE = "com.huanchengfly.tieba.post.action.CLEAR_PRIVATE"
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
        private val privateMessageMutex = kotlinx.coroutines.sync.Mutex()

        suspend fun consumePrivateMessages(context: Context) {
            privateMessageMutex.lock()
            try {
                // 官方消息中心本身就是 fetch_session 驱动的，未读数直接取自会话列表，
                // 不再依赖长连接逐条比对消息游标。
                val sessions = ImSessionApi.fetchSessions()
                val incoming = sessions.sumOf { it.unreadNum }
                if (incoming <= 0) return
                context.sendBroadcast(
                    Intent(ACTION_NEW_MESSAGE)
                        .setPackage(context.packageName)
                        .putExtra("channel", CHANNEL_PRIVATE)
                        .putExtra("count", incoming)
                )
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    manager.createNotificationChannelGroup(
                        NotificationChannelGroup(CHANNEL_GROUP, CHANNEL_GROUP_NAME)
                    )
                    manager.createNotificationChannel(
                        NotificationChannel(CHANNEL_PRIVATE, CHANNEL_PRIVATE_NAME, NotificationManager.IMPORTANCE_DEFAULT)
                            .apply { group = CHANNEL_GROUP; setShowBadge(true) }
                    )
                }
                val notification = NotificationCompat.Builder(context, CHANNEL_PRIVATE)
                    .setSubText(CHANNEL_PRIVATE_NAME)
                    .setContentTitle(context.getString(R.string.tips_message_private, incoming))
                    .setContentText(context.getString(R.string.tip_touch_to_view))
                    .setSmallIcon(R.drawable.ic_round_drafts)
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(
                        context, ID_PRIVATE,
                        Intent(ACTION_VIEW, Uri.parse("tblite://notifications/2")),
                        pendingIntentFlagImmutable(),
                    ))
                    .build()
                try {
                    manager.notify(ID_PRIVATE, notification)
                } catch (error: SecurityException) {
                    Log.w(TAG, "私信通知权限未授予", error)
                }
            } finally {
                privateMessageMutex.unlock()
            }
        }
    }
}