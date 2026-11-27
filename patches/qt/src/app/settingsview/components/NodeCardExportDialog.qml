/*
 * F-904 带外加好友 —— 导出节点名片对话框（Qt/QML）
 *
 * 展示本账户的名片文本串（JAMICARD1:...），支持复制 / 系统分享 / 保存为
 * contacts.jamicards 批量文件（AC-2，经 UtilsAdapter.stringToFile 落盘）。
 */
import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Qt.labs.platform

import net.jami.Adapters 1.1
import net.jami.Constants 1.1
import "../../commoncomponents"
import "../../net/jami/Constants/NodeCardCodec.js" as NodeCardCodec

BaseModalDialog {
    id: root

    required property string accountId
    required property string displayName
    required property string appVersion

    property string cardText: ""
    property string cardFileJson: ""

    titleText: JamiStrings.nodeCardExportTitle

    function openWithParameters() {
        var card = {
            "id": CurrentAccount.uri,
            "n": displayName !== "" ? displayName : null,
            "ver": appVersion !== "" ? appVersion : null,
            "ts": Math.floor(Date.now())
        };
        cardText = NodeCardCodec.encode(card);
        cardFileJson = NodeCardCodec.toFile([card], Math.floor(Date.now()));
        // 注意：cardTextArea 位于 popupContent（Loader.sourceComponent）内，
        // 属于独立 QML 组件作用域，根作用域无法按 id 直接赋值（运行时 ReferenceError）。
        // 改为在 TextArea 内用 text: root.cardText 绑定，见下方。
        open();
    }

    popupContent: ColumnLayout {
        width: JamiTheme.preferredDialogWidth
        spacing: JamiTheme.preferredMarginSize / 2

        Label {
            id: cardNote

            Layout.preferredWidth: JamiTheme.preferredDialogWidth - 2 * root.popupMargins
            text: JamiStrings.nodeCardNote
            color: JamiTheme.textColor
            wrapMode: Text.WordWrap
            font.pointSize: JamiTheme.menuFontSize - 2
        }

        ScrollView {
            id: cardScroll

            Layout.preferredWidth: JamiTheme.preferredDialogWidth - 2 * root.popupMargins
            Layout.preferredHeight: 120

            TextArea {
                id: cardTextArea

                width: cardScroll.width
                // 绑定根属性：打开前 openWithParameters() 先填 root.cardText，
                // 这里自动同步文本（规避跨 Loader 作用域按 id 赋值失败）。
                text: root.cardText

                readOnly: true
                wrapMode: TextArea.WrapAnywhere
                color: JamiTheme.textColor
                font.pointSize: JamiTheme.menuFontSize - 2
                selectByMouse: true
                background: Rectangle {
                    border.color: JamiTheme.tintedBlue
                    color: JamiTheme.secondaryBackgroundColor
                }
            }
        }

        MaterialButton {
            id: saveFileButton

            Layout.alignment: Qt.AlignHCenter

            primary: true
            color: JamiTheme.buttonTintedBlue
            preferredWidth: JamiTheme.preferredFieldWidth
            text: JamiStrings.nodeCardSaveFile

            onClicked: {
                var dlg = viewCoordinator.presentDialog(appWindow, "commoncomponents/JamiFileDialog.qml", {
                                                            "fileMode": JamiFileDialog.SaveFile,
                                                            "folder": StandardPaths.writableLocation(StandardPaths.DownloadLocation),
                                                            "defaultSuffix": ".jamicards"
                                                        });
                dlg.fileAccepted.connect(function (file) {
                    var path = UtilsAdapter.getAbsPath(file.toString());
                    if (path.length === 0)
                        return;
                    if (path.indexOf(".") === -1)
                        path += ".jamicards";
                    var ok = UtilsAdapter.stringToFile(path, root.cardFileJson);
                    viewCoordinator.presentDialog(appWindow, "commoncomponents/SimpleMessageDialog.qml", {
                                                      "title": ok ? JamiStrings.success : JamiStrings.error,
                                                      "infoText": ok ? JamiStrings.nodeCardFileSaved
                                                                     : JamiStrings.nodeCardFileSaveFailed,
                                                      "buttonTitles": [JamiStrings.optionOk],
                                                      "buttonStyles": [SimpleMessageDialog.ButtonStyle.TintedBlue]
                                                  });
                });
            }
        }
    }

    button1.text: JamiStrings.copy
    button1.onClicked: {
        UtilsAdapter.setClipboardText(root.cardText);
        close();
    }

    // F-904 订正：原「共享」按钮实现与「复制」完全相同（复制名片串到剪贴板后关闭）；
    // Qt 桌面端无系统分享面板，该按钮只会误导用户以为能拉起微信分享。
    // 置空 text 后 BaseModalDialog 自动隐藏中间按钮，仅保留「复制」「取消」。
    button2.text: ""

    button3.text: JamiStrings.optionCancel
    button3.onClicked: close()
}
