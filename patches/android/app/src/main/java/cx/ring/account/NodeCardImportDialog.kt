/*
 *  F-904 带外加好友 —— 导入节点名片对话框（纯代码构造 UI，无新增布局文件）
 *  解析+校验用 NodeCardCodec（纯函数）；加好友通过 listener 交回 Fragment 调现有
 *  AccountService.addContact（0 daemon / 0 SWIG 改动）。
 */
package cx.ring.account

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cx.ring.R
import cx.ring.utils.NodeCardCodec

class NodeCardImportDialog : DialogFragment() {

    interface Listener {
        fun onCardConfirmed(card: NodeCardCodec.Card)
    }

    private var listener: Listener? = null
    private var parsed: NodeCardCodec.Card? = null
    private var positiveBtn: android.widget.Button? = null
    private var presetText: String? = null
    private var titleOverride: String? = null
    private var selfId: String? = null

    fun setListener(l: Listener) { listener = l }

    /** F-904 self 守卫：拒绝把自己的名片加成好友 */
    fun setSelfId(id: String) { selfId = id }

    /** F-904 剪贴板自动导入：打开时预填文本（触发实时校验） */
    fun setPresetText(text: String) { presetText = text }

    /** F-904 剪贴板自动导入：自定义标题（如"剪贴板中发现节点名片"） */
    fun setTitleTag(title: String) { titleOverride = title }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()

        val input = EditText(ctx).apply {
            hint = getString(R.string.nodecard_hint)
            minLines = 3
            maxLines = 6
            setPadding(48, 32, 48, 16)
        }
        val preview = TextView(ctx).apply {
            textSize = 14f
            setPadding(48, 16, 48, 24)
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(input)
            addView(preview)
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(titleOverride ?: getString(R.string.nodecard_import_title))
            .setView(container)
            .setMessage(R.string.nodecard_note)
            .setPositiveButton(R.string.nodecard_add, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                if (text.isBlank()) {
                    preview.text = ""
                    parsed = null
                    updateButton(enabled = false)
                    return
                }
                when (val r = NodeCardCodec.decode(text)) {
                    is NodeCardCodec.Result.Ok -> {
                        // F-904 self 守卫：自己的名片不允许添加
                        if (r.card.id == selfId) {
                            parsed = null
                            preview.text = getString(R.string.nodecard_self)
                            updateButton(enabled = false)
                            return
                        }
                        parsed = r.card
                        android.util.Log.d("NodeCardImportDialog", "F904PRESET: parsed OK id=${r.card.id.take(12)} self=${selfId?.take(12)}")
                        preview.text = if (r.card.n.isNullOrEmpty())
                            getString(R.string.nodecard_preview_noname, r.card.id)
                        else
                            getString(R.string.nodecard_preview, r.card.n, r.card.id)
                        preview.append("\n" + getString(R.string.nodecard_valid))
                        updateButton(enabled = true)
                    }
                    is NodeCardCodec.Result.Error -> {
                        parsed = null
                        preview.text = getString(errorMessage(r.reason))
                        updateButton(enabled = false)
                    }
                }
            }
        })
        // F-904 修复：预填必须在 TextWatcher 注册之后，否则 afterTextChanged
        // 不触发 → parsed 恒为 null → 预览空白 + 添加按钮灰色。
        presetText?.let { input.setText(it) }

        dialog.setOnShowListener {
            positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            updateButton(enabled = parsed != null)
            positiveBtn?.setOnClickListener {
                val card = parsed
                if (card == null) {
                    Toast.makeText(ctx, R.string.nodecard_err_empty, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                listener?.onCardConfirmed(card)
                dialog.dismiss()
            }
        }
        return dialog
    }

    private fun updateButton(enabled: Boolean) { positiveBtn?.isEnabled = enabled }

    private fun errorMessage(reason: NodeCardCodec.Reason): Int = when (reason) {
        NodeCardCodec.Reason.EMPTY -> R.string.nodecard_err_empty
        NodeCardCodec.Reason.BAD_PREFIX -> R.string.nodecard_err_prefix
        NodeCardCodec.Reason.TOO_LONG, NodeCardCodec.Reason.BAD_BASE64,
        NodeCardCodec.Reason.BAD_JSON, NodeCardCodec.Reason.NO_ID, NodeCardCodec.Reason.BAD_ID -> R.string.nodecard_err_bad
        NodeCardCodec.Reason.BAD_MAGIC -> R.string.nodecard_err_version
        NodeCardCodec.Reason.BAD_FINGERPRINT -> R.string.nodecard_err_bad
    }

    companion object {
        fun newInstance() = NodeCardImportDialog()

        /** F-904 剪贴板自动导入：带预填文本创建 */
        fun newInstanceWithText(text: String): NodeCardImportDialog {
            val dlg = NodeCardImportDialog()
            val args = Bundle()
            args.putString(ARG_PRESET_TEXT, text)
            dlg.arguments = args
            return dlg
        }

        private const val ARG_PRESET_TEXT = "preset_text"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        presetText = arguments?.getString(ARG_PRESET_TEXT)
    }
}
