package com.sesliasistan.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val prefs = Prefs(this)

        val apiKey = findViewById<EditText>(R.id.apiKey)
        val model = findViewById<EditText>(R.id.model)
        val name = findViewById<EditText>(R.id.assistantName)
        val autoListen = findViewById<CheckBox>(R.id.autoListen)
        val speak = findViewById<CheckBox>(R.id.speak)
        val confirmSms = findViewById<CheckBox>(R.id.confirmSms)
        val wakeWord = findViewById<CheckBox>(R.id.wakeWord)
        wakeWord.isChecked = prefs.wakeWord

        apiKey.setText(prefs.apiKey)
        model.setText(prefs.model)
        name.setText(prefs.assistantName)
        autoListen.isChecked = prefs.autoListen
        speak.isChecked = prefs.speak
        confirmSms.isChecked = prefs.confirmSms

        findViewById<Button>(R.id.btnGetKey).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnAssistant).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            prefs.apiKey = apiKey.text.toString()
            prefs.model = model.text.toString()
            prefs.assistantName = name.text.toString()
            prefs.autoListen = autoListen.isChecked
            prefs.speak = speak.isChecked
            prefs.confirmSms = confirmSms.isChecked
            prefs.wakeWord = wakeWord.isChecked
            if (!prefs.wakeWord) WakeService.stop(this)
            Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
