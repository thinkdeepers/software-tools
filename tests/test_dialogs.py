"""进度/完成对话框相关测试。"""

import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, "src"))

from jianya import dialogs  # noqa: E402


def test_show_result_inplace_uses_self_tk():
    """回归：结果页必须用 self.tk，不能引用未定义的模块级 tk（否则白框）。"""
    import inspect

    src = inspect.getsource(dialogs.ProgressDialog._show_result_inplace)
    assert "self.tk" in src
    # 禁止在销毁子控件后再用裸 tk.Label / tk.Button
    assert "tk = self.tk" in src or "self.tk.Label" in src


@pytest.mark.skipif(
    sys.platform == "darwin",
    reason="无头环境以 Linux/Wine 为主",
)
def test_progress_dialog_result_page_not_blank():
    """完成页切换后 body 内应有可见控件（徽章/标题/按钮）。"""
    tkinter = pytest.importorskip("tkinter")
    try:
        root = tkinter.Tk()
        root.withdraw()
    except Exception as exc:  # pragma: no cover - 无显示环境
        pytest.skip(f"无法创建 Tk：{exc}")

    try:
        dlg = dialogs.ProgressDialog(title="简压 — 正在处理", status="正在解压…", parent=root)
        dlg._result_title = "简压"
        dlg._result_message = "已解压到：\n/tmp/demo"
        dlg._result_error = False
        dlg._show_result_inplace()

        texts = []
        for child in dlg._body.winfo_children():
            try:
                texts.append(str(child.cget("text")))
            except Exception:
                pass
        assert "已解压" in texts
        assert "简压" in texts
        assert "确定" in texts
        assert any("已解压到" in t for t in texts)
        dlg.close()
    finally:
        try:
            root.destroy()
        except Exception:
            pass
