package com.zigpoll.example

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.zigpoll.Zigpoll

/* Demo app for the Zigpoll Android SDK. Replace YOUR_ACCOUNT_ID and
   YOUR_SURVEY_ID with values from your dashboard (API delivery survey).

   UI-test hook: adb shell am start -n com.zigpoll.example/.MainActivity --ez autotrigger true */

class MainActivity : AppCompatActivity() {

    private val pollId = "YOUR_SURVEY_ID"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Zigpoll.configure(this, "YOUR_ACCOUNT_ID", preview = true)
        Zigpoll.identify("example-user-1", mapOf("email" to "user@example.com"))

        Zigpoll.onLoad = { Log.d("demo", "survey loaded") }
        Zigpoll.onComplete = { responses -> Log.d("demo", "completed: ${responses.size} responses") }
        Zigpoll.onClose = { responses -> Log.d("demo", "closed: ${responses.size} responses") }
        Zigpoll.onError = { error -> Log.d("demo", "error: $error") }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        layout.addView(TextView(this).apply {
            text = "Zigpoll SDK Demo"
            textSize = 22f
            gravity = Gravity.CENTER
        })

        layout.addView(Button(this).apply {
            text = "Trigger survey"
            setOnClickListener { Zigpoll.trigger(pollId, this@MainActivity) }
        })

        layout.addView(Button(this).apply {
            text = "Dismiss"
            setOnClickListener { Zigpoll.dismiss() }
        })

        layout.addView(Button(this).apply {
            text = "Logout"
            setOnClickListener { Zigpoll.logout() }
        })

        setContentView(layout)

        if (intent.getBooleanExtra("autotrigger", false)) {
            Handler(Looper.getMainLooper()).postDelayed({
                Zigpoll.trigger(pollId, this)
            }, 1500)
        }
    }
}
