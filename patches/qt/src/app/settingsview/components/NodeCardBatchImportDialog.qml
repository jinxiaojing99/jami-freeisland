/*
 * F-904 带外加好友 —— 批量导入节点名片对话框（Qt/QML，AC-4）
 *
 * 选择 contacts.jamicards 文件 → parseFile 拆出多张 → 逐张校验 →
 * 勾选列表（已是好友的标记 Added 且不可勾）→ 批量发送联系人请求。
 * 加好友链路与单张导入一致（setFilter + makeConversationPermanent）。
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

    // 解析结果元素: { id, name, valid, added, checked }
    property var cards: []
    property int validCount: 0
    property int checkedCount: 0
    // popupContent 是 Loader.sourceComponent（独立 QML 作用域），根作用域不能按 id
    // 访问 statusLabel / listContainer，状态改由这两个根属性经绑定传入。
    property string statusMsg: JamiStrings.nodeCardBatchNote
    property bool listVisible: false

    titleText: JamiStrings.nodeCardBatchTitle

    function openWithParameters() {
        cards = [];
        validCount = 0;
        checkedCount = 0;
        statusMsg = JamiStrings.nodeCardBatchNote;
        listVisible = false;
        open();
    }

    function loadFile(fileUrl) {
        var path = UtilsAdapter.getAbsPath(fileUrl.toString());
        if (path.length === 0) {
            statusMsg = JamiStrings.nodeCardFileParseFailed;
            return;
        }
        var content = UtilsAdapter.qStringFromFile(path);
        var texts = NodeCardCodec.parseFile(content);
        var result = [];
        var nValid = 0;
        var nSelf = 0;
        for (var i = 0; i < texts.length; i++) {
            var r = NodeCardCodec.decode(texts[i]);
            if (!r.ok)
                continue;
            // F-904 self 守卫：跳过自己的名片，不能添加自己为联系人
            if (r.card.id === CurrentAccount.uri) {
                nSelf++;
                continue;
            }
            nValid++;
            var added = ConversationsAdapter.dialogId(r.card.id) !== "";
            result.push({
                            "id": r.card.id,
                            "name": r.card.n || r.card.id.substring(0, 10) + "…",
                            "valid": true,
                            "added": added,
                            "checked": !added
                        });
        }
        cards = result;
        validCount = nValid;
        if (nValid === 0) {
            statusMsg = JamiStrings.nodeCardFileInvalid
                    + (nSelf > 0 ? " · " + JamiStrings.nodeCardSelfSkipped.arg(nSelf) : "");
            listVisible = false;
            return;
        }
        statusMsg = JamiStrings.nodeCardValidOf.arg(nValid).arg(texts.length)
                + (nSelf > 0 ? " · " + JamiStrings.nodeCardSelfSkipped.arg(nSelf) : "");
        listVisible = true;
    }

    function addSelected() {
        var n = 0;
        for (var i = 0; i < cards.length; i++) {
            var c = cards[i];
            if (!c.checked || c.added)
                continue;
            // 与单张导入一致的确定性选边原生链路（addContact + 按需 sendTrustRequest）
            ContactAdapter.addContactFromCard(c.id);
            n++;
        }
        close();
        viewCoordinator.presentDialog(appWindow, "commoncomponents/SimpleMessageDialog.qml", {
                                          "title": JamiStrings.success,
                                          "infoText": JamiStrings.nodeCardBatchAdded.arg(n),
                                          "buttonTitles": [JamiStrings.optionOk],
                                          "buttonStyles": [SimpleMessageDialog.ButtonStyle.TintedBlue]
                                      });
    }

    popupContent: ColumnLayout {
        width: JamiTheme.preferredDialogWidth
        spacing: JamiTheme.preferredMarginSize / 2

        MaterialButton {
            id: selectFileButton

            Layout.alignment: Qt.AlignHCenter

            primary: true
            color: JamiTheme.buttonTintedBlue
            preferredWidth: JamiTheme.preferredFieldWidth
            text: JamiStrings.nodeCardSelectFile

            onClicked: {
                var dlg = viewCoordinator.presentDialog(appWindow, "commoncomponents/JamiFileDialog.qml", {
                                                            "fileMode": JamiFileDialog.OpenFile,
                                                            "folder": StandardPaths.writableLocation(StandardPaths.DownloadLocation),
                                                            "nameFilters": [JamiStrings.nodeCardFilter, JamiStrings.allFiles]
                                                        });
                dlg.fileAccepted.connect(function (file) {
                    root.loadFile(file);
                });
            }
        }

        Label {
            id: statusLabel

            Layout.preferredWidth: JamiTheme.preferredDialogWidth - 2 * root.popupMargins
            text: root.statusMsg
            color: JamiTheme.textColor
            wrapMode: Text.WordWrap
            font.pointSize: JamiTheme.menuFontSize - 2
        }

        ScrollView {
            id: listContainer

            visible: root.listVisible
            Layout.preferredWidth: JamiTheme.preferredDialogWidth - 2 * root.popupMargins
            Layout.preferredHeight: Math.min(240, cardRepeater.count * 40 + 8)

            ColumnLayout {
                width: listContainer.availableWidth
                spacing: 4

                Repeater {
                    id: cardRepeater

                    model: root.cards

                    delegate: RowLayout {
                        required property int index
                        required property var modelData

                        width: listContainer.availableWidth
                        spacing: 8

                        CheckBox {
                            checked: modelData.checked
                            enabled: modelData.valid && !modelData.added
                            onToggled: {
                                var arr = root.cards;
                                arr[index].checked = checked;
                                root.cards = arr;
                            }
                        }

                        Label {
                            Layout.fillWidth: true
                            text: modelData.name
                            color: JamiTheme.textColor
                            elide: Text.ElideRight
                            font.pointSize: JamiTheme.menuFontSize
                        }

                        Label {
                            text: modelData.id.substring(0, 8) + "…"
                            color: JamiTheme.textColor
                            opacity: 0.6
                            font.pointSize: JamiTheme.menuFontSize - 2
                        }

                        Label {
                            visible: modelData.added
                            text: JamiStrings.nodeCardAlreadyAdded
                            color: JamiTheme.presenceGreen
                            font.pointSize: JamiTheme.menuFontSize - 2
                        }
                    }
                }
            }
        }
    }

    button1.text: JamiStrings.nodeCardAddSelected
    button1.enabled: {
        var n = 0;
        for (var i = 0; i < cards.length; i++)
            if (cards[i].checked && !cards[i].added)
                n++;
        return n > 0;
    }
    button1.onClicked: root.addSelected()

    button2.text: JamiStrings.optionCancel
    button2.onClicked: close()
}
