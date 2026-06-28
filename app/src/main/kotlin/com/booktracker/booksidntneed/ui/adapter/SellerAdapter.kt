package com.booktracker.booksidntneed.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.booktracker.booksidntneed.R

class SellerAdapter(
    private val sellers: List<com.booktracker.booksidntneed.network.SellerOption>,
    private val onSellerSelected: (com.booktracker.booksidntneed.network.SellerOption) -> Unit
) : RecyclerView.Adapter<SellerAdapter.SellerViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SellerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_seller, parent, false)
        return SellerViewHolder(view)
    }

    override fun onBindViewHolder(holder: SellerViewHolder, position: Int) {
        val seller = sellers[position]
        holder.bind(seller)
        holder.itemView.setOnClickListener {
            onSellerSelected(seller)
        }
    }

    override fun getItemCount(): Int = sellers.size

    class SellerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.seller_name)
        private val detailsTextView: TextView = itemView.findViewById(R.id.seller_details)

        fun bind(seller: com.booktracker.booksidntneed.network.SellerOption) {
            nameTextView.text = seller.sellerName
            val priceText = "$${String.format("%.2f", seller.price)}"
            val conditionText = if (seller.condition?.isNullOrBlank() == false) seller.condition else "Unknown condition"
            val locationText = if (seller.location?.isNullOrBlank() == false) seller.location else "Unknown location"
            detailsTextView.text = "$priceText • $conditionText • $locationText"
        }
    }
} 