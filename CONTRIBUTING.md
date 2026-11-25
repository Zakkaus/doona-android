# Contributing / 参与贡献

## Build and checks / 构建与检查

Follow [AGENTS.md](AGENTS.md) for toolchains and gates. [Android CI](.github/workflows/android.yml) lists the checks for the Android app and Rust crates. Run the checks that apply to your change.
工具链和检查要求见 [AGENTS.md](AGENTS.md)。[Android CI](.github/workflows/android.yml) 列出了 Android 应用和 Rust crate 的检查。请执行与你的改动相关的检查。

## Issues and pull requests / Issue 与 PR

AI may help you write this; unverified content must not be submitted.
可以用 AI 辅助撰写，但提交前须自行核实全部内容。

Use the [issue forms](https://github.com/Zakkaus/doona-android/issues/new/choose) and name the human responsible. Branch from the current `main` and keep each PR to one topic. Write English commit subjects as `type(scope): short subject` with an imperative verb. Use a short English body to explain the change and its reason.
请使用 [issue 表单](https://github.com/Zakkaus/doona-android/issues/new/choose)并填写负责人。基于当前 `main` 开发，每个 PR 只处理一个主题。提交标题采用 `type(scope): short subject` 格式，主题用英文祈使句。正文用简短的英文说明改动及原因。

Start from the [PR template](.github/PULL_REQUEST_TEMPLATE.md), including when submitting through `gh` or the API. In English or Simplified Chinese, write one short paragraph under each heading in this order: `## Problem / 问题`, `## How I fixed it / 解决方法`, `## Verified / 验证`. Record the commands, environment, and observed results in Verified. Link long output and say what you could not verify. Keep the checklist and check only true claims.
通过网页、`gh` 或 API 提交时，均以 [PR 模板](.github/PULL_REQUEST_TEMPLATE.md)为基础。按原顺序在“问题”“解决方法”“验证”下各写一小段，使用英文或简体中文。在“验证”中列出执行的命令、环境和实际结果。较长的输出请附链接，并注明未验证的部分。保留检查清单，只勾选属实的选项。

`template-check` closes outside contributors’ issues and PRs if required sections or attestations are missing or a disqualifying box is checked. Edit the body to correct the violations; the workflow only reopens items it closed. Maintainers can also reopen an item manually. The check cannot verify what you actually reviewed or tested.
外部贡献者提交的 issue 或 PR 如果缺少必填内容、漏勾必选项或勾选了不符合要求的选项，`template-check` 会将其关闭。修正正文后，工作流只会重新打开由它关闭的条目。维护者也可以手动重新打开条目。自动检查无法核实你实际审阅或测试了什么。
