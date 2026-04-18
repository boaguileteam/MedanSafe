package com.medansafe.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import com.medansafe.app.databinding.FragmentVehicleSelectionBinding

class VehicleSelectionFragment : Fragment() {

    private var _binding: FragmentVehicleSelectionBinding? = null
    private val binding get() = _binding!!

    var onVehicleSelected: ((String) -> Unit)? = null
    private var currentVehicle = "Kereta"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVehicleSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        arguments?.getString("selected")?.let {
            currentVehicle = it
        }

        updateUI(currentVehicle)

        binding.cvModeMotor.setOnClickListener {
            currentVehicle = "Kereta"
            updateUI("Kereta")
        }

        binding.cvModeMobil.setOnClickListener {
            currentVehicle = "Mobil"
            updateUI("Mobil")
        }

        binding.btnUseMode.setOnClickListener {
            onVehicleSelected?.invoke(currentVehicle)
        }
    }

    private fun updateUI(vehicle: String) {
        val isKereta = vehicle.contains("Kereta", ignoreCase = true) || vehicle.contains("Motor", ignoreCase = true)

        // Update Kereta Card
        if (isKereta) {
            binding.cvModeMotor.setCardBackgroundColor("#FFF1F0".toColorInt())
            binding.cvModeMotor.strokeColor = "#C0392B".toColorInt()
            binding.cvModeMotor.strokeWidth = (2.5f * resources.displayMetrics.density).toInt()
            binding.cvModeMotor.cardElevation = 6f

            binding.vMotorCircleRing.visibility = View.VISIBLE
            binding.cvMotorIconBg.setCardBackgroundColor(Color.WHITE)
            binding.tvMotorLabel.setTextColor("#C0392B".toColorInt())
            binding.ivCheckMotor.visibility = View.VISIBLE
            binding.ivCheckMotor.setImageResource(R.drawable.ic_check_premium)
        } else {
            binding.cvModeMotor.setCardBackgroundColor(Color.WHITE)
            binding.cvModeMotor.strokeColor = "#F1F5F9".toColorInt()
            binding.cvModeMotor.strokeWidth = (1f * resources.displayMetrics.density).toInt()
            binding.cvModeMotor.cardElevation = 0f

            binding.vMotorCircleRing.visibility = View.GONE
            binding.cvMotorIconBg.setCardBackgroundColor("#F1F5F9".toColorInt())
            binding.tvMotorLabel.setTextColor("#475569".toColorInt())
            binding.ivCheckMotor.visibility = View.INVISIBLE
        }

        // Update Mobil Card
        if (!isKereta) {
            binding.cvModeMobil.setCardBackgroundColor("#F0F9FF".toColorInt())
            binding.cvModeMobil.strokeColor = "#0284C7".toColorInt()
            binding.cvModeMobil.strokeWidth = (2.5f * resources.displayMetrics.density).toInt()
            binding.cvModeMobil.cardElevation = 6f

            binding.vMobilCircleRing.visibility = View.VISIBLE
            binding.cvMobilIconBg.setCardBackgroundColor(Color.WHITE)
            binding.tvMobilLabel.setTextColor("#0369A1".toColorInt())
            binding.ivCheckMobil.visibility = View.VISIBLE
            binding.ivCheckMobil.setImageResource(R.drawable.ic_check_premium)
            binding.ivCheckMobil.imageTintList = ColorStateList.valueOf("#0284C7".toColorInt())
        } else {
            binding.cvModeMobil.setCardBackgroundColor(Color.WHITE)
            binding.cvModeMobil.strokeColor = "#F1F5F9".toColorInt()
            binding.cvModeMobil.strokeWidth = (1f * resources.displayMetrics.density).toInt()
            binding.cvModeMobil.cardElevation = 0f

            binding.vMobilCircleRing.visibility = View.GONE
            binding.cvMobilIconBg.setCardBackgroundColor("#F1F5F9".toColorInt())
            binding.tvMobilLabel.setTextColor("#475569".toColorInt())
            binding.ivCheckMobil.visibility = View.INVISIBLE
        }

        if (isKereta) {
            binding.btnUseMode.text = "Gunakan Mode Kereta"
            binding.btnUseMode.backgroundTintList = ColorStateList.valueOf("#C0392B".toColorInt())
            binding.tvHintText.text = "Mode Kereta akan menyorot insiden begal & jalan gelap. Cocok untuk lincah di jalan kecil."
        } else {
            binding.btnUseMode.text = "Gunakan Mode Mobil"
            binding.btnUseMode.backgroundTintList = ColorStateList.valueOf("#0284C7".toColorInt())
            binding.tvHintText.text = "Mode Mobil fokus pada rute jalan utama dan info kecelakaan. Lebih aman untuk roda empat."
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(selected: String): VehicleSelectionFragment {
            return VehicleSelectionFragment().apply {
                arguments = Bundle().apply {
                    putString("selected", selected)
                }
            }
        }
    }
}
