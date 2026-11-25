#!/usr/bin/env bash
# Usage: template-check.sh NUMBER; offline: template-check.sh --dry-run issue|pr < body.md
set -euo pipefail

check_body() {
    jq -Rs --arg kind "$1" '
        def trim: gsub("^\\s+|\\s+$"; "");
        def heading($level; $name):
            "(?:^|\\n)" + $level + " " + $name + "(?: /[^\\n]*)?[ \\t]*(?=\\n|\\z)";
        def section($level; $name):
            [capture(heading($level; $name) + "(?<text>[\\s\\S]*?)(?=\\n(?:#{1,6} |---[ \\t]*(?:\\n|\\z))|\\z)"; "i") | .text][0]
            // "" | trim;
        def filled($level; $name):
            section($level; $name) |
            (if $level == "##" then gsub("(?:^|\\n) {0,3}[-*] \\[[ xX]\\] [^\\n]*"; "") | trim else . end) |
            . != "" and ascii_downcase != "_no response_";
        def boxes($text): [split("\n")[] |
            capture("^ {0,3}[-*] \\[(?<mark>[ x])\\] " + $text + "(?: /[^\\n]*)?[ \\t]*$"; "i") | .mark | ascii_downcase];
        def checked($text): boxes($text) | any(. == "x");
        def kept($text): boxes($text) | length > 0 and all(. == "x");

        gsub("\r\n"; "\n") |
        gsub("<!--[\\s\\S]*?-->"; "") |
        gsub("(?:^|\\n) {0,3}(?<fence>`{3,}|~{3,})[^\\n]*\\n[\\s\\S]*?\\n {0,3}\\k<fence>[ \\t]*(?=\\n|\\z)"; "") | . as $body |
        ($kind == "issue" and (test(heading("###"; "What happened"); "i") or
            (test(heading("###"; "(?:Problem|Proposal)"); "i") | not))) as $bug |
        (if $kind == "issue" then section("###"; "Confirm") else . end) as $checks |
        (section("###"; "Human responsible") | ascii_downcase) as $human |
        [
            {test: ($checks | checked("I checked the boxes above without reading them")),
                en: "The unread-checkbox trap is checked.", zh: "勾选了“我没有阅读上面的选项就直接勾选了”。"},
            {test: ($checks | checked("I am an AI agent, and this content was submitted without human review")),
                en: "The AI-agent box says there was no human review.", zh: "勾选了“我是 AI 代理”，表示内容没有经过人工审核。"},
            {test: ($kind == "issue" and ($human | . == "your-name" or . == "@your-name" or
                (test("^@?[a-z0-9]+(?:-[a-z0-9]+)*$") | not))),
                en: "Name the human responsible with a GitHub username.", zh: "请填写负责人的 GitHub 用户名。"},
            {test: ($bug and (["App version", "Android version", "Device", "What happened", "Steps to reproduce", "Diagnostic ZIP"] |
                any(. as $name | $body | filled("###"; $name) | not))),
                en: "Fill in every required bug report section, including the diagnostic ZIP or why it is unavailable.",
                zh: "请填写问题报告的所有必填项，包括诊断 ZIP 或无法上传的原因。"},
            {test: ($kind == "issue" and ($bug | not) and (filled("###"; "Problem") | not)),
                en: "Describe the problem in the feature request.", zh: "请在功能请求中说明问题。"},
            {test: ($kind == "issue" and ($checks | kept("I searched existing issues") | not)),
                en: "Keep and check the existing-issue search box.", zh: "请保留并勾选“我已搜索过现有 issue”。"},
            {test: ($kind == "issue" and ($checks |
                kept(if $bug then "This describes what I observed" else "This describes what I need" end) | not)),
                en: "Keep and check the observation or need box from your issue template.",
                zh: "请保留并勾选模板中确认实际情况或需求的选项。"},
            {test: ($kind == "pr" and (["Problem", "How I fixed it", "Verified"] |
                any(. as $name | $body | filled("##"; $name) | not))),
                en: "Keep and fill in Problem, How I fixed it and Verified.", zh: "请保留并填写“问题”“解决方法”和“验证”。"},
            {test: ($kind == "pr" and (["I understand this change and answer for every line",
                "This branch is based on the current main",
                "I ran the repository\u0027s gates and recorded the results in Verified", "Each commit is one coherent change"] |
                any(. as $box | $checks | kept($box) | not))),
                en: "Keep and check all four unconditional PR boxes.", zh: "请保留并勾选 PR 的四项必选选项。"}
        ] | map(select(.test) | {en, zh})'
}

if [[ ${1:-} == --dry-run ]]; then
    [[ ${2:-} == issue || ${2:-} == pr ]] || exit 2
    failures=$(check_body "$2")
    printf '%s\n' "$failures"
    jq -e 'length == 0' <<< "$failures" >/dev/null
    exit
fi

number=${1:?issue or pull request number}
repo="repos/${GITHUB_REPOSITORY:?}"
item=$(gh api "$repo/issues/$number")
if jq -e '.user.type == "Bot" or (.author_association | IN("OWNER", "MEMBER", "COLLABORATOR"))' <<< "$item" >/dev/null; then
    exit
fi
if [[ ${GITHUB_EVENT_ACTION:-} == reopened && ${SENDER:-} != "$(jq -r .user.login <<< "$item")" ]]; then
    exit
fi

kind=$(jq -r 'if .pull_request then "pr" else "issue" end' <<< "$item")
failures=$(jq -r '.body // ""' <<< "$item" | check_body "$kind")
label=template-violation
marker='<!-- template-violation -->'
failed=$(jq length <<< "$failures")
labelled=$(jq --arg label "$label" 'any(.labels[]; .name == $label)' <<< "$item")
state=$(jq -r .state <<< "$item")
closed_by=$(jq -r '.closed_by.login' <<< "$item")
bot='github-actions[bot]'
if [[ $state == closed && ( $labelled != true || $closed_by != "$bot" ) ]]; then
    exit
fi
if [[ $failed == 0 && ( ${GITHUB_EVENT_ACTION:-} != edited || $state != closed ) ]]; then
    exit
fi

if [[ $failed != 0 ]]; then
    if [[ $state == open ]]; then
        if [[ $kind == issue ]]; then
            gh issue close "$number" --repo "$GITHUB_REPOSITORY" --reason 'not planned'
        else
            gh pr close "$number" --repo "$GITHUB_REPOSITORY"
        fi
        # gh close returns success even if someone else closed the item first.
        closed=$(gh api "$repo/issues/$number")
        if ! jq -e --arg bot "$bot" '.state == "closed" and .closed_by.login == $bot' <<< "$closed" >/dev/null; then
            exit
        fi
        if ! gh api "$repo/labels/$label" --silent; then
            gh label create "$label" --repo "$GITHUB_REPOSITORY" --color D93F0B \
                --description 'Issue or PR template needs correction' || gh api "$repo/labels/$label" --silent
        fi
        gh api "$repo/issues/$number/labels" -f "labels[]=$label" --silent
    fi
    message=$(jq -r --arg marker "$marker" '
        $marker + "\n内容不符合模板要求，已关闭。请修改以下各项，正文改好后会自动重新打开：\n" +
        (map("- " + .zh) | join("\n")) +
        "\n\nFix these template violations; this item will reopen automatically once its body is fixed:\n" +
        (map("- " + .en) | join("\n"))' <<< "$failures")
else
    gh "$kind" reopen "$number" --repo "$GITHUB_REPOSITORY"
    message="$marker
已按模板改好，现已重新打开。

Template violations fixed; reopened."
fi

comment=$(gh api --paginate --slurp "$repo/issues/$number/comments" | jq --arg marker "$marker" --arg bot "$bot" \
    '[.[][] | select(.user.type == "Bot" and .user.login == $bot and (.body | startswith($marker)))][0] // null')
if [[ $comment == null ]]; then
    gh api "$repo/issues/$number/comments" -f body="$message" --silent
elif [[ $(jq -r .body <<< "$comment") != "$message" ]]; then
    gh api "$repo/issues/comments/$(jq -r .id <<< "$comment")" -X PATCH -f body="$message" --silent
fi

if [[ $failed == 0 ]]; then
    gh api "$repo/issues/$number/labels/$label" -X DELETE --silent
fi
