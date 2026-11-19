package com.huanchengfly.tieba.post.api.chat.ws

/**
 * 进程内共享的私信连接。
 *
 * 官方客户端全进程只维护一条 IM 长连接，服务端对同账号的并发连接也会互相干扰。
 * 主界面常驻连接、私信列表、会话页和通知任务都从这里取同一个实例，
 * 页面退出时不再各自关闭，避免反复握手与多连接互踢。
 */
object ChatSocketHub {

    private var client: ChatWebSocketClient? = null
    private var ownerCookie: String = ""

    /**
     * 取得当前账号对应的共享连接。
     *
     * cookie 变化（切换账号或重新登录）时旧连接立即关闭，避免用错身份发送消息。
     */
    @Synchronized
    fun get(cookie: String): ChatWebSocketClient {
        val existing = client
        // 登录态尚未就绪时不要踢掉已有连接，直接沿用。
        if (cookie.isEmpty()) return existing ?: ChatWebSocketClient().also { client = it }
        if (existing != null && ownerCookie == cookie) return existing
        existing?.close()
        return ChatWebSocketClient().also {
            client = it
            ownerCookie = cookie
        }
    }

    /** 退出登录或进程退到后台时关闭共享连接。 */
    @Synchronized
    fun close() {
        client?.close()
        client = null
        ownerCookie = ""
    }
}
