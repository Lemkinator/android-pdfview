package de.lemke.pdfview.sample

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.lemke.pdfview.sample.databinding.DialogPasswordBinding

class PasswordDialog(private val onPasswordEntered: (String) -> Unit) : DialogFragment() {
    private val binding by lazy {
        DialogPasswordBinding.inflate(layoutInflater)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding.validate.setOnClickListener {
            onPasswordEntered.invoke(binding.passwordInputField.text.toString())
            dismiss()
        }
        return MaterialAlertDialogBuilder(requireContext()).setView(binding.root).create()
    }
}
