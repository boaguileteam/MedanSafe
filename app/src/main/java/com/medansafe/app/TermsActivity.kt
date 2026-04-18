package com.medansafe.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.medansafe.app.databinding.ActivityTermsBinding

class TermsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTermsBinding
    private var isFromRegister = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTermsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Detect Activity origin
        isFromRegister = intent.getBooleanExtra("from_register", false)

        if (isFromRegister) {
            binding.btnAgree.text = getString(R.string.terms_btn_agree_continue)
        } else {
            binding.btnAgree.text = getString(R.string.terms_btn_agree_close)
        }

        // Back Button
        binding.btnBack.setOnClickListener { finish() }

        // Synchronize Checkbox with Button
        binding.cbAgree.setOnCheckedChangeListener { _, isChecked ->
            binding.btnAgree.isEnabled = isChecked
            binding.btnAgree.animate()
                .alpha(if (isChecked) 1.0f else 0.5f)
                .setDuration(200)
                .start()
        }

        // Agree Button
        binding.btnAgree.setOnClickListener {
            if (isFromRegister) {
                setResult(RESULT_OK)
            }
            finish()
        }
    }
}
