package com.example.bankscraperlogger.history

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.TextView

class HistorySuggestionAdapter(
    context: Context,
    private val store: HistoryStore,
) : ArrayAdapter<HistorySuggestionAdapter.Suggestion>(context, android.R.layout.simple_dropdown_item_1line) {

    data class Suggestion(
        val url: String,
        val title: String?,
    ) {
        val label: String = buildString {
            val t = title?.trim().orEmpty()
            if (t.isNotEmpty()) {
                append(t)
                append(" — ")
            }
            append(url)
        }
    }

    private val items: MutableList<Suggestion> = mutableListOf()

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Suggestion? = items.getOrNull(position)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(android.R.layout.simple_dropdown_item_1line, parent, false)
        val tv = view.findViewById<TextView>(android.R.id.text1)
        tv.text = getItem(position)?.label.orEmpty()
        tv.isSingleLine = true
        return view
    }

    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val q = constraint?.toString().orEmpty()
                val entries = store.search(q, limit = 8)
                val suggestions = entries.map { e -> Suggestion(url = e.url, title = e.title) }
                return FilterResults().apply {
                    values = suggestions
                    count = suggestions.size
                }
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                items.clear()
                val list = results?.values as? List<Suggestion> ?: emptyList()
                items.addAll(list)
                notifyDataSetChanged()
            }

            override fun convertResultToString(resultValue: Any?): CharSequence {
                val s = resultValue as? Suggestion
                return s?.url ?: ""
            }
        }
    }
}

