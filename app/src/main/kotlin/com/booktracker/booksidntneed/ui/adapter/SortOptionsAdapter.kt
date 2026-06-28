package com.booktracker.booksidntneed.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.booktracker.booksidntneed.R

class SortOptionsAdapter(
    private val sortOptions: List<String>,
    private val onOptionClick: (Int) -> Unit
) : RecyclerView.Adapter<SortOptionsAdapter.SortOptionViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SortOptionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sort_option, parent, false) as TextView
        return SortOptionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SortOptionViewHolder, position: Int) {
        holder.bind(sortOptions[position], position)
    }

    override fun getItemCount(): Int = sortOptions.size

    inner class SortOptionViewHolder(private val textView: TextView) : RecyclerView.ViewHolder(textView) {

        fun bind(option: String, position: Int) {
            textView.text = option
            textView.setOnClickListener {
                onOptionClick(position)
            }
        }
    }
} 