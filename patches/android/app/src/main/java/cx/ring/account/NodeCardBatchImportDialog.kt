/*
 *  F-904 带外加好友 —— 文件批量导入对话框（AC-4）
 *
 *  流程：SAF 选 contacts.jamicards → NodeCardCodec.parseFile 拆出多张 →
 *        逐张 decode 校验 → 列表勾选 → 批量交回 Fragment 调现有 addContact。
 *  重复项（已在现有好友，或文件内先出现的同 ID）标记"已添加"且默认不勾选。
 *  0 daemon / 0 SWIG 改动：加好友完全复用 AccountService.addContact。
 */
package cx.ring.account

import android.app.Dialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cx.ring.R
import cx.ring.utils.NodeCardCodec
import org.json.JSONException

class NodeCardBatchImportDialog : DialogFragment() {

    interface Listener {
        /** 用户勾选并确认要添加的名片（已排除重复项） */
        fun onCardsConfirmed(cards: List<NodeCardCodec.Card>)
    }

    private var listener: Listener? = null
    private var existingIds: Set<String> = emptySet()
    private var selfId: String? = null

    // 每张名片对应一行勾选框；data class 避免位置错位
    private data class Row(val card: NodeCardCodec.Card, val box: CheckBox, val alreadyAdded: Boolean)
    private val rows = mutableListOf<Row>()

    private var rowsContainer: LinearLayout? = null
    private var summaryView: TextView? = null
    private var positiveBtn: Button? = null

    // 选文件：json / 纯文本 / 任意（用户可能把后缀改成 .txt 发来）
    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext()
        val content = try {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (e: Exception) {
            Toast.makeText(ctx, R.string.nodecard_batch_file_error, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        if (content.isNullOrBlank()) {
            Toast.makeText(ctx, R.string.nodecard_batch_file_error, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        render(content)
    }

    fun setListener(l: Listener) { listener = l }
    fun setExistingIds(ids: Set<String>) { existingIds = ids }
    /** F-904 self 守卫：自己的名片在批量列表中标记并禁用 */
    fun setSelfId(id: String) { selfId = id }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()

        val chooseBtn = Button(ctx).apply {
            text = getString(R.string.nodecard_choose_file)
            isAllCaps = false
            setOnClickListener { openFileLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
        }
        summaryView = TextView(ctx).apply {
            textSize = 13f
            setPadding(48, 16, 48, 8)
        }
        rowsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(ctx).apply { addView(rowsContainer) }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(chooseBtn)
            addView(summaryView)
            addView(scroll)
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.nodecard_batch_import_title)
            .setView(container)
            .setMessage(R.string.nodecard_batch_note)
            .setPositiveButton(R.string.nodecard_add_selected, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            positiveBtn = dialog.getButton(Dialog.BUTTON_POSITIVE)
            updateState()
            positiveBtn?.setOnClickListener {
                val selected = rows.filter { !it.alreadyAdded && it.box.isChecked }.map { it.card }
                if (selected.isEmpty()) {
                    Toast.makeText(ctx, R.string.nodecard_batch_none_selected, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                listener?.onCardsConfirmed(selected)
                dialog.dismiss()
            }
        }
        return dialog
    }

    /** 解析文件 JSON → 逐张校验 → 生成勾选列表 */
    private fun render(content: String) {
        val ctx = requireContext()
        val texts: List<String> = try {
            NodeCardCodec.parseFile(content)
        } catch (e: JSONException) {
            Toast.makeText(ctx, R.string.nodecard_batch_file_error, Toast.LENGTH_SHORT).show()
            clearRows()
            return
        }
        if (texts.isEmpty()) {
            Toast.makeText(ctx, R.string.nodecard_batch_no_valid, Toast.LENGTH_SHORT).show()
            clearRows()
            return
        }

        rows.clear()
        rowsContainer?.removeAllViews()
        val seen = existingIds.toMutableSet() // 文件内同 ID 第二张也视为"已添加"
        var validCount = 0
        var selfCount = 0
        for (cardText in texts) {
            val card = when (val r = NodeCardCodec.decode(cardText)) {
                is NodeCardCodec.Result.Ok -> r.card
                is NodeCardCodec.Result.Error -> continue // 单张损坏则跳过，不影响其它
            }
            validCount++
            // F-904 self 守卫：自己的名片不能勾选添加
            val isSelf = card.id == selfId
            if (isSelf) selfCount++
            val dup = seen.contains(card.id) || isSelf
            seen.add(card.id)
            val label = buildLabel(card, dup, isSelf)
            val box = CheckBox(ctx).apply {
                text = label
                isChecked = !dup
                isEnabled = !dup
                setPadding(48, 12, 48, 12)
            }
            rows.add(Row(card, box, dup))
            rowsContainer?.addView(box)
        }

        if (rows.isEmpty()) {
            Toast.makeText(ctx, R.string.nodecard_batch_no_valid, Toast.LENGTH_SHORT).show()
        }
        var summary = getString(R.string.nodecard_batch_summary, validCount, rows.size)
        if (selfCount > 0)
            summary += "\n" + getString(R.string.nodecard_self_skipped, selfCount)
        summaryView?.text = summary
        updateState()
    }

    private fun buildLabel(card: NodeCardCodec.Card, dup: Boolean, isSelf: Boolean): String {
        val shortId = if (card.id.length > 8) card.id.substring(0, 8) + "…" else card.id
        val name = if (card.n.isNullOrEmpty()) getString(R.string.nodecard_preview_noname_short) else card.n
        val base = getString(R.string.nodecard_batch_row, name, shortId)
        return when {
            isSelf -> "$base  [${getString(R.string.nodecard_self)}]"
            dup -> "$base  [${getString(R.string.nodecard_already_added)}]"
            else -> base
        }
    }

    private fun clearRows() {
        rows.clear()
        rowsContainer?.removeAllViews()
        summaryView?.text = ""
        updateState()
    }

    private fun updateState() {
        positiveBtn?.isEnabled = rows.any { !it.alreadyAdded }
    }

    companion object {
        fun newInstance() = NodeCardBatchImportDialog()
    }
}
