package com.medansafe.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        startLogoAnimations()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        setupFirestorePersistence()
        checkNotificationPermission()
    }

    private fun setupFirestorePersistence() {
        val settings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
            .build()
        FirebaseFirestore.getInstance().firestoreSettings = settings
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startLogoAnimations()
            }
        } else {
            startLogoAnimations()
        }
    }

    private fun startLogoAnimations() {
        val logo = findViewById<View>(R.id.img_logo)
        val appName = findViewById<View>(R.id.ll_app_name)
        val tagline = findViewById<View>(R.id.tv_tagline)
        val dot1 = findViewById<View>(R.id.dot1)
        val dot2 = findViewById<View>(R.id.dot2)
        val dot3 = findViewById<View>(R.id.dot3)

        logo.alpha = 0f
        appName.alpha = 0f
        tagline.alpha = 0f
        dot1.alpha = 0.4f
        dot2.alpha = 0.4f
        dot3.alpha = 0.4f

        val logoAnimation = AnimationSet(true).apply {
            addAnimation(AlphaAnimation(0f, 1f))
            addAnimation(ScaleAnimation(0.8f, 1f, 0.8f, 1f, 
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f))
            duration = 1000
            interpolator = AccelerateDecelerateInterpolator()
            fillAfter = true
        }

        logo.startAnimation(logoAnimation)
        logo.alpha = 1f

        Handler(Looper.getMainLooper()).postDelayed({
            appName.animate().alpha(1f).setDuration(800).start()
            tagline.animate().alpha(0.6f).setDuration(800).start()
        }, 500)

        fun animateDot(view: View, delay: Long, isLast: Boolean = false) {
            Handler(Looper.getMainLooper()).postDelayed({
                view.animate()
                    .alpha(1f)
                    .setDuration(300)
                    .withEndAction {
                        view.backgroundTintList = android.content.res.ColorStateList.valueOf(
                            "#C0392B".toColorInt()
                        )
                        if (isLast) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                checkSessionAndNavigate()
                            }, 600)
                        }
                    }
                    .start()
            }, delay)
        }

        animateDot(dot1, 1200)
        animateDot(dot2, 1500)
        animateDot(dot3, 1800, true)
    }

    private fun checkSessionAndNavigate() {
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            FirebaseFirestore.getInstance().collection("users").document(currentUser.uid).get()
                .addOnSuccessListener { doc ->
                    if (doc.exists()) {
                        val isDisabled = doc.getBoolean("isDisabled") ?: false
                        if (isDisabled) {
                            FirebaseAuth.getInstance().signOut()
                            startActivity(Intent(this, LoginActivity::class.java))
                        } else {
                            val isAdmin = doc.getBoolean("isAdmin") ?: false
                            if (isAdmin) {
                                startActivity(Intent(this, AdminDashboardActivity::class.java))
                            } else {
                                startActivity(Intent(this, HomeActivity::class.java))
                            }
                        }
                    } else {
                        // Document doesn't exist, user needs to complete profile (e.g. Google Login was interrupted)
                        startActivity(Intent(this, LoginActivity::class.java).apply {
                            putExtra("FORCE_COMPLETE_PROFILE", true)
                        })
                    }
                    finish()
                }
                .addOnFailureListener {
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
        } else {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}
