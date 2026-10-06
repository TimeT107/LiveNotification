package com.example.livenotification


import java.util.concurrent.TimeUnit


object CountdownManager {


    fun getCountdownText(
        departureTime: Long,
        arrivalTime: Long
    ): String {


        val now =
            System.currentTimeMillis()



        return when {


            // 未发车
            // 未发车
            now < departureTime -> {
                val diff = departureTime - now
                "距离发车 ${formatToMinute(diff)}"
            }

// 行驶中
            now < arrivalTime -> {
                val diff = arrivalTime - now
                "预计到达 ${formatToMinute(diff)}"
            }



            // 已到达
            else -> {


                "已到达"

            }


        }


    }





    private fun formatToMinute(millis: Long): String {

        val hour = TimeUnit.MILLISECONDS.toHours(millis)
        val minute = TimeUnit.MILLISECONDS
            .toMinutes(millis)
            .rem(60)

        return String.format(
            "%02d:%02d",
            hour,
            minute
        )
    }

    /**
     * 计算行程时间进度 (0 ~ 100)
     * 出发前为 0，到达后为 100，行程中按时间比例计算
     */
    /**
     * 计算行程时间进度 (0 ~ 100)
     * 第一阶段：createTime → departureTime（候车阶段）
     * 第二阶段：departureTime → arrivalTime（行程阶段）
     * 出发时进度条会从 100 重置回 0
     */
    /**
     * 计算行程时间进度 (0 ~ 100)
     * 第一阶段：createTime → departureTime（候车阶段）
     * 第二阶段：departureTime → arrivalTime（行程阶段）
     *
     * 容错：如果 createTime 无效或已经晚于 departureTime（用户迟到创建），
     *      直接跳过候车阶段，从行程阶段开始算。
     */
    fun calcProgress(
        createTime: Long,
        departureTime: Long,
        arrivalTime: Long
    ): Int {

        val now = System.currentTimeMillis()

        // 基础校验：时间和顺序必须成立
        if (departureTime <= 0L || arrivalTime <= departureTime) {
            return 0
        }

        // 已经到达
        if (now >= arrivalTime) return 100

        // 行程阶段（已经发车）
        if (now >= departureTime) {
            val total = arrivalTime - departureTime
            val elapsed = now - departureTime
            return ((elapsed.toDouble() / total) * 100)
                .toInt()
                .coerceIn(0, 100)
        }

        // 候车阶段
        // createTime 无效或已晚于 departureTime 时，兜底用"出发前 1 小时"作为起点
        val effectiveCreate = if (createTime in 1 until departureTime) {
            createTime
        } else {
            departureTime - 60 * 60 * 1000L
        }

        val total = departureTime - effectiveCreate
        val elapsed = now - effectiveCreate
        return ((elapsed.toDouble() / total) * 100)
            .toInt()
            .coerceIn(0, 100)
    }

    /**
     * 根据当前时间计算出行状态
     * - 未到出发时间 → "等待出行"
     * - 已到出发时间 → "已出发"
     */
    fun calcAutoStatus(
        departureTime: Long
    ): String {

        val now = System.currentTimeMillis()

        return if (now < departureTime) {
            "等待出行"
        } else {
            "已出发"
        }
    }
}