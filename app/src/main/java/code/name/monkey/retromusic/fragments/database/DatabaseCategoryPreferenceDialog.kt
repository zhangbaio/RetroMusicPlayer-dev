package code.name.monkey.retromusic.fragments.database

import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.CategoryInfoAdapter
import code.name.monkey.retromusic.databinding.PreferenceDialogLibraryCategoriesBinding
import code.name.monkey.retromusic.extensions.colorButtons
import code.name.monkey.retromusic.extensions.materialDialog
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.model.CategoryInfo
import code.name.monkey.retromusic.util.PreferenceUtil

class DatabaseCategoryPreferenceDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = PreferenceDialogLibraryCategoriesBinding.inflate(layoutInflater)
        val categoryAdapter = CategoryInfoAdapter(PreferenceUtil.databaseCategory.toMutableList())

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = categoryAdapter
            categoryAdapter.attachToRecyclerView(this)
        }

        return materialDialog(R.string.database_content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.done) { _, _ -> updateCategories(categoryAdapter.categoryInfos) }
            .setView(binding.root)
            .create()
            .colorButtons()
    }

    private fun updateCategories(categories: List<CategoryInfo>) {
        if (categories.count { it.visible } == 0) {
            showToast(R.string.you_have_to_select_at_least_one_category)
            return
        }
        PreferenceUtil.databaseCategory = categories
        parentFragmentManager.setFragmentResult(
            RESULT_KEY,
            bundleOf(RESULT_UPDATED to true)
        )
    }

    companion object {
        const val RESULT_KEY = "database_category_result"
        const val RESULT_UPDATED = "updated"
        fun newInstance(): DatabaseCategoryPreferenceDialog = DatabaseCategoryPreferenceDialog()
    }
}
