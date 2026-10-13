package io.github.tuthan.paddock.files

import io.github.tuthan.paddock.files.TransferRefusal.BadCount
import io.github.tuthan.paddock.files.TransferRefusal.BadName
import io.github.tuthan.paddock.files.TransferRefusal.BadPath
import io.github.tuthan.paddock.files.TransferRefusal.Changed
import io.github.tuthan.paddock.files.TransferRefusal.Exists
import io.github.tuthan.paddock.files.TransferRefusal.Hidden
import io.github.tuthan.paddock.files.TransferRefusal.Home
import io.github.tuthan.paddock.files.TransferRefusal.MissingTool
import io.github.tuthan.paddock.files.TransferRefusal.NotDirectory
import io.github.tuthan.paddock.files.TransferRefusal.NotFound
import io.github.tuthan.paddock.files.TransferRefusal.NotRegular
import io.github.tuthan.paddock.files.TransferRefusal.NotYours
import io.github.tuthan.paddock.files.TransferRefusal.OutsideFolder
import io.github.tuthan.paddock.files.TransferRefusal.OutsideHome
import io.github.tuthan.paddock.files.TransferRefusal.OverCap
import io.github.tuthan.paddock.files.TransferRefusal.SharedFolder
import io.github.tuthan.paddock.files.TransferRefusal.Symlink
import io.github.tuthan.paddock.files.TransferRefusal.Truncated
import io.github.tuthan.paddock.files.TransferRefusal.Unwritable

/**
 * Why a transfer script refused, one exit status each (a script exits with nothing else on purpose: 1, 2, 126, 127 and 128 and up belong to the
 * shell and to signals, so [forExit] answers null for them and the caller treats the outcome as unknown). Every refusal happens before a file is
 * touched, or leaves no part behind; the [sentence] says which, because a refusal that does not say what was and was not done is not shown.
 */
enum class TransferRefusal(val exit: Int, val sentence: String) {
    /** A program the script needs is not on the machine's PATH (`head`, `wc`, `mktemp`, `link`, `mkdir`, `rmdir`, `rm`, `find`, `stat` or `sort`). */
    MissingTool(10, "The machine lacks a program the transfer needs. Nothing was transferred."),
    /** A folder or path that is not absolute, holds a control character, or was cut off in the header. */
    BadPath(11, "That folder or path is not one Paddock can use. Nothing was transferred."),
    NotDirectory(12, "That folder is not a folder on the machine. Nothing was transferred."),
    NotYours(13, "That folder or file does not belong to the account Paddock signs in as. Nothing was transferred."),
    Symlink(14, "A link stands where Paddock needs a folder or a file, so it stopped. Nothing was transferred."),
    Exists(15, "A file with that name is already there. Nothing was written."),
    /** The plan's "short": the stream ended before the declared count, so no name was given to the part and the part was removed. */
    Truncated(16, "The file stopped arriving before its end. Nothing was kept."),
    OverCap(17, "The file is larger than Paddock transfers. Nothing was transferred."),
    Unwritable(18, "The machine would not let Paddock write there. Nothing was kept."),
    BadName(19, "That file name is not one Paddock can use. Nothing was transferred."),
    BadCount(20, "The size Paddock gave was not a usable number. Nothing was transferred."),
    OutsideFolder(21, "That path is outside the agent's folder. Nothing was fetched."),
    NotRegular(22, "That is not a regular file. Nothing was fetched."),
    Home(23, "That is the home folder itself, which is not a drop folder. Nothing was written."),
    Hidden(24, "That folder is hidden, and a hidden folder is not a drop folder. Nothing was written."),
    OutsideHome(25, "That folder is outside the home folder. Nothing was written."),
    NotFound(26, "That is not on the machine. Nothing was fetched."),
    /** A file whose size is not the one the phone looked at: it changed in between. */
    Changed(27, "The file changed size after it was looked at. Nothing was kept."),
    /** A folder a write would go into that its group or other accounts may write in too: one of them could swap the part for a link before it is filled. */
    SharedFolder(28, "Other accounts on the machine can write in that folder, so Paddock did not write there. Nothing was transferred.");

    /** `over_cap`, `bad_name`: what a journal row holds as its code. */
    val code: String get() = name.replace(Regex("(?<=[a-z])(?=[A-Z])"), "_").lowercase()

    companion object {
        fun forExit(exit: Int): TransferRefusal? = entries.firstOrNull { it.exit == exit }
    }
}

/**
 * The host side of Part B: constant `/bin/sh -c` scripts in the `RelaySagaHost.RESOLVE_FOLDER` pattern, run as
 * `/bin/sh -c <script> paddock-<name> [<cap>]` (see the `...Argv` functions) with every folder, name and path on stdin and never in argv, so a
 * character no login shell reads the same way cannot reach one. Their source below uses `@` for the shell's `$` (a raw Kotlin string would read
 * `$name` as a template), `<TAB>` for a tab and `#` lines for comments; what is sent is one line without a single quote, a backslash or a line
 * break, because that is all `argvToCommand` carries through every login shell, and a check at load time holds it to that.
 *
 * SEC-4, for every script:
 * - **The prelude.** `LC_ALL=C` (byte-wise patterns and lengths), `umask 077` and `set -C`, then `command -v` for every program it calls: a missing
 *   one is its own refusal. `set -C` is a backstop only (POSIX lets `>` follow a dangling link under it); the guarantees are `mktemp`'s exclusive
 *   create and `link`. It runs on the user's own PATH: pinning it would break NixOS and Homebrew layouts.
 * - **The header** (a write script reads `folder`, `name` and `size` as three lines, after one cap as `$1`) is checked before any file is touched:
 *   the size as 1 to 15 digits within the cap, the name against `[A-Za-z0-9][A-Za-z0-9._-]{0,99}`, the folder absolute with no control character.
 * - **The folders.** `.paddock` and the inbox must not be links and must be the user's, checked before and after `mkdir`. `.paddock/.gitignore`
 *   (`*`, X1) is written only into a `.paddock` this run created, so nothing can be planted there first and no git process runs. Every folder a
 *   write goes into (`.paddock`, the inbox, the drop folder) must give other accounts no write, and its group none unless it is the user's own private
 *   group ([ownOnly]): the part is filled by its path, and an account that could write there could swap it for a link in between. The folders a script
 *   creates are 0700.
 * - **The part** is made by `mktemp` (exclusive, 0600, an unpredictable name), filled by `head -c`, counted by `wc -c` as a number, and published
 *   by `link`, which fails whenever the name exists as a file, a link or a directory. `ln` is never used: given a directory (or a link to one) at the
 *   name it puts the file inside it. A trap on EXIT, HUP, INT, TERM and PIPE removes the part; one a dead run left behind is swept after an hour.
 *   A filesystem without hard links therefore refuses a put as unwritable.
 * - Nothing on stdout from a write, nothing evaluated, no command built from input, and operands after `--` wherever the tool takes it (`link`
 *   takes none: both of its operands are absolute).
 *
 * What the scripts do not do is stated in the callers: an instruction-file name is refused by the phone's names (slice 9), not here.
 */
object TransferScripts {
    /** The shell text of a script: lines trimmed and joined, `#` comment lines dropped, `@` read as `$`. Refuses what `argvToCommand` would. */
    private fun sh(raw: String): String {
        val text = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.joinToString(" ").replace('@', '$').replace("<TAB>", "\t")
        check(text.none { it == '\'' || it == '\\' || it == '\n' || it == '\r' || it == '\u0000' }) { "a transfer script must be one line without a quote or a backslash" }
        return text
    }

    private fun x(r: TransferRefusal) = r.exit

    private fun tools(names: String) = "for t in $names; do command -v \"@t\" >/dev/null 2>&1 || exit ${x(MissingTool)}; done;"

    /**
     * Refuses the folder [d] (shell text, `@dir`) unless other accounts have no write on it: `find` prints it only then, and prints nothing when it fails,
     * so a folder it could not judge is refused too. Its group may write only when that group is the user's own private group, one named as the user and
     * their primary group (Debian, Ubuntu and Fedora make one per user and default to umask 002, so a folder made by hand there is 0775; Debian's OpenSSH
     * accepts the same case for its own checks). A shared primary group, such as macOS's `staff`, gets no such allowance. `-perm -g+w`, `-group` and
     * `-prune` are POSIX, and GNU, BSD and busybox `find` read them alike.
     */
    private fun ownOnly(d: String) =
        "w=@(find \"$d\" -prune ! -perm -g+w ! -perm -o+w 2>/dev/null); " +
            "if [ -z \"@w\" ] && [ \"@(id -gn 2>/dev/null)\" = \"@(id -un 2>/dev/null)\" ]; then w=@(find \"$d\" -prune ! -perm -o+w -group \"@(id -un)\" 2>/dev/null); fi; " +
            "[ -n \"@w\" ] || exit ${x(SharedFolder)};"

    private val PRELUDE = """
        LC_ALL=C; export LC_ALL; umask 077; set -C;
    """

    /** The part is removed however the script ends; a signal ends it, which the shell would otherwise only note. */
    private val SIGNALS = """
        part=;
        cleanup() { [ -z "@part" ] || rm -f -- "@part"; };
        trap cleanup EXIT; trap "exit 129" HUP; trap "exit 130" INT; trap "exit 143" TERM; trap "exit 141" PIPE;
    """

    private const val WRITE_TOOLS = "head wc mktemp link mkdir rmdir rm find"

    /** `$1` is the cap in bytes, from the app; the header is `folder`, `name` and `size`, each on a line of its own, then the bytes. */
    private val WRITE_HEADER = """
        cap=@1;
        case "@cap" in ""|*[!0-9]*) exit ${x(BadCount)};; esac;
        [ "@{#cap}" -le 15 ] || exit ${x(BadCount)};
        IFS= read -r folder || exit ${x(BadPath)};
        IFS= read -r name || exit ${x(BadName)};
        IFS= read -r n || exit ${x(BadCount)};
        case "@n" in ""|*[!0-9]*) exit ${x(BadCount)};; esac;
        [ "@{#n}" -le 15 ] || exit ${x(BadCount)};
        [ "@n" -gt 0 ] || exit ${x(BadCount)};
        [ "@n" -le "@cap" ] || exit ${x(OverCap)};
        case "@name" in ""|[!A-Za-z0-9]*|*[!A-Za-z0-9._-]*) exit ${x(BadName)};; esac;
        [ "@{#name}" -le 100 ] || exit ${x(BadName)};
        case "@folder" in ""|*[[:cntrl:]]*) exit ${x(BadPath)};; esac;
    """

    /** The agent's folder, then `.paddock` and `.paddock/inbox` (X1): `dir` is the inbox when this is done. */
    private val INBOX = """
        case "@folder" in /*) ;; *) exit ${x(BadPath)};; esac;
        [ -d "@folder" ] || exit ${x(NotDirectory)};
        [ -O "@folder" ] || exit ${x(NotYours)};
        p="@folder/.paddock"; dir="@p/inbox"; made=;
        [ ! -L "@p" ] || exit ${x(Symlink)};
        if [ ! -e "@p" ]; then if mkdir -m 700 -- "@p" 2>/dev/null; then made=1; fi; fi;
        [ ! -L "@p" ] || exit ${x(Symlink)};
        [ -e "@p" ] || exit ${x(Unwritable)};
        [ -d "@p" ] || exit ${x(NotDirectory)};
        [ -O "@p" ] || exit ${x(NotYours)};
        ${ownOnly("@p")}
        if [ -n "@made" ]; then echo "*" > "@p/.gitignore" 2>/dev/null || { rm -f -- "@p/.gitignore"; rmdir -- "@p"; exit ${x(Unwritable)}; }; fi;
        [ ! -L "@dir" ] || exit ${x(Symlink)};
        if [ ! -e "@dir" ]; then mkdir -m 700 -- "@dir" 2>/dev/null; fi;
        [ ! -L "@dir" ] || exit ${x(Symlink)};
        [ -e "@dir" ] || exit ${x(Unwritable)};
        [ -d "@dir" ] || exit ${x(NotDirectory)};
        [ -O "@dir" ] || exit ${x(NotYours)};
        ${ownOnly("@dir")}
    """

    /**
     * A drop folder's value (`folder`: `~`, `~/x` or absolute) resolved by `pwd -P` after the `~`, with `real` set to the real path and `made` set
     * when it does not exist yet (its parent must). Refused: the home folder itself, a place outside home, anything hidden below home, a link at the
     * last component, a folder that is not the user's. Only the last component is ever created, by [DROP_CREATE].
     */
    private val DROP_RESOLVE = """
        case "@HOME" in /*) ;; *) exit ${x(OutsideHome)};; esac;
        case "@folder" in "~") d="@HOME";; "~/"*) d="@HOME/@{folder#"~/"}";; *) d="@folder";; esac;
        case "@d" in /*) ;; *) exit ${x(BadPath)};; esac;
        while [ "@{d%/}" != "@d" ]; do d=@{d%/}; done;
        [ -n "@d" ] || exit ${x(OutsideHome)};
        rh=@(cd -- "@HOME" 2>/dev/null && pwd -P) || exit ${x(OutsideHome)};
        case "@rh" in ""|*[[:cntrl:]]*) exit ${x(BadPath)};; esac;
        [ ! -L "@d" ] || exit ${x(Symlink)};
        made=;
        if [ -e "@d" ]; then
          [ -d "@d" ] || exit ${x(NotDirectory)};
          real=@(cd -- "@d" 2>/dev/null && pwd -P) || exit ${x(NotDirectory)};
        else
          parent=@{d%/*}; base=@{d##*/};
          [ -n "@parent" ] || parent=/;
          case "@base" in .|..) exit ${x(BadPath)};; esac;
          rp=@(cd -- "@parent" 2>/dev/null && pwd -P) || exit ${x(NotDirectory)};
          case "@rp" in /) real="/@base";; *) real="@rp/@base";; esac;
          made=1;
        fi;
        case "@real" in *[[:cntrl:]]*) exit ${x(BadPath)};; esac;
        [ "@real" != "@rh" ] || exit ${x(Home)};
        case "@real" in "@rh"/*) ;; *) exit ${x(OutsideHome)};; esac;
        rel=@{real#"@rh"/};
        case "/@rel" in */.*) exit ${x(Hidden)};; esac;
        if [ -z "@made" ]; then [ -O "@real" ] || exit ${x(NotYours)}; fi;
    """

    /**
     * Creates the resolved folder 0700 when it was new, then checks what is there again (a link planted in between is refused, and so is a folder
     * other accounts may write in); `dir` is the folder.
     */
    private val DROP_CREATE = """
        if [ -n "@made" ]; then mkdir -m 700 -- "@real" 2>/dev/null; fi;
        [ ! -L "@real" ] || exit ${x(Symlink)};
        [ -e "@real" ] || exit ${x(Unwritable)};
        [ -d "@real" ] || exit ${x(NotDirectory)};
        [ -O "@real" ] || exit ${x(NotYours)};
        ${ownOnly("@real")}
        dir="@real";
    """

    /** The sweep, the part, the count and the publish, into `dir`. A part is named `.<name>.part.<random>`, which a published name never is. */
    private val PUBLISH = """
        find "@dir" -maxdepth 1 -type f -name ".*.part.*" -mmin +60 -exec rm -f -- {} + >/dev/null 2>&1;
        part=@(mktemp "@dir/.@name.part.XXXXXXXX" 2>/dev/null) || exit ${x(Unwritable)};
        head -c "@n" >| "@part" || exit ${x(Unwritable)};
        got=@(wc -c < "@part"); set -- @got; got=@1;
        [ "@got" -eq "@n" ] || exit ${x(Truncated)};
        if link "@part" "@dir/@name" 2>/dev/null; then rm -f -- "@part"; part=; exit 0; fi;
        if [ -e "@dir/@name" ] || [ -L "@dir/@name" ]; then exit ${x(Exists)}; fi;
        exit ${x(Unwritable)};
    """

    /** An attachment: header `folder`, `name`, `size`; writes `<folder>/.paddock/inbox/<name>` (X1, X9). `$1` is the cap. */
    val PUT = sh(PRELUDE + SIGNALS + tools(WRITE_TOOLS) + WRITE_HEADER + INBOX + PUBLISH)

    /** A drop (X10): header `folder` (`~`, `~/x` or absolute), `name`, `size`; writes `<real folder>/<name>`, creating the folder 0700. `$1` is the cap. */
    val DROP = sh(PRELUDE + SIGNALS + tools(WRITE_TOOLS) + WRITE_HEADER + DROP_RESOLVE + DROP_CREATE + PUBLISH)

    /** What a drop folder's value resolves to: one line, the real path (a folder that does not exist yet is named by its real parent), or a refusal. */
    val RESOLVE_DROP = sh(PRELUDE + """
        IFS= read -r folder || exit ${x(BadPath)};
        case "@folder" in ""|*[[:cntrl:]]*) exit ${x(BadPath)};; esac;
    """ + DROP_RESOLVE + """
        printf "%s" "@real"; echo;
    """)

    /** Looks at a path under an agent's folder on its real path; sets `real` and `size`. `$1` is the cap. */
    private val JUDGE_PATH = """
        case "@folder" in /*) ;; *) exit ${x(BadPath)};; esac;
        case "@path" in /*) ;; *) exit ${x(BadPath)};; esac;
        case "@folder@path" in *[[:cntrl:]]*) exit ${x(BadPath)};; esac;
        rf=@(cd -- "@folder" 2>/dev/null && pwd -P) || exit ${x(NotDirectory)};
        parent=@{path%/*}; base=@{path##*/};
        [ -n "@parent" ] || parent=/;
        [ -n "@base" ] || exit ${x(NotRegular)};
        rp=@(cd -- "@parent" 2>/dev/null && pwd -P) || exit ${x(NotFound)};
        case "@rp" in /) real="/@base";; *) real="@rp/@base";; esac;
        case "@real" in *[[:cntrl:]]*) exit ${x(BadPath)};; esac;
        case "@real" in "@rf"/*) ;; *) exit ${x(OutsideFolder)};; esac;
        [ ! -L "@real" ] || exit ${x(Symlink)};
        [ -e "@real" ] || exit ${x(NotFound)};
        [ -f "@real" ] || exit ${x(NotRegular)};
        size=@(wc -c < "@real" 2>/dev/null) || exit ${x(NotFound)};
        set -- @size; size=@1;
        [ "@size" -le "@cap" ] || exit ${x(OverCap)};
    """

    private val CAP = """
        cap=@1;
        case "@cap" in ""|*[!0-9]*) exit ${x(BadCount)};; esac;
        [ "@{#cap}" -le 15 ] || exit ${x(BadCount)};
    """

    private val READ_FOLDER_PATH = """
        IFS= read -r folder || exit ${x(BadPath)};
        IFS= read -r path || exit ${x(BadPath)};
    """

    /**
     * Header `folder` (the agent's), `path`; prints `<size>`, a tab, `<mtime in seconds>`, a tab and the real path, judged as X2 says: the parent
     * resolved with `pwd -P`, the last component not a link, a regular file, under the real folder plus a separator. The size is `wc -c` (the
     * tools' own `stat` formats differ); the time is `stat -c %Y` (GNU), else `stat -f %m` (BSD). `$1` is the cap.
     */
    val STAT = sh(PRELUDE + tools("wc stat") + CAP + READ_FOLDER_PATH + JUDGE_PATH + """
        T="<TAB>";
        mt=@(stat -c %Y -- "@real" 2>/dev/null) || mt=@(stat -f %m -- "@real" 2>/dev/null) || exit ${x(NotFound)};
        case "@mt" in ""|*[!0-9]*) exit ${x(NotFound)};; esac;
        printf "%s%s%s%s%s" "@size" "@T" "@mt" "@T" "@real"; echo;
        exit 0;
    """)

    /**
     * Header `folder`, `path`, `size` (the stat's): the same judgement, then the file's bytes on stdout, at most one more than `size` so a file that
     * grew is seen to have (the phone counts) without an unbounded read. A file whose size is no longer `size` is refused. `$1` is the cap.
     */
    val GET = sh(PRELUDE + tools("head wc") + CAP + READ_FOLDER_PATH + """
        IFS= read -r want || exit ${x(BadCount)};
        case "@want" in ""|*[!0-9]*) exit ${x(BadCount)};; esac;
        [ "@{#want}" -le 15 ] || exit ${x(BadCount)};
    """ + JUDGE_PATH + """
        [ "@size" -eq "@want" ] || exit ${x(Changed)};
        head -c @((want+1)) < "@real";
    """)

    /**
     * Header `dir` (an absolute folder: the agent's `.paddock/outbox` or the drop folder): the newest 50 regular files, newest first, one per line as
     * name, a tab, size, a tab and the time in seconds. A name with a control character (a tab and a line break included), a link, a hidden name
     * and anything that is not a regular file are skipped, and at most 1,000 names are looked at (each costs two programs, so a bigger folder would outrun the deadline). A folder that is not there is [TransferRefusal.NotFound].
     */
    val LIST = sh(PRELUDE + tools("wc stat sort head") + """
        IFS= read -r dir || exit ${x(BadPath)};
        case "@dir" in /*) ;; *) exit ${x(BadPath)};; esac;
        case "@dir" in *[[:cntrl:]]*) exit ${x(BadPath)};; esac;
        [ ! -L "@dir" ] || exit ${x(Symlink)};
        [ -e "@dir" ] || exit ${x(NotFound)};
        [ -d "@dir" ] || exit ${x(NotDirectory)};
        [ -O "@dir" ] || exit ${x(NotYours)};
        T="<TAB>"; k=0;
        for f in "@dir"/*; do
          k=@((k+1)); [ "@k" -le 1000 ] || break;
          case "@f" in *[[:cntrl:]]*) continue;; esac;
          [ ! -L "@f" ] || continue;
          [ -f "@f" ] || continue;
          size=@(wc -c < "@f" 2>/dev/null) || continue;
          set -- @size;
          mt=@(stat -c %Y -- "@f" 2>/dev/null) || mt=@(stat -f %m -- "@f" 2>/dev/null) || continue;
          case "@mt" in ""|*[!0-9]*) continue;; esac;
          printf "%s%s%s%s%s" "@{f##*/}" "@T" "@1" "@T" "@mt"; echo;
        done | sort -t "@T" -k3,3nr | head -n 50;
        exit 0;
    """)

    /** Header `folder` (the agent's), `name`: `present`, a tab and the size, or `absent`, for `<folder>/.paddock/inbox/<name>`. Creates nothing. */
    val CHECK = sh(PRELUDE + tools("wc") + """
        IFS= read -r folder || exit ${x(BadPath)};
        IFS= read -r name || exit ${x(BadName)};
        case "@name" in ""|[!A-Za-z0-9]*|*[!A-Za-z0-9._-]*) exit ${x(BadName)};; esac;
        [ "@{#name}" -le 100 ] || exit ${x(BadName)};
        case "@folder" in /*) ;; *) exit ${x(BadPath)};; esac;
        case "@folder" in *[[:cntrl:]]*) exit ${x(BadPath)};; esac;
        [ -d "@folder" ] || exit ${x(NotDirectory)};
        i="@folder/.paddock/inbox"; f="@i/@name";
        [ ! -L "@folder/.paddock" ] || exit ${x(Symlink)};
        [ ! -L "@i" ] || exit ${x(Symlink)};
        if [ ! -e "@f" ] && [ ! -L "@f" ]; then echo absent; exit 0; fi;
        [ ! -L "@f" ] || exit ${x(Symlink)};
        [ -f "@f" ] || exit ${x(NotRegular)};
        [ -O "@f" ] || exit ${x(NotYours)};
        size=@(wc -c < "@f" 2>/dev/null) || exit ${x(NotFound)};
        set -- @size;
        T="<TAB>";
        printf "present%s%s" "@T" "@1"; echo;
        exit 0;
    """)

    private fun argv(script: String, name: String, cap: Long? = null) = listOfNotNull("/bin/sh", "-c", script, "paddock-$name", cap?.also { require(it in 1..999_999_999_999_999L) }?.toString())

    fun putArgv(cap: Long) = argv(PUT, "put", cap)
    fun dropArgv(cap: Long) = argv(DROP, "drop", cap)
    fun resolveDropArgv() = argv(RESOLVE_DROP, "resolve-drop")
    fun statArgv(cap: Long) = argv(STAT, "stat", cap)
    fun getArgv(cap: Long) = argv(GET, "get", cap)
    fun listArgv() = argv(LIST, "list")
    fun checkArgv() = argv(CHECK, "check")

    /** The name a write script accepts: what its `case` patterns and length check allow, for the phone to refuse the same before it opens a channel. */
    val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
}
