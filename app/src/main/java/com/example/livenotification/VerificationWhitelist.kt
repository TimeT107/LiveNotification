package com.example.livenotification

/**
 * 触发验证码识别的 App 白名单
 */
object VerificationWhitelist {

    val packages: Set<String> = setOf(
        // 支付
        "com.eg.android.AlipayGphone",           // 支付宝
        "com.unionpay",                          // 云闪付
        "com.tencent.mm",                        // 微信

        // 电商
        "com.taobao.taobao",                     // 淘宝
        "com.tmall.wireless",                    // 天猫
        "com.jingdong.app.mall",                 // 京东
        "com.xunmeng.pinduoduo",                 // 拼多多

        // 出行
        "com.MobileTicket",                      // 铁路 12306
        "ctrip.android.view",                    // 携程
        "com.Qunar",                             // 去哪儿

        // 银行（常见）
        "com.icbc",                              // 工商银行
        "com.chinamworld.main",                  // 建设银行
        "com.chinamworld.bocmbci",               // 中国银行
        "com.android.bankabc",                   // 农业银行
        "cmb.pb",                                // 招商银行
        "com.bankcomm.Bankcomm",                 // 交通银行
        "com.cmbc.cc.mbank",                     // 民生银行

        // 系统短信
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.miui.smsextra",
        "com.coloros.mms"
    )

    fun contains(pkg: String): Boolean = pkg in packages
}