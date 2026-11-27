/*
 *  F-904 带外加好友 —— 导出节点名片对话框（纯代码构造 UI，无新增布局文件）
 */
package cx.ring.account

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.DialogFragment
import cx.ring.R
import cx.ring.utils.NodeCardCodec

class NodeCardExportDialog : DialogFragment() {

    // AC-2: 批量文件内容（含本张名片），由 SAF CreateDocument 写出
    private var cardFileJson: String = ""
    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null || cardFileJson.isEmpty()) return@registerForActivityResult
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { os ->
                os.write(cardFileJson.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(requireContext(), R.string.nodecard_file_saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.nodecard_file_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val id = requireArguments().getString(ARG_ID).orEmpty()
        val name = requireArguments().getString(ARG_NAME)
        val ver = requireArguments().getString(ARG_VER)

        val card = NodeCardCodec.Card(
            id = id,
            n = name?.ifEmpty { null },
            ver = ver,
            ts = System.currentTimeMillis()
        )
        val cardText = NodeCardCodec.encode(card) // v1: 裸 JSON base64url，跨端兼容
        // AC-2: 同一张名片也生成文件载体（contacts.jamicards），供对方批量导入
        cardFileJson = NodeCardCodec.toFile(listOf(card), System.currentTimeMillis())

        val tv = TextView(ctx).apply {
            text = cardText
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(48, 32, 48, 32)
        }
        val scroll = ScrollView(ctx).apply { addView(tv) }
        val btnSaveFile = Button(ctx).apply {
            text = getString(R.string.nodecard_save_file)
            isAllCaps = false
            setOnClickListener { saveFileLauncher.launch("contacts.jamicards") }
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll)
            addView(btnSaveFile)
        }

        return MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.nodecard_export_title)
            .setView(container)
            .setMessage(R.string.nodecard_note)
            .setPositiveButton(R.string.nodecard_copy, null)
            .setNeutralButton(R.string.nodecard_share, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(Dialog.BUTTON_POSITIVE).setOnClickListener {
                        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("node_card", cardText))
                        Toast.makeText(ctx, R.string.nodecard_copy, Toast.LENGTH_SHORT).show()
                    }
                    getButton(Dialog.BUTTON_NEUTRAL).setOnClickListener {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, cardText)
                        }
                        startActivity(Intent.createChooser(intent, getString(R.string.share_via)))
                    }
                }
            }
    }

    companion object {
        private const val ARG_ID = "id"
        private const val ARG_NAME = "name"
        private const val ARG_VER = "ver"

        fun newInstance(id: String, name: String?, ver: String?) = NodeCardExportDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_ID, id)
                putString(ARG_NAME, name)
                putString(ARG_VER, ver)
            }
        }
    }
}
