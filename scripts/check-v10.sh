#!/usr/bin/env bash
# 边界守卫 — 跨 Gradle 项目依赖的静态检查(G1 拆分版)。
#
# 与 ArchUnit 的关系:
#   - 本脚本是快速第一道防线(grep 源码 + 读 build.gradle), 在编译之前就能发现问题;
#   - ModuleBoundaryArchitectureTest(chat 项目)是结构性第二道, 用 ArchUnit 对字节码
#     做依赖方向断言, 随 `gradle test` 执行。
#   两者都必须通过。
#
# 它守的是什么:
#   1. 各项目"拥有"的包两两不相交(Java 不允许 split package, 跨项目同名包会静默合并);
#   2. 平台之间互不引用源码 —— chat 与 application 经 contract 的端口相见,
#      contract 谁都不能依赖;
#   3. Gradle 依赖图与声明一致 —— 同样的规则在 build.gradle 上再查一遍。
#
# G1 之前这里守的是五个 Maven 模块; 拆分后 digital-human-platform 整体迁往
# simulation-agent-platform 仓库, 仓库边界接管了"chat ↔ DH 互不相见"的保证 ——
# 本脚本继续守**仓内**的四项目边界, 对外契约(contract)仍是核心。
#
# 包归属不写死, 全部从各项目源码树推导 —— 加了新包不需要改这个脚本, 也就不会过期。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE_PKG="com/luxera/companion"

fail=0
note() { echo "  $*" >&2; }
ok()   { note "✓ $*"; }
bad()  { note "✗ $*"; fail=1; }

# 项目 → 该项目 src/main/java 下 com.luxera.companion.<X> 的 <X> 集合
owned_packages() {
  local module="$1"
  local dir="$ROOT/$module/src/main/java/$BASE_PKG"
  [[ -d "$dir" ]] || return 0
  find "$dir" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort
}

PROJECTS=(contract common application chat)

echo "== 1) 包归属互斥(split package 检查) =="
declare -A OWNER
for m in "${PROJECTS[@]}"; do
  while read -r pkg; do
    [[ -n "$pkg" ]] || continue
    if [[ -n "${OWNER[$pkg]:-}" ]]; then
      bad "包 com.luxera.companion.$pkg 同时存在于 ${OWNER[$pkg]} 与 $m"
    else
      OWNER[$pkg]="$m"
    fi
  done < <(owned_packages "$m")
done
[[ "$fail" -eq 0 ]] && ok "包归属互斥(${#OWNER[@]} 个顶层包分属 ${#PROJECTS[@]} 个项目)"

# 每个项目拥有的包(供第 2 步用)
pkgs_of() {
  local want="$1" out=()
  for pkg in "${!OWNER[@]}"; do
    [[ "${OWNER[$pkg]}" == "$want" ]] && out+=("$pkg")
  done
  printf '%s\n' "${out[@]:-}"
}

echo "== 2) 跨项目源码引用 =="
check_no_import() {
  local src_project="$1" forbidden_project="$2"
  local dir="$ROOT/$src_project/src"
  [[ -d "$dir" ]] || return 0
  local packages pattern hits
  packages="$(pkgs_of "$forbidden_project" | paste -sd'|' -)"
  [[ -n "$packages" ]] || return 0
  # 匹配 import / 全限定引用 com.luxera.companion.<被禁包>. ; 不匹配该项目自己拥有的包
  pattern="com\\.luxera\\.companion\\.(${packages})\\."
  hits="$(grep -rnE "$pattern" "$dir" --include=*.java 2>/dev/null | grep -v '// CHECK-V10-ALLOW' || true)"
  # 守卫测试文件整体豁免: 它们的正文里出现别项目的包名是断言素材(比如 ArchUnit 规则
  # 要写被禁包的全名), 不是依赖。豁免以文件头一行显式标记为凭, 没有标记的照常报。
  local guard_files
  guard_files="$(grep -rl 'CHECK-V10-ALLOW-GUARD' "$dir" --include=*.java 2>/dev/null || true)"
  if [[ -n "$guard_files" ]]; then
    local exclude=()
    while IFS= read -r gf; do exclude+=("--exclude=$(basename "$gf")"); done <<< "$guard_files"
    hits="$(echo "$hits" | grep -vE "$(printf '%s\n' "${guard_files}" | xargs -I{} basename {} | paste -sd'|')" || true)"
  fi
  if [[ -n "$hits" ]]; then
    bad "$src_project 引用了 $forbidden_project 拥有的包:"
    echo "$hits" | head -5 | sed 's|^|      |' >&2
  else
    ok "$src_project 不引用 $forbidden_project 的包"
  fi
}
# chat 与 application 经 contract 的端口相见, 不碰对方实现
check_no_import chat application
check_no_import application chat
# contract 是纯契约项目: 谁都不能依赖, 它也不依赖任何人
check_no_import contract chat
check_no_import contract application
# common 是仓内底座: 提供者不许认识使用者
check_no_import common chat
check_no_import common application

echo "== 3) Gradle 依赖图 =="
gradle_depends_on() {
  local project="$1" other="$2"
  local build="$ROOT/$project/build.gradle"
  [[ -f "$build" ]] || return 1
  # project(':x') 形式的项目依赖
  grep -q "project(':$other')" "$build"
}

check_gradle_absent() {
  local project="$1" other="$2"
  if gradle_depends_on "$project" "$other"; then
    bad "$project/build.gradle 依赖了项目 $other"
  else
    ok "$project/build.gradle 不依赖 $other"
  fi
}
# 依赖图上唯一合法的方向: chat → {contract, common, application}, application/common → contract
check_gradle_absent contract chat
check_gradle_absent contract common
check_gradle_absent contract application
check_gradle_absent application chat
check_gradle_absent application common
check_gradle_absent common application

# contract 项目里不许出现任何仓内项目依赖(用户铁律: 契约不依赖仓库内任何项目)
if grep -qE "project\(':" "$ROOT/contract/build.gradle"; then
  bad "contract/build.gradle 出现了仓内项目依赖 —— 契约必须零依赖"
else
  ok "contract/build.gradle 零仓内依赖"
fi

if [[ "$fail" -eq 0 ]]; then
  echo "check-v10 OK"
else
  echo "check-v10 FAILED"
  exit 1
fi
