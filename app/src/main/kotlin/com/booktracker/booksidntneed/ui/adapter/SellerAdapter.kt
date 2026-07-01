package com.booktracker.booksidntneed.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.network.SellerOption

class SellerAdapter(
    private val sellers: List<SellerOption>,
    private val onSellerSelected: (SellerOption) -> Unit
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

        fun bind(seller: SellerOption) {
            nameTextView.text = seller.sellerName
            val context = itemView.context
            val priceText = context.getString(R.string.book_price, seller.price)
            val conditionText = seller.condition.takeUnless { it.isNullOrBlank() }
                ?: context.getString(R.string.unknown_condition)
            val locationText = seller.location.takeUnless { it.isNullOrBlank() }
                ?: context.getString(R.string.unknown_location)
            detailsTextView.text = context.getString(R.string.seller_details, priceText, conditionText, locationText)
        }
    }
} 
