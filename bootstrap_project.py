from pathlib import Path
import base64, io, shutil, zipfile

root = Path(__file__).resolve().parent
parts = sorted((root / ".bootstrap-payload").glob("part-*.txt"))
payload = "".join(p.read_text(encoding="utf-8").strip() for p in parts)
with zipfile.ZipFile(io.BytesIO(base64.b64decode(payload))) as archive:
    archive.extractall(root)
shutil.rmtree(root / ".bootstrap-payload")
(root / "bootstrap_project.py").unlink(missing_ok=True)
(root / ".github/workflows/bootstrap.yml").unlink(missing_ok=True)
print("简播项目文件已生成")
