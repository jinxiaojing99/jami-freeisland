/*
 * F-904 带外加好友 —— 导入节点名片对话框（Qt/QML）
 *
 * 粘贴名片串（JAMICARD1:...）→ 实时校验预览 → 添加为联系人。
 * 加好友走原生链路：ConversationsAdapter.setFilter(id) 对 40 位 ID
 * 同步生成临时联系人/会话（searchResults），再由
 * LRCInstance.makeConversationPermanent(id) 发送联系人请求
 * （conversationmodel.cpp: makePermanent → sendContactRequest）。
 * 已是好友用 ConversationsAdapter.dialogId 判重（官方 QML 惯用法）。
 */
import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

import net.jami.Adapters 1.1
import net.jami.Constants 1.1

import "../../commoncomponents"
import "../../net/jami/Constants/NodeCardCodec.js" as NodeCardCodec

BaseModalDialog {
    id: root

    property string cardText: ""
    property bool cardValid: false
    property string cardId: ""
    property string cardName: ""
    // F-904 淘口令式自动导入：预填文本 + 自定义标题
    property string presetText: ""
    property string titleOverride: ""
    // popupContent 是 Loader.sourceComponent（独立 QML 作用域），根作用域不能按 id
    // 访问 cardInput / previewText。预览内容改由这两个根属性经绑定传入 popupContent。
    property string previewMsg: ""
    property color previewColor: JamiTheme.textColor

    titleText: titleOverride !== "" ? titleOverride : JamiStrings.nodeCardImportTitle

    function openWithParameters() {
        cardText = "";
        cardValid = false;
        cardId = "";
        cardName = "";
        // presetText 由 presentDialog 在创建时注入，直接据此刷新预览。
        refreshPreview(presetText);
        open();
    }

    // 参数化：文本由 popupContent 内部 onTextChanged 传入，避免跨作用域按 id 读取。
    function refreshPreview(input) {
        var t = (input || "").trim();
        cardText = t;
        if (t === "") {
            cardValid = false;
            previewMsg = "";
            return;
        }
        var r = NodeCardCodec.decode(t);
        if (r.ok) {
            // F-904 self 守卫：自己的名片不能添加自己为联系人
            if (r.card.id === CurrentAccount.uri) {
                cardValid = false;
                previewColor = JamiTheme.buttonTintedRed;
                previewMsg = JamiStrings.nodeCardSelf;
                return;
            }
            cardValid = true;
            cardId = r.card.id;
            cardName = r.card.n || "";
            previewColor = JamiTheme.presenceGreen;
            previewMsg = JamiStrings.nodeCardCardValid
                    + " · " + (r.card.n ? r.card.n + " · " : "")
                    + r.card.id.substring(0, 10) + "…";
        } else {
            cardValid = false;
            previewColor = JamiTheme.buttonTintedRed;
            previewMsg = JamiStrings.nodeCardCardInvalid;
        }
    }

    popupContent: ColumnLayout {
        width: JamiTheme.preferredDialogWidth
        spacing: JamiTheme.preferredMarginSize / 2

        ScrollView {
            id: cardInputScroll

            Layout.preferredWidth: JamiTheme.preferredDialogWidth - 2 * root.popupMargins
            Layout.preferredHeight: 100

            TextArea {
                id: cardInput

                width: cardInputScroll.width
                // 根属性注入预填文本（剪贴板自动导入 / 手动导入均可预填）
                text: root.presetText

                placeholderText: JamiStrings.nodeCardPasteHint
                wrapMode: TextArea.WrapAnywhere
                color: JamiTheme.textColor
                selectByMouse: true
                background: Rectangle {
                    border.color: JamiTheme.tintedBlue
                    color: JamiTheme.secondaryBackgroundColor
                }
                // 把本组件内的文本作为参数传给根函数，避免跨作用域按 id 读取
                onTextChanged: root.refreshPreview(cardInput.text)
            }
        }

        Label {
            id: previewText

            Layout.preferredWidth: JamiTheme.preferredDialogWidth - 2 * root.popupMargins
            // 由根属性绑定刷新（原先根作用域直接写 previewText.text 会 ReferenceError）
            text: root.previewMsg
            color: root.previewColor
            wrapMode: Text.WordWrap
            font.pointSize: JamiTheme.menuFontSize - 2
        }
    }

    button1.text: JamiStrings.nodeCardAdd
    button1.enabled: root.cardValid
    button1.onClicked: {
        if (ConversationsAdapter.dialogId(root.cardId) !== "") {
            // 已是好友，直接打开会话
            ConversationsAdapter.openDialogConversationWith(root.cardId);
            close();
            return;
        }
        // F-904 通讯修复：走确定性选边的原生链路（addContact + 按需 sendTrustRequest），
        // 使两端进入同一 1:1 会话。原先仅 makeConversationPermanent 在本端建会话，
        // 对端 id 较大时不会 clone，导致“加上好友但发消息对方收不到”。
        ContactAdapter.addContactFromCard(root.cardId);
        close();
    }

    button2.text: JamiStrings.optionCancel
    button2.onClicked: close()
}
