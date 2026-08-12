"""进度/完成对话框相关测试。"""

import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, "src"))

from jianya import dialogs  # noqa: E402


def test_enter_done_state_keeps_progress_ui():
    """完成后应保留进度页，不销毁重建；按钮变为「确定」。"""
    import inspect

    src = inspect.getsource(dialogs.ProgressDialog._enter_done_state)
    assert "set_text" in src
    assert "确定" in src
    # 禁止再销毁 body 子控件切页
    assert "winfo_children" not in src
    assert "destroy()" not in src


@pytest.mark.skipif(
    sys.platform == "darwin",
    reason="无头环境以 Linux/Wine 为主",
)
def test_progress_dialog_done_stays_on_progress_page():
    """完成态：进度 100%，界面控件仍在，按钮文案为确定。"""
    tkinter = pytest.importorskip("tkinter")
    try:
        root = tkinter.Tk()
        root.withdraw()
    except Exception as exc:  # pragma: no cover - 无显示环境
        pytest.skip(f"无法创建 Tk：{exc}")

    try:
        dlg = dialogs.ProgressDialog(
            title="简压 — 正在处理", status="正在解压…", parent=root
        )
        # 记录完成前关键控件，确保不会被销毁
        status_before = dlg.status_label
        bar_before = dlg.bar_canvas
        btn_before = dlg._cancel_btn

        dlg._result_title = "简压"
        dlg._result_message = "已解压到：\n/tmp/demo"
        dlg._result_error = False
        dlg._enter_done_state()

        assert dlg._finished is True
        assert dlg._pct == 100
        assert dlg.status_label is status_before
        assert dlg.bar_canvas is bar_before
        assert dlg._cancel_btn is btn_before
        assert dlg._cancel_btn._jy_text == "确定"
        assert "处理完成" in str(dlg.status_label.cget("text"))
        # 不应出现旧结果页徽章
        texts = []

        def _collect(widget) -> None:
            try:
                texts.append(str(widget.cget("text")))
            except Exception:
                pass
            try:
                for item in widget.find_all():
                    if widget.type(item) == "text":
                        texts.append(str(widget.itemcget(item, "text")))
            except Exception:
                pass
            for child in widget.winfo_children():
                _collect(child)

        _collect(dlg._body)
        assert "确定" in texts
        assert "已解压" not in texts  # 不再切到「已解压」结果页
        dlg.close()
    finally:
        try:
            root.destroy()
        except Exception:
            pass


def test_show_result_inplace_delegates_to_enter_done():
    """旧 API 应转到 _enter_done_state，避免白框/跳变。"""
    import inspect

    src = inspect.getsource(dialogs.ProgressDialog._show_result_inplace)
    assert "_enter_done_state" in src
