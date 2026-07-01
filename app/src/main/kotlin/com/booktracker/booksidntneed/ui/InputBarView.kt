package com.booktracker.booksidntneed.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.booktracker.booksidntneed.databinding.ViewInputBarBinding
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText

class InputBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    private val binding: ViewInputBarBinding = ViewInputBarBinding.inflate(LayoutInflater.from(context), this, true)

    val urlEditText: TextInputEditText get() = binding.urlEditText
    val addBookButton: FloatingActionButton get() = binding.addBookButton
    val addBookStatusText: TextView get() = binding.addBookStatusText

}