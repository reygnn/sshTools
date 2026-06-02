# =============================================================================
# German-comment detector for git-diff-style input.
#
# Reads a unified diff on stdin and flags every ADDED (`+`) line that is a
# comment containing German prose. Ported from Kolibri_Launcher's Rule 13
# linter. sshTools keeps ALL source comments English (see CLAUDE.md), so this
# guards against German creeping back in via new/modified lines.
#
# Two-tier detection:
#   - hard signal: any umlaut/eszett (ä ö ü ß Ä Ö Ü) → German.
#   - soft signal: >= 2 distinct German function words (umlaut-free German).
# =============================================================================

BEGIN {
    # German function words — high-precision (no English overlap).
    n_words = split("und nicht oder aber auch noch schon weil damit dass " \
                   "sich sind wurde wurden werden eine einen keine kein " \
                   "dieser diese dieses gleich bereits werfen wenn kann " \
                   "muss soll zwischen ohne sondern beim vom zur zum " \
                   "dem des deren denen dessen jener jene jenes welche " \
                   "welcher welches", german_words, " ")

    current_file = ""
    current_line = 0
}

# +++ b/<path>: file header. Capture path; reset line counter.
/^\+\+\+ b\// {
    current_file = substr($0, 7)
    current_line = 0
    next
}

# Skip the --- a/<path> header.
/^--- / { next }

# Hunk header: @@ -<old>,<n> +<new>,<m> @@
/^@@/ {
    if (match($0, /\+[0-9]+/)) {
        current_line = substr($0, RSTART + 1, RLENGTH - 1) - 1
    }
    next
}

# Removed line: ignored.
/^-/ { next }

# Context line: increments line counter, but not flagged.
/^ / {
    current_line++
    next
}

# Added line: this is what we check.
/^\+/ {
    current_line++
    line_content = substr($0, 2)
    check_added_line(line_content)
    next
}

function check_added_line(line,    comment_text) {
    comment_text = extract_comment(line)
    if (comment_text == "") return

    if (is_german(comment_text)) {
        printf "%s:%d:%s\n", current_file, current_line, comment_text
    }
}

function extract_comment(line,    stripped, idx) {
    stripped = line
    sub(/^[ \t]+/, "", stripped)

    # KDoc opener: /** …  or /* …
    if (match(stripped, /^\/\*\*?[ \t]*/)) {
        return strip_trailing_close(substr(stripped, RLENGTH + 1))
    }

    # KDoc continuation: * …  (but not */ alone)
    if (stripped ~ /^\*[ \t]/) {
        return strip_trailing_close(substr(stripped, 3))
    }

    # Bare close marker — no prose.
    if (stripped ~ /^\*\/[ \t]*$/) return ""

    # Line comment: // …
    if (match(stripped, /^\/\/+[ \t]*/)) {
        return substr(stripped, RLENGTH + 1)
    }

    # Inline `//` after code. Skip if part of `://` (URL).
    idx = index(line, "//")
    if (idx > 0) {
        if (idx > 1 && substr(line, idx - 1, 1) == ":") return ""
        stripped = substr(line, idx + 2)
        sub(/^[ \t]+/, "", stripped)
        return stripped
    }

    return ""
}

function strip_trailing_close(text) {
    sub(/[ \t]*\*\/[ \t]*$/, "", text)
    return text
}

function is_german(text,    count, i, w, re) {
    # Hard signal: umlaut or eszett.
    if (text ~ /(ä|ö|ü|ß|Ä|Ö|Ü)/) return 1

    # Soft signal: count distinct German function-word matches (need >= 2).
    count = 0
    for (i = 1; i <= n_words; i++) {
        w = german_words[i]
        # Word-boundary match: surrounded by start/end or non-letter.
        re = "(^|[^a-zA-Z])" w "([^a-zA-Z]|$)"
        if (text ~ re) {
            count++
            if (count >= 2) return 1
        }
    }

    return 0
}
