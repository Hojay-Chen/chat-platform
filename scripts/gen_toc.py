#!/usr/bin/env python3
"""为 README.md 生成/替换 GitHub 兼容的可点击目录(TOC)。幂等: 替换已有 TOC 块, 不重复插入。"""
import re

PATH = "/home/ubuntu/claude-workspace/chat-platform/README.md"

def slug(text: str) -> str:
    t = re.sub(r"<[^>]*>", "", text)
    t = t.lower()
    t = re.sub(r"[^\w一-鿿 \-_]", "", t, flags=re.UNICODE)
    t = t.replace(" ", "-")
    return t

with open(PATH, encoding="utf-8") as f:
    content = f.read()

seen = {}
toc_lines = ["## 📖 目录\n"]
for line in content.split("\n"):
    m = re.match(r"^(#{2,4})\s+(.*)$", line)
    if not m:
        continue
    title = m.group(2).strip()
    if title == "📖 目录":
        continue
    level = len(m.group(1))
    anchor = slug(title)
    if anchor in seen:
        seen[anchor] += 1
        anchor = f"{anchor}-{seen[anchor]}"
    else:
        seen[anchor] = 1
    indent = "  " * (level - 2)
    toc_lines.append(f"{indent}- [{title}](#{anchor})")

toc = "\n".join(toc_lines)

# 若已存在 TOC 块("## 📖 目录" 到其后的 "---"), 整体替换; 否则插到第一个 H2 之前
toc_block = re.compile(r"^## 📖 目录\n.*?(?=^---\n)", re.MULTILINE | re.DOTALL)
if toc_block.search(content):
    content = toc_block.sub(toc.rstrip("\n") + "\n", content, count=1)
else:
    idx = content.find("\n## ")
    if idx == -1:
        idx = content.find("\n# ")
    content = content[:idx] + "\n" + toc + "\n" + content[idx:]

with open(PATH, "w", encoding="utf-8") as f:
    f.write(content)

print(f"✅ TOC 已生成/替换: {len(toc_lines)-1} 个条目")
