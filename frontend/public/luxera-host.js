/*
 * Luxera Host SDK —— 第三方应用在宿主里说话的那一半 (§15 / §23 P3)。
 *
 * 用法 (一个最普通的第三方页面):
 *
 *   <script src="https://chat.luxera.top/luxera-host.js"></script>
 *   <script>
 *     const ctx = await luxera.session.context()
 *     await luxera.share.requestShare({ title: '五子棋', description: '来下一局' })
 *   </script>
 *
 * <h2>它是什么, 不是什么</h2>
 *
 * 它是 `protocol.ts` 那张表的**客户端实现**: 把一次函数调用变成一帧 `request`, 把回执变回
 * 一个 Promise。它<b>不含任何业务判断</b> —— 不检查权限、不裁剪字段、不猜宿主会不会答应。
 * 理由很直接: 这边的判断是**可以被绕过的**(应用完全可以不用这个 SDK, 自己 postMessage),
 * 所以它一条都不能承担安全职责。真正的那一道闸在宿主里, 见 `bridge.ts`。
 *
 * <h2>为什么是零依赖的一个文件, 而不是一个 npm 包</h2>
 *
 * 接进来的应用是**别人的**页面, 它们不该为了用一次分享去配一个构建链。一个 <script> 标签
 * 能跑起来的东西, 是这个场景下唯一会被真的用起来的东西。
 *
 * <h2>不用 `window.parent` 之外的任何窗口</h2>
 *
 * 目标窗口写死成 `window.parent`, 收消息时也只认 `event.source === window.parent`。
 * 一个允许应用指定 target 的 SDK, 等于给了它一个向任意窗口发帧的 API。
 */
;(function (global) {
  'use strict'

  var PROTOCOL = 'luxera.host.v1'

  /**
   * 每个能力的等待上限 (毫秒)。0 = 不超时。
   *
   * `share.request` 是唯一一个 0 的: 它会一直挂着直到**用户**做出决定 —— 而"用户想了四十秒
   * 要不要发给谁"是完全正常的事。给它一个超时, 结果是分享在用户按下发送的前一刻失败。
   * 其余的能力都是宿主内部的机械动作, 几秒不回就是真的出问题了。
   */
  var TIMEOUTS = {
    'app.ready': 5000,
    'app.close': 5000,
    'user.me': 5000,
    'session.context': 5000,
    'chat.openWindow': 8000,
    'chat.minimize': 5000,
    'chat.openConversation': 8000,
    'share.request': 0,
  }

  var seq = 0
  var pending = {}

  /**
   * 这个页面自己的随机短前缀。
   *
   * 同一时刻宿主里可能开着好几个应用, 而它们各自的 `seq` 都从 1 开始 —— 没有这个前缀,
   * 宿主日志里的 "req-3" 指代不明, 而排查一条被拒的请求时, "是谁发的"正是第一个要回答的问题。
   */
  var pageId = Math.random().toString(36).slice(2, 10) + Date.now().toString(36).slice(-4)

  function nextId() {
    seq += 1
    return pageId + '-' + seq
  }

  /**
   * 发一帧请求。
   *
   * 这个函数**不判断宿主会不会答应**, 也不预先检查自己有没有权限 —— 那是宿主的活。
   * 客户端预判的后果是: 宿主改了规则, 应用却还在按旧规则自己拒绝自己, 而两边永远不会知道。
   */
  function call(capability, input) {
    return new Promise(function (resolve, reject) {
      if (global.parent === global) {
        reject(
          new Error(
            'Luxera Host SDK: 这个页面没有跑在宿主里 (window.parent === window)。' +
              '请从聊天平台的应用里打开它。',
          ),
        )
        return
      }

      var id = nextId()
      var timer = 0
      var limit = TIMEOUTS[capability]

      if (limit) {
        timer = global.setTimeout(function () {
          delete pending[id]
          reject(new Error('Luxera Host SDK: ' + capability + ' 等了 ' + limit + 'ms 没有回执。'))
        }, limit)
      }

      pending[id] = {
        resolve: function (v) {
          if (timer) global.clearTimeout(timer)
          resolve(v)
        },
        reject: function (e) {
          if (timer) global.clearTimeout(timer)
          reject(e)
        },
      }

      global.parent.postMessage(
        { protocol: PROTOCOL, kind: 'request', id: id, capability: capability, input: input || {} },
        // 目标 origin 写 '*' 是因为宿主可能跑在任意域名下 (本地 / 验证域名 / 未来的 App
        // WebView), 而这个 SDK 没有配置入口去知道它是哪个。**这不构成泄露**: 发出去的是
        // 应用自己填的内容, 而宿主回执的方向由宿主那边的 source 校验把关 —— 也就是说
        // 这个 '*' 影响的是"谁能收到我发的", 不是"谁能命令我"。
        '*',
      )
    })
  }

  var listeners = {}

  global.addEventListener('message', function (event) {
    // 只认宿主那一个窗口。少了这一句, 页面上任何一个 iframe 都能给这个应用推事件。
    if (event.source !== global.parent) return

    var data = event.data
    if (!data || typeof data !== 'object' || data.protocol !== PROTOCOL) return

    if (data.kind === 'response') {
      var slot = pending[data.id]
      if (!slot) return
      delete pending[data.id]
      if (data.ok) {
        slot.resolve(data.result)
      } else {
        var err = new Error(
          (data.error && data.error.message) || '宿主拒绝了这个请求',
        )
        // 应用需要能区分"宿主出错了"与"用户不让我做" —— 所以码必须挂到 Error 上,
        // 而不是只剩下一句人话。
        err.code = (data.error && data.error.code) || 'UNKNOWN'
        slot.reject(err)
      }
      return
    }

    if (data.kind === 'event') {
      var handlers = listeners[data.event] || []
      for (var i = 0; i < handlers.length; i += 1) {
        try {
          handlers[i](data.payload)
        } catch (e) {
          // 一个订阅者抛异常不该让别的订阅者收不到 —— 也不该让这个监听器本身炸掉。
          if (global.console) global.console.error('[luxera] 事件处理出错', e)
        }
      }
    }
  })

  var luxera = {
    /** 协议版本。应用据此判断宿主是不是自己认识的那个。 */
    protocol: PROTOCOL,

    app: {
      /** 告诉宿主"我画好了"。可重复调, 幂等。 */
      ready: function () {
        return call('app.ready')
      },
      /** 请求关闭自己。**宿主决定关掉是什么意思** —— 返回上一页 / 收起抽屉 / 关弹窗。 */
      close: function () {
        return call('app.close')
      },
    },

    user: {
      /** 我在这个平台上是谁。只有 id / displayName / avatarUrl 三个字段 (§15)。 */
      me: function () {
        return call('user.me')
      },
    },

    session: {
      /**
       * 我此刻在哪儿: applicationId / sessionId / surface / conversationId / permissions。
       *
       * 应用该用它来**隐藏自己做不了的事**(没有 SHARE 就把分享按钮收起来), 而不是用它来
       * 决定"要不要绕过" —— 权限的判定始终在宿主那边, 这里拿到的只是同一份事实的副本。
       */
      context: function () {
        return call('session.context')
      },
    },

    chat: {
      /**
       * §7 把聊天浮窗叫出来。
       *
       * `conversationId` 可以不给 —— 那时打开的是浮窗自己的会话列表。`mode` 是
       * 'MINIMIZED' | 'EXPANDED', 不给就是打开时那一档。
       */
      openWindow: function (input) {
        return call('chat.openWindow', input)
      },
      /** 缩回最小态。浮窗已经是最小时也不会报错 —— 幂等比"必须先展开"好用。 */
      minimize: function () {
        return call('chat.minimize')
      },
      /** 让宿主跳到某段对话去。与 openWindow 不同, 这是"离开这个应用"。 */
      openConversation: function (conversationId) {
        return call('chat.openConversation', { conversationId: conversationId })
      },
    },

    share: {
      /**
       * §9 请求分享。宿主会打开 ShareSheet, 由**用户**选人、写附言、发送。
       *
       * 返回 `{ sent: boolean, conversationId?: string }` —— 用户在最后一步取消了就是
       * `sent: false`, 而不是一个异常: 取消是正常结果, 不是错误。
       *
       * <p>`coverUrl` 只用于发起者这一侧的预览, **不会**进那条消息(理由见宿主侧
       * `share.ts` 的 `normalizeShare`)。不传它就由平台按应用名画一张。
       */
      requestShare: function (input) {
        return call('share.request', input)
      },
    },

    /**
     * 订阅宿主事件。返回一个取消订阅的函数 —— 没有取消函数的订阅在单页应用里就是一次泄漏。
     */
    on: function (event, handler) {
      if (!listeners[event]) listeners[event] = []
      listeners[event].push(handler)
      return function () {
        var list = listeners[event] || []
        var at = list.indexOf(handler)
        if (at >= 0) list.splice(at, 1)
      }
    },
  }

  global.luxera = luxera

  // 自动报到。应用可以不等它 —— 但宿主靠它知道"这一层可以显示了", 而一个忘了调 ready 的
  // 应用会永远停在加载态, 那是个很难自己诊断出来的现象。所以这里替它调一次,
  // 并保留 `luxera.app.ready()` 供想要精确控制时机的人重复调用(幂等)。
  if (global.document) {
    if (global.document.readyState === 'loading') {
      global.document.addEventListener('DOMContentLoaded', function () {
        luxera.app.ready().catch(function () {})
      })
    } else {
      luxera.app.ready().catch(function () {})
    }
  }
})(typeof window !== 'undefined' ? window : this)
