package com.irregular.xenopowermeter.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import com.irregular.xenopowermeter.R
import com.irregular.xenopowermeter.data.converter.DataConverter
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

object IslandHelper {

    private const val CHANNEL_ID = "power_meter_live"
    private const val CHANNEL_NAME = "Power Meter Live"
    private const val NOTIFICATION_ID = 1001

    private var notificationManager: NotificationManager? = null
    private var quotes: List<String> = emptyList()
    private var currentQuote: String = ""
    private var recordingStartTimeMs: Long = 0L

    fun init(context: Context) {
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel(context)
        loadQuotes(context)
    }

    private fun loadQuotes(context: Context) {
        try {
            val inputStream = context.assets.open("island_quotes.txt")
            val reader = BufferedReader(InputStreamReader(inputStream))
            quotes = reader.readLines().filter { it.isNotBlank() }
            reader.close()
        } catch (e: Exception) {
            quotes = listOf("XenoPowerMeter")
        }
    }

    private fun getRandomQuote(): String {
        if (currentQuote.isEmpty()) {
            currentQuote = if (quotes.isNotEmpty()) quotes.random() else "XenoPowerMeter"
        }
        return currentQuote
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Live power measurement updates"
        }
        notificationManager?.createNotificationChannel(channel)
    }

    fun isIslandSupported(context: Context): Boolean {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod(
                "getBoolean",
                String::class.java,
                Boolean::class.javaPrimitiveType
            )
            method.invoke(null, "persist.sys.feature.island", false) as Boolean
        } catch (e: Exception) {
            false
        }
    }

    fun hasFocusPermission(context: Context): Boolean {
        return try {
            val uri = android.net.Uri.parse("content://miui.statusbar.notification.public")
            val extras = Bundle().apply { putString("package", context.packageName) }
            val bundle = context.contentResolver.call(uri, "canShowFocus", null, extras)
            bundle?.getBoolean("canShowFocus", false) ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun showLiveMeasurement(
        context: Context,
        voltage: Float,
        current: Float,
        power: Float,
        avgPower: Float,
        isRecording: Boolean
    ) {
        val nm = notificationManager ?: return

        if (isRecording && recordingStartTimeMs == 0L) {
            recordingStartTimeMs = System.currentTimeMillis()
        }

        val voltageStr = DataConverter.formatVoltage(voltage)
        val currentStr = DataConverter.formatCurrent(current)
        val powerStr = DataConverter.formatPower(power)
        val avgPowerStr = DataConverter.formatPower(avgPower)

        val durationMs = if (isRecording) System.currentTimeMillis() - recordingStartTimeMs else 0L
        val durationSec = durationMs / 1000
        val durationMin = durationSec / 60
        val durationS = durationSec % 60
        val durationStr = String.format("%02d:%02d", durationMin, durationS)

        val islandParams = buildIslandParams(
            context = context,
            voltageStr = voltageStr,
            currentStr = currentStr,
            powerStr = powerStr,
            avgPowerStr = avgPowerStr,
            durationStr = durationStr,
            isRecording = isRecording
        )

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("XenoPowerMeter")
            .setContentText("$voltageStr | $currentStr | $powerStr")
            .setOngoing(true)

        val picsBundle = Bundle().apply {
            putParcelable("miui.focus.pic_imageText", Icon.createWithResource(context, R.drawable.app_icon))
            putParcelable("miui.focus.pic_small", Icon.createWithResource(context, R.drawable.app_icon))
            putParcelable("miui.focus.pic_small_dark", Icon.createWithResource(context, R.drawable.app_icon))
        }

        val intent = Intent(context, com.irregular.xenopowermeter.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.app_icon),
            "打开应用",
            pendingIntent
        ).build()

        val actionsBundle = Bundle().apply {
            putParcelable("miui.focus.action_open_app", action)
        }

        builder.addExtras(Bundle().apply {
            putBundle("miui.focus.pics", picsBundle)
            putBundle("miui.focus.actions", actionsBundle)
        })

        val notification = builder.build()
        notification.extras.putString("miui.focus.param", islandParams)

        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildIslandParams(
        context: Context,
        voltageStr: String,
        currentStr: String,
        powerStr: String,
        avgPowerStr: String,
        durationStr: String,
        isRecording: Boolean
    ): String {
        val status = if (isRecording) "Recording" else "Monitoring"
        val now = System.currentTimeMillis()

        return JSONObject().apply {
            put("param_v2", JSONObject().apply {
                put("business", "power_meter")
                put("protocol", 1)
                put("enableFloat", false)
                put("updatable", true)
                put("outEffectSrc", "")
                put("reopen", "reopen")
                put("sequence", now)

                put("baseInfo", JSONObject().apply {
                    put("type", 2)
                    put("title", "XenoPowerMeter - $status")
                    put("content", getRandomQuote())
                    put("colorTitle", "#111111")
                    put("colorTitleDark", "#ffffff")
                    put("colorContent", "#333333")
                    put("colorContentDark", "#cccccc")
                    put("showDivider", true)
                })

                put("hintInfo", JSONObject().apply {
                    put("type", 2)
                    put("content", "Voltage")
                    put("title", voltageStr)
                    put("subContent", "Current")
                    put("subTitle", currentStr)
                    put("colorContent", "#666666")
                    put("colorContentDark", "#aaaaaa")
                    put("colorTitle", "#222222")
                    put("colorTitleDark", "#eeeeee")
                    put("colorSubContent", "#666666")
                    put("colorSubContentDark", "#aaaaaa")
                    put("colorSubTitle", "#222222")
                    put("colorSubTitleDark", "#eeeeee")
                    put("actionInfo", JSONObject().apply {
                        put("actionTitle", "打开应用")
                        put("actionIntentType", 1)
                        put("action", "miui.focus.action_open_app")
                    })
                })

                put("picInfo", JSONObject().apply {
                    put("type", 1)
                    put("pic", "miui.focus.pic_imageText")
                })

                put("param_island", JSONObject().apply {
                    put("islandProperty", 1)
                    put("islandTimeout", 3600)
                    put("bigIslandArea", JSONObject().apply {
                        put("templateNo", 2)
                        put("imageTextInfoLeft", JSONObject().apply {
                            put("type", 1)
                            put("textInfo", JSONObject().apply {
                                put("title", voltageStr)
                                put("content", "")
                                put("showHighlightColor", false)
                                put("narrowFont", false)
                            })
                            put("picInfo", JSONObject().apply {
                                put("type", 1)
                                put("pic", "miui.focus.pic_imageText")
                            })
                        })
                        put("textInfo", JSONObject().apply {
                            put("frontTitle", "")
                            put("title", currentStr)
                            put("content", "")
                            put("showHighlightColor", false)
                            put("narrowFont", false)
                        })
                    })
                    put("smallIslandArea", JSONObject().apply {
                        put("picInfo", JSONObject().apply {
                            put("type", 1)
                            put("pic", "miui.focus.pic_imageText")
                        })
                    })
                })
            })
        }.toString()
    }

    fun cancelNotification(context: Context) {
        notificationManager?.cancel(NOTIFICATION_ID)
        currentQuote = ""
        recordingStartTimeMs = 0L
    }
}
