package com.booktracker.booksidntneed.ui.dialog

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import androidx.fragment.app.DialogFragment
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.utils.AutoUpdatePreferences
import com.booktracker.booksidntneed.work.AutoUpdateScheduler
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class SettingsDialogFragment : DialogFragment() {
    interface SettingsDialogListener {
        fun onExportData()
        fun onImportData()
        fun onClearData()
    }

    private var listener: SettingsDialogListener? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? SettingsDialogListener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<MaterialCardView>(R.id.exportCard).setOnClickListener {
            listener?.onExportData()
            dismiss()
        }
        view.findViewById<MaterialCardView>(R.id.importCard).setOnClickListener {
            listener?.onImportData()
            dismiss()
        }
        val autoUpdateSwitch = view.findViewById<Switch>(R.id.autoUpdateSwitch)
        val timeCard = view.findViewById<MaterialCardView>(R.id.updateTimeCard)
        val timeText = view.findViewById<TextView>(R.id.updateTimeText)
        val updateAllCard = view.findViewById<MaterialCardView>(R.id.updateAllNowCard)

        viewLifecycleOwner.lifecycleScope.launch {
            val enabled = AutoUpdatePreferences.isEnabled(requireContext()).first()
            val minutes = AutoUpdatePreferences.timeMinutes(requireContext()).first()
            autoUpdateSwitch.isChecked = enabled
            timeText.text = formatMinutes(minutes)
            updatePriceAndTimeCardState(enabled, timeCard, timeText)
        }

        autoUpdateSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewLifecycleOwner.lifecycleScope.launch {
                AutoUpdatePreferences.setEnabled(requireContext(), isChecked)
                updatePriceAndTimeCardState(isChecked, timeCard, timeText)
                val minutes = AutoUpdatePreferences.timeMinutes(requireContext()).first()
                if (isChecked) {
                    // Best effort: request POST_NOTIFICATIONS on Android 13+
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        val permission = android.Manifest.permission.POST_NOTIFICATIONS
                        if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) {
                            notificationPermissionLauncher.launch(permission)
                        }
                    }
                    // Suggest the user exclude from battery optimizations (can be skipped)
                    try {
                        val pm = requireContext().getSystemService(PowerManager::class.java)
                        val pkg = requireContext().packageName
                        val ignoring = pm?.isIgnoringBatteryOptimizations(pkg) ?: false
                        if (!ignoring) {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:$pkg"))
                            // Gracefully attempt; some OEMs may restrict this
                            try { startActivity(intent) } catch (_: Exception) { }
                        }
                    } catch (_: Exception) { }
                    AutoUpdateScheduler.scheduleDaily(
                        requireContext(),
                        minutes,
                        androidx.work.ExistingPeriodicWorkPolicy.REPLACE
                    )
                } else {
                    AutoUpdateScheduler.cancel(requireContext())
                }
            }
        }

        timeCard.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val minutes = AutoUpdatePreferences.timeMinutes(requireContext()).first()
                val hour24 = minutes / 60
                val minute = minutes % 60
                val picker = MaterialTimePicker.Builder()
                    .setTimeFormat(TimeFormat.CLOCK_12H)
                    .setHour(hour24)
                    .setMinute(minute)
                    .setTitleText(getString(R.string.update_time))
                    .build()
                picker.addOnPositiveButtonClickListener {
                    val newMinutes = picker.hour * 60 + picker.minute
                    viewLifecycleOwner.lifecycleScope.launch {
                        AutoUpdatePreferences.setTimeMinutes(requireContext(), newMinutes)
                        timeText.text = formatMinutes(newMinutes)
                        if (AutoUpdatePreferences.isEnabled(requireContext()).first()) {
                            // Replace existing schedule when the user changes the time
                            AutoUpdateScheduler.scheduleDaily(
                                requireContext(),
                                newMinutes,
                                androidx.work.ExistingPeriodicWorkPolicy.REPLACE
                            )
                        }
                    }
                }
                picker.show(parentFragmentManager, "update_time_picker")
            }
        }

        updateAllCard.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.confirm_update_all_title)
                .setMessage(R.string.confirm_update_all_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.run_now) { _, _ ->
                    enqueueManualUpdate()
                }
                .show()
        }
        view.findViewById<Button>(R.id.closeButton).setOnClickListener { dismiss() }
        // If you add a clear data button, wire it up here:
        // view.findViewById<Button>(R.id.clearDataButton)?.setOnClickListener {
        //     listener?.onClearData()
        //     dismiss()
        // }
    }

    private fun enqueueManualUpdate() {
        viewLifecycleOwner.lifecycleScope.launch {
            val workManager = androidx.work.WorkManager.getInstance(requireContext())
            
            // Check if manual update is already running
            val workInfos = workManager.getWorkInfosForUniqueWork(AutoUpdateScheduler.UNIQUE_MANUAL_WORK_NAME).get()
            val isRunning = workInfos.any { 
                it.state == androidx.work.WorkInfo.State.RUNNING || it.state == androidx.work.WorkInfo.State.ENQUEUED 
            }
            
            if (isRunning) {
                // Show message that update is already running
                try {
                    com.google.android.material.snackbar.Snackbar.make(
                        requireView(),
                        "Price update is already running",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                    ).show()
                } catch (_: Exception) { }
                return@launch
            }
            
            // Enqueue new manual update
            val request = androidx.work.OneTimeWorkRequestBuilder<com.booktracker.booksidntneed.work.AutoUpdateWorker>()
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
                .build()
            
            workManager.enqueueUniqueWork(
                AutoUpdateScheduler.UNIQUE_MANUAL_WORK_NAME,
                androidx.work.ExistingWorkPolicy.KEEP,
                request
            )
            
            // Inform user about notification
            try {
                com.google.android.material.snackbar.Snackbar.make(
                    requireView(),
                    getString(R.string.update_all_now_desc),
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                ).show()
            } catch (_: Exception) { }
        }
    }

    private fun updatePriceAndTimeCardState(
        enabled: Boolean,
        timeCard: MaterialCardView,
        timeText: TextView
    ) {
        timeCard.isEnabled = enabled
        timeCard.alpha = if (enabled) 1.0f else 0.5f
        timeText.isEnabled = enabled
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    private fun formatMinutes(minutes: Int): String {
        val hour24 = minutes / 60
        val minute = minutes % 60
        val isAm = hour24 < 12
        var hour12 = hour24 % 12
        if (hour12 == 0) hour12 = 12
        val suffix = if (isAm) "AM" else "PM"
        return String.format("%d:%02d %s", hour12, minute, suffix)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.95).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
} 
