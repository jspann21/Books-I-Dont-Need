package com.booktracker.booksidntneed.ui.dialog

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import androidx.fragment.app.DialogFragment
import androidx.core.view.doOnPreDraw
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.ui.ThemeTransitionSnapshot
import com.booktracker.booksidntneed.utils.AutoUpdatePreferences
import com.booktracker.booksidntneed.utils.AppThemeMode
import com.booktracker.booksidntneed.utils.ThemePreferences
import com.booktracker.booksidntneed.work.AutoUpdateScheduler
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import android.content.pm.PackageManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsDialogFragment : DialogFragment() {
    interface SettingsDialogListener {
        fun onExportData()
        fun onImportData()
        fun onClearData()
    }

    private var listener: SettingsDialogListener? = null
    private var backgroundDimView: View? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(
            STYLE_NO_TITLE,
            if (ThemeTransitionSnapshot.isThemeTransition) {
                R.style.ThemeOverlay_BooksIDontNeed_Dialog_NoAnimation
            } else {
                R.style.ThemeOverlay_BooksIDontNeed_Dialog
            }
        )
    }

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
        val autoUpdateSwitch = view.findViewById<MaterialSwitch>(R.id.autoUpdateSwitch)
        val timeCard = view.findViewById<MaterialCardView>(R.id.updateTimeCard)
        val timeText = view.findViewById<TextView>(R.id.updateTimeText)
        val updateAllCard = view.findViewById<MaterialCardView>(R.id.updateAllNowCard)
        val themeSystemButton = view.findViewById<RadioButton>(R.id.themeSystemButton)
        val themeLightButton = view.findViewById<RadioButton>(R.id.themeLightButton)
        val themeDarkButton = view.findViewById<RadioButton>(R.id.themeDarkButton)
        val themeSelectedIndicator = view.findViewById<View>(R.id.themeSelectedIndicator)
        val themeDividerSystemLight = view.findViewById<View>(R.id.themeDividerSystemLight)
        val themeDividerLightDark = view.findViewById<View>(R.id.themeDividerLightDark)

        setupThemeToggle(
            themeSelectedIndicator,
            themeSystemButton,
            themeLightButton,
            themeDarkButton,
            themeDividerSystemLight,
            themeDividerLightDark
        )

        viewLifecycleOwner.lifecycleScope.launch {
            val enabled = AutoUpdatePreferences.isEnabled(requireContext()).first()
            val minutes = AutoUpdatePreferences.timeMinutes(requireContext()).first()
            autoUpdateSwitch.isChecked = enabled
            timeText.text = formatMinutes(minutes)
            updatePriceAndTimeCardState(enabled, timeCard, timeText)

            // Register AFTER setting the initial value so that the programmatic
            // setChecked above does not trigger a redundant schedule update.
            autoUpdateSwitch.setOnCheckedChangeListener { _, isChecked ->
                viewLifecycleOwner.lifecycleScope.launch {
                    AutoUpdatePreferences.setEnabled(requireContext(), isChecked)
                    updatePriceAndTimeCardState(isChecked, timeCard, timeText)
                    val mins = AutoUpdatePreferences.timeMinutes(requireContext()).first()
                    if (isChecked) {
                        requestNotificationPermissionIfNeeded()
                        AutoUpdateScheduler.scheduleDaily(
                            requireContext(),
                            mins,
                            androidx.work.ExistingPeriodicWorkPolicy.UPDATE
                        )
                    } else {
                        AutoUpdateScheduler.cancel(requireContext())
                    }
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
                            // Update the schedule without cancelling a run in progress.
                            AutoUpdateScheduler.scheduleDaily(
                                requireContext(),
                                newMinutes,
                                androidx.work.ExistingPeriodicWorkPolicy.UPDATE
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

            val (manualWork, automaticWork) = withContext(Dispatchers.IO) {
                val manual = workManager
                    .getWorkInfosForUniqueWork(AutoUpdateScheduler.UNIQUE_MANUAL_WORK_NAME)
                    .get()
                val automatic = listOf(
                    AutoUpdateScheduler.UNIQUE_WORK_NAME,
                    AutoUpdateScheduler.UNIQUE_ONE_TIME_NAME
                ).flatMap { uniqueWorkName ->
                    workManager.getWorkInfosForUniqueWork(uniqueWorkName).get()
                }
                manual to automatic
            }

            val updateIsActive =
                manualWork.any { !it.state.isFinished } ||
                    automaticWork.any { it.state == androidx.work.WorkInfo.State.RUNNING }

            if (updateIsActive) {
                try {
                    com.google.android.material.snackbar.Snackbar.make(
                        requireView(),
                        "Price update is already running",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                    ).show()
                } catch (_: Exception) { }
                return@launch
            }

            // Running now covers today's update, so remove a same-day request that
            // is still waiting rather than scraping the entire library twice.
            withContext(Dispatchers.IO) {
                workManager
                    .cancelUniqueWork(AutoUpdateScheduler.UNIQUE_ONE_TIME_NAME)
                    .result
                    .get()
            }
            requestNotificationPermissionIfNeeded()
            AutoUpdateScheduler.enqueueManual(requireContext())

            dismiss()
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

    override fun onDestroyView() {
        removeBackgroundDimLayer()
        if (!ThemeTransitionSnapshot.isThemeTransition) {
            ThemeTransitionSnapshot.clearCachedDialogLayer()
        }
        super.onDestroyView()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < 33) return

        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(permission)
        }
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
        DialogStyling.apply(dialog)
        dialog?.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val dimView = showBackgroundDimLayer()
        // The full-screen transition overlay handles the visual fade. Keep
        // the restored dialog visible underneath it and suppress only the
        // normal dialog window animation.
        if (ThemeTransitionSnapshot.isThemeTransition) {
            dialog?.window?.setWindowAnimations(0)
            dialog?.window?.decorView?.alpha = 1f
        }
        dialog?.window?.decorView?.doOnPreDraw { dialogDecor ->
            if (isAdded) {
                val captureRestoredState = {
                    dialogDecor.post {
                        if (!isAdded) return@post

                        val dialogWindow = dialog?.window
                        ThemeTransitionSnapshot.cacheDialogWindow(
                            requireActivity(),
                            dialogWindow,
                            MANUAL_DIM_ALREADY_DRAWN
                        )
                        if (ThemeTransitionSnapshot.isThemeTransition) {
                            ThemeTransitionSnapshot.captureRestoredFrame(
                                requireActivity(),
                                dialogWindow,
                                MANUAL_DIM_ALREADY_DRAWN
                            )
                            ThemeTransitionSnapshot.installRestoredBackgroundCover(requireActivity())
                            ThemeTransitionSnapshot.markRestoredDialogReady()
                        }
                    }
                }

                if (ThemeTransitionSnapshot.isThemeTransition && dimView != null) {
                    dimView.doOnPreDraw { captureRestoredState() }
                } else {
                    captureRestoredState()
                }
            }
        }
    }

    private fun setupThemeToggle(
        selectedIndicator: View,
        systemButton: RadioButton,
        lightButton: RadioButton,
        darkButton: RadioButton,
        systemLightDivider: View,
        lightDarkDivider: View
    ) {
        fun selectedIndicatorTranslation(mode: AppThemeMode): Float {
            return when (mode) {
                AppThemeMode.SYSTEM -> 0f
                AppThemeMode.LIGHT -> (systemButton.width + systemLightDivider.width).toFloat()
                AppThemeMode.DARK -> (
                    systemButton.width +
                        systemLightDivider.width +
                        lightButton.width +
                        lightDarkDivider.width
                    ).toFloat()
            }
        }

        fun moveSelectedIndicator(mode: AppThemeMode) {
            selectedIndicator.translationX = selectedIndicatorTranslation(mode)
        }

        fun updateSelectedMode(mode: AppThemeMode) {
            systemButton.isChecked = mode == AppThemeMode.SYSTEM
            lightButton.isChecked = mode == AppThemeMode.LIGHT
            darkButton.isChecked = mode == AppThemeMode.DARK
            applySegmentTextColors(systemButton, systemButton.isChecked)
            applySegmentTextColors(lightButton, lightButton.isChecked)
            applySegmentTextColors(darkButton, darkButton.isChecked)
            selectedIndicator.background = createSegmentBackground(mode)
            if (systemButton.width > 0 && lightButton.width > 0) {
                moveSelectedIndicator(mode)
            } else {
                selectedIndicator.post { moveSelectedIndicator(mode) }
            }
        }

        fun selectMode(selectedMode: AppThemeMode) {
            val appContext = requireContext().applicationContext
            val currentMode = ThemePreferences.getThemeMode(appContext)
            if (selectedMode == currentMode) return

            val changesEffectiveNightMode = ThemePreferences.changesEffectiveNightMode(
                appContext,
                currentMode,
                selectedMode
            )
            updateSelectedMode(selectedMode)
            ThemePreferences.setThemeMode(appContext, selectedMode)

            val hostActivity = activity
            if (hostActivity == null || !changesEffectiveNightMode) {
                ThemePreferences.applyTheme(selectedMode)
                return
            }

            val dialogWindow = dialog?.window
            ThemeTransitionSnapshot.captureAndApply(
                hostActivity,
                selectedMode,
                dialogWindow = dialogWindow,
                dialogDimAmount = MANUAL_DIM_ALREADY_DRAWN
            )
        }

        updateSelectedMode(ThemePreferences.getThemeMode(requireContext()))

        systemButton.setOnClickListener { selectMode(AppThemeMode.SYSTEM) }
        lightButton.setOnClickListener { selectMode(AppThemeMode.LIGHT) }
        darkButton.setOnClickListener { selectMode(AppThemeMode.DARK) }
    }

    private fun applySegmentTextColors(button: RadioButton, selected: Boolean) {
        button.setTextColor(if (selected) themeColor(true) else themeColor(false))
    }

    private fun themeColor(selected: Boolean): Int {
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return ContextCompat.getColor(
            requireContext(),
            when {
                selected && isNightMode -> R.color.md_theme_dark_onSecondaryContainer
                selected -> R.color.md_theme_light_onSecondaryContainer
                isNightMode -> R.color.md_theme_dark_onSurface
                else -> R.color.md_theme_light_onSurface
            }
        )
    }

    private fun createSegmentBackground(mode: AppThemeMode): GradientDrawable {
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val radius = 19f * resources.displayMetrics.density
        val selectedFill = ContextCompat.getColor(
            requireContext(),
            if (isNightMode) R.color.md_theme_dark_secondaryContainer else R.color.md_theme_light_secondaryContainer
        )
        val radii = when (mode) {
            AppThemeMode.SYSTEM -> floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
            AppThemeMode.LIGHT -> FloatArray(8) { 0f }
            AppThemeMode.DARK -> floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(selectedFill)
            cornerRadii = radii
        }
    }

    private fun showBackgroundDimLayer(): View? {
        backgroundDimView?.takeIf { it.parent != null }?.let { return it }
        val decor = activity?.window?.decorView as? ViewGroup ?: return null
        val dimAlpha = (DialogStyling.BACKGROUND_DIM_AMOUNT * 255).toInt()
        backgroundDimView = View(requireContext()).apply {
            setBackgroundColor(Color.argb(dimAlpha, 0, 0, 0))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        decor.addView(
            backgroundDimView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        return backgroundDimView
    }

    private fun removeBackgroundDimLayer() {
        val dimView = backgroundDimView ?: return
        (dimView.parent as? ViewGroup)?.removeView(dimView)
        backgroundDimView = null
    }

} 

private const val MANUAL_DIM_ALREADY_DRAWN = 0f
